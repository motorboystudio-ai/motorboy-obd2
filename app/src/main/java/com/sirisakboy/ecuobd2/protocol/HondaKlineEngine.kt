package com.sirisakboy.ecuobd2.protocol

import com.sirisakboy.ecuobd2.transport.RawByteTransport
import kotlinx.coroutines.delay

enum class EcuState(val code: Int, val displayName: String) {
    UNDEFINED(-1, "UNDEFINED"),
    OFF(0, "OFF (ignition off)"),
    READ(1, "READ"),
    READING(2, "READING"),
    OK(3, "OK (normal)"),
    RECOVER_OLD(4, "RECOVER OLD"),
    RECOVER_NEW(5, "RECOVER NEW"),
    WRITE(6, "WRITE"),
    WRITING(7, "WRITING"),
    ERASING(8, "ERASING"),
    INIT_WRITE(9, "INIT WRITE"),
    INIT_RECOVER(10, "INIT RECOVER"),
    ERROR(11, "ERROR"),
    UNKNOWN(12, "UNKNOWN");

    companion object {
        fun fromCode(code: Int) = entries.find { it.code == code } ?: UNKNOWN
    }
}

data class HondaFaults(
    val current: List<String> = emptyList(),
    val past: List<String> = emptyList()
)

data class HondaResponse(
    val rmtype: ByteArray,
    val rml: Int,
    val rdata: ByteArray,
    val rdl: Int
)

object HondaConstants {
    val HONDA_DTC = mapOf(
        "01-01" to "MAP sensor circuit low voltage",
        "01-02" to "MAP sensor circuit high voltage",
        "02-01" to "MAP sensor performance problem",
        "07-01" to "ECT sensor circuit low voltage",
        "07-02" to "ECT sensor circuit high voltage",
        "08-01" to "TP sensor circuit low voltage",
        "08-02" to "TP sensor circuit high voltage",
        "09-01" to "IAT sensor circuit low voltage",
        "09-02" to "IAT sensor circuit high voltage",
        "11-01" to "VS sensor no signal",
        "12-01" to "No.1 primary injector circuit malfunction",
        "13-01" to "No.2 primary injector circuit malfunction",
        "14-01" to "No.3 primary injector circuit malfunction",
        "15-01" to "No.4 primary injector circuit malfunction",
        "16-01" to "No.1 secondary injector circuit malfunction",
        "17-01" to "No.2 secondary injector circuit malfunction",
        "18-01" to "CMP sensor no signal",
        "19-01" to "CKP sensor no signal",
        "21-01" to "O2 sensor malfunction",
        "23-01" to "O2 sensor heater malfunction",
        "25-02" to "Knock sensor circuit malfunction",
        "25-03" to "Knock sensor circuit malfunction",
        "29-01" to "IACV circuit malfunction",
        "33-02" to "ECM EEPROM malfunction",
        "34-01" to "ECV POT low voltage malfunction",
        "34-02" to "ECV POT high voltage malfunction",
        "35-01" to "EGCA malfunction",
        "48-01" to "No.3 secondary injector circuit malfunction",
        "49-01" to "No.4 secondary injector circuit malfunction",
        "51-01" to "HESD linear solenoid malfunction",
        "54-01" to "Bank angle sensor circuit low voltage",
        "54-02" to "Bank angle sensor circuit high voltage",
        "56-01" to "Knock sensor IC malfunction",
        "86-01" to "Serial communication malfunction"
    )

    fun checksum8bitHonda(data: ByteArray): Byte {
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        return (((sum xor 0xFF) + 1) and 0xFF).toByte()
    }

    fun formatMessage(mtype: Byte, data: ByteArray): ByteArray {
        val dl = data.size
        val msgsize = 0x02 + 1 + dl
        val msg = ByteArray(msgsize)
        msg[0] = mtype
        msg[1] = msgsize.toByte()
        if (dl > 0) {
            System.arraycopy(data, 0, msg, 2, dl)
        }
        msg[msgsize - 1] = checksum8bitHonda(msg.copyOfRange(0, msgsize - 1))
        return msg
    }

    fun formatReadAddress(location: Int): ByteArray {
        val b0 = ((location ushr 16) and 0xFF).toByte()
        val b1 = (location and 0xFF).toByte()
        val b2 = ((location ushr 8) and 0xFF).toByte()
        return byteArrayOf(b0, b1, b2)
    }

    fun addressFromBytes(b0: Int, b1: Int, b2: Int): Int {
        return ((b0 and 0xFF) shl 16) or ((b2 and 0xFF) shl 8) or (b1 and 0xFF)
    }

    fun toHexStr(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}

class HondaKlineEngine(
    private val transport: RawByteTransport,
    private val log: (msg: String, dir: String) -> Unit = { _, _ -> }
) {
    fun formatMessage(mtype: ByteArray, data: ByteArray): ByteArray {
        val ml = mtype.size
        val dl = data.size
        val msgsize = 0x02 + ml + dl
        val msg = ByteArray(msgsize)
        System.arraycopy(mtype, 0, msg, 0, ml)
        msg[ml] = msgsize.toByte()
        if (dl > 0) {
            System.arraycopy(data, 0, msg, ml + 1, dl)
        }
        msg[msgsize - 1] = HondaConstants.checksum8bitHonda(msg.copyOfRange(0, msgsize - 1))
        return msg
    }

    suspend fun send(msg: ByteArray, ml: Int): ByteArray? {
        transport.write(msg)

        // 1. Consume echo
        val t0 = System.currentTimeMillis()
        val echoBudget = 80 + msg.size * 3L
        var got = 0
        while (got < msg.size) {
            val remain = echoBudget - (System.currentTimeMillis() - t0)
            if (remain <= 0) return null
            val b = transport.readUntil(msg.size - got, minOf(50L, remain))
            if (b.isEmpty()) return null
            got += b.size
        }

        // 2. Read response header
        val t1 = System.currentTimeMillis()
        val buf = mutableListOf<Byte>()
        var gotLen = 0
        while (gotLen < ml + 1) {
            val remain = 150 - (System.currentTimeMillis() - t1)
            if (remain <= 0) return null
            val b = transport.readUntil(ml + 1 - gotLen, minOf(50L, remain))
            if (b.isEmpty()) return null
            for (x in b) buf.add(x)
            gotLen += b.size
        }

        // 3. Read remaining bytes
        val totalMsgSize = buf.last().toInt() and 0xFF
        var need = totalMsgSize - ml - 1
        while (need > 0) {
            val b = transport.readUntil(need, 50L)
            if (b.isEmpty()) return null
            for (x in b) buf.add(x)
            need -= b.size
        }

        return buf.toByteArray()
    }

    suspend fun sendCommand(
        mtype: ByteArray,
        data: ByteArray = byteArrayOf(),
        retries: Int = 1,
        delayMs: Long = 0
    ): HondaResponse? {
        val msg = formatMessage(mtype, data)
        val ml = mtype.size

        for (r in 0..retries) {
            log("> ${HondaConstants.toHexStr(msg)}", "send")
            try { transport.drain() } catch (_: Exception) {}
            val resp = send(msg, ml)
            if (resp != null && resp.size >= ml + 2) {
                log("< ${HondaConstants.toHexStr(resp)}", "recv")
                val calcChecksum = HondaConstants.checksum8bitHonda(resp.copyOfRange(0, resp.size - 1))
                if (calcChecksum == resp.last()) {
                    val rmtype = resp.copyOfRange(0, ml)
                    var valid = false
                    if (ml == 3) {
                        valid = (rmtype[0].toInt() and 0xFF) == ((mtype[0].toInt() and 0xFF) or 0x10) &&
                                (rmtype[1].toInt() and 0xFF) == ((mtype[1].toInt() and 0xFF) or 0x10)
                    } else if (ml == 2) {
                        valid = rmtype[0] == mtype[0] && rmtype[1] == mtype[1]
                    } else if (ml == 1) {
                        valid = (rmtype[0].toInt() and 0xFF) == ((mtype[0].toInt() and 0xFF) and 0x0F)
                    }

                    if (valid) {
                        val rml = resp[ml].toInt() and 0xFF
                        val rdl = rml - 2 - ml
                        val rdata = resp.copyOfRange(ml + 1, resp.size - 1)
                        if (delayMs > 0) delay(delayMs)
                        return HondaResponse(rmtype, rml, rdata, rdl)
                    }
                }
            }
        }
        return null
    }

    suspend fun ping(): Boolean {
        return sendCommand(byteArrayOf(0xFE.toByte()), byteArrayOf(0x72.toByte()), retries = 0) != null
    }

    suspend fun diag(): Boolean {
        return sendCommand(byteArrayOf(0x72.toByte()), byteArrayOf(0x00.toByte(), 0xF0.toByte()), retries = 0) != null
    }

    suspend fun detectEcuState(): EcuState {
        var t0 = sendCommand(byteArrayOf(0x72.toByte()), byteArrayOf(0x71.toByte(), 0x00.toByte()), retries = 0)
        if (t0 == null) {
            delay(50)
            ping()
            t0 = sendCommand(byteArrayOf(0x72.toByte()), byteArrayOf(0x71.toByte(), 0x00.toByte()), retries = 0)
        }
        if (t0 != null && t0.rdata.size >= 7) {
            val d5 = t0.rdata[5].toInt() and 0xFF
            val d6 = t0.rdata[6].toInt() and 0xFF
            if (d5 != 0 || d6 != 0) return EcuState.OK
        }

        val recoverOld = sendCommand(byteArrayOf(0x7D.toByte()), byteArrayOf(0x01, 0x01, 0x00), retries = 0)
        if (recoverOld != null) return EcuState.RECOVER_OLD

        val recoverNew = sendCommand(byteArrayOf(0x7B.toByte()), byteArrayOf(0x00, 0x01, 0x01), retries = 0)
        if (recoverNew != null) return EcuState.RECOVER_NEW

        val ws = sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x01, 0x00), retries = 0)
        if (ws != null) {
            if (ws.rdata.size > 1 && (ws.rdata[1].toInt() and 0xFF) == 0xF0) return EcuState.ERROR
            return EcuState.WRITE
        }

        val readResp = sendCommand(byteArrayOf(0x82.toByte(), 0x82.toByte(), 0x00.toByte()), byteArrayOf(0x00, 0x00, 0x00, 0x08), retries = 0)
        if (readResp != null) return EcuState.READ

        return if (t0 != null) EcuState.UNKNOWN else EcuState.OFF
    }

    suspend fun probeTables(tables: List<Int> = listOf(0x10, 0x11, 0x17, 0x20, 0x21, 0x60, 0x61, 0x67, 0x70, 0x71, 0xD0, 0xD1)): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        for (t in tables) {
            val info = sendCommand(byteArrayOf(0x72.toByte()), byteArrayOf(0x71.toByte(), t.toByte()))
            if (info != null && info.rdl > 2) {
                result[t] = info.rdata
            }
        }
        return result
    }

    suspend fun getFaults(): HondaFaults {
        val currentFaults = mutableListOf<String>()
        val pastFaults = mutableListOf<String>()

        for ((sub, list) in listOf(0x74 to currentFaults, 0x73 to pastFaults)) {
            for (i in 1 until 12) {
                val info = sendCommand(byteArrayOf(0x72.toByte()), byteArrayOf(sub.toByte(), i.toByte()), retries = 0)
                if (info == null) break
                val d = info.rdata
                for (j in listOf(3, 5, 7)) {
                    if (j + 1 < d.size && d[j].toInt() != 0) {
                        val code = "%02d-%02d".format(d[j].toInt() and 0xFF, d[j + 1].toInt() and 0xFF)
                        list.add(code)
                    }
                }
                if (d.size > 2 && d[2].toInt() == 0) break
            }
        }

        return HondaFaults(current = currentFaults, past = pastFaults)
    }

    suspend fun initRecover(): Boolean {
        sendCommand(byteArrayOf(0x7B.toByte()), byteArrayOf(0x00, 0x01, 0x01), delayMs = 300)
        sendCommand(byteArrayOf(0x7B.toByte()), byteArrayOf(0x00, 0x01, 0x02), delayMs = 300)
        sendCommand(byteArrayOf(0x7B.toByte()), byteArrayOf(0x00, 0x01, 0x03), delayMs = 300)
        sendCommand(byteArrayOf(0x7B.toByte()), byteArrayOf(0x00, 0x02, 0x76.toByte(), 0x03, 0x17), delayMs = 300)
        sendCommand(byteArrayOf(0x7B.toByte()), byteArrayOf(0x00, 0x03, 0x75.toByte(), 0x05, 0x13), delayMs = 300)
        return true
    }

    suspend fun initWrite(): Boolean {
        sendCommand(byteArrayOf(0x7D.toByte()), byteArrayOf(0x01, 0x01, 0x01), delayMs = 300)
        sendCommand(byteArrayOf(0x7D.toByte()), byteArrayOf(0x01, 0x01, 0x02), delayMs = 300)
        sendCommand(byteArrayOf(0x7D.toByte()), byteArrayOf(0x01, 0x01, 0x03), delayMs = 300)
        sendCommand(byteArrayOf(0x7D.toByte()), byteArrayOf(0x01, 0x02, 0x50, 0x47, 0x4D), delayMs = 300)
        sendCommand(byteArrayOf(0x7D.toByte()), byteArrayOf(0x01, 0x03, 0x2D, 0x46, 0x49), delayMs = 300)
        return true
    }

    suspend fun erase(): Boolean {
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x02), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x03, 0x00, 0x00), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x0B, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x0E, 0x01, 0x90.toByte()), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x01, 0x01), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x04, 0xFF.toByte()), delayMs = 300)
        return true
    }

    suspend fun eraseWait(maxRounds: Int = 40): Boolean {
        for (round in 0 until maxRounds) {
            val info = sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x05), retries = 0)
            if (info != null && info.rdata.size > 1 && info.rdata[1].toInt() == 0x00) {
                return sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x01, 0x00), retries = 0) != null
            }
            delay(300)
        }
        throw Exception("Erase wait: ECU never reported erase done")
    }

    suspend fun postWrite(): Boolean {
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x09), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x0A), delayMs = 300)
        sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x0C), delayMs = 300)
        val info = sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x0D), delayMs = 300)
        return info != null && info.rdata.size > 1 && (info.rdata[1].toInt() and 0xFF) == 0x0F
    }

    suspend fun readRom(
        address: Int,
        length: Int,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ByteArray {
        val out = ByteArray(length)
        var off = 0
        val chunk = 24
        while (off < length) {
            val n = minOf(chunk, length - off)
            val addrBytes = HondaConstants.formatReadAddress(address + off)
            val reqData = byteArrayOf(0x01, 0x06, addrBytes[0], addrBytes[1], addrBytes[2])
            val resp = sendCommand(byteArrayOf(0x7E.toByte()), reqData, retries = 2)
                ?: throw Exception("ROM read failed at 0x%06X".format(address + off))
            if (resp.rdata.size < n) {
                throw Exception("ROM read short response at 0x%06X".format(address + off))
            }
            System.arraycopy(resp.rdata, 0, out, off, n)
            off += n
            onProgress?.invoke(off, length)
        }
        return out
    }

    suspend fun writeRom(
        address: Int,
        bytes: ByteArray,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Boolean {
        var off = 0
        val chunk = 3
        while (off < bytes.size) {
            val n = minOf(chunk, bytes.size - off)
            val addrBytes = HondaConstants.formatReadAddress(address + off)
            val req = ByteArray(5 + n)
            req[0] = 0x01
            req[1] = 0x0B
            req[2] = addrBytes[0]
            req[3] = addrBytes[1]
            req[4] = addrBytes[2]
            System.arraycopy(bytes, off, req, 5, n)

            val resp = sendCommand(byteArrayOf(0x7E.toByte()), req, retries = 2, delayMs = 5)
                ?: throw Exception("ROM write failed at 0x%06X".format(address + off))
            off += n
            onProgress?.invoke(off, bytes.size)
        }
        writeWait()
        return true
    }

    suspend fun writeWait(maxRounds: Int = 40): Boolean {
        for (round in 0 until maxRounds) {
            val info = sendCommand(byteArrayOf(0x7E.toByte()), byteArrayOf(0x01, 0x05), retries = 0)
            if (info != null && info.rdata.size > 1 && info.rdata[1].toInt() == 0x00) return true
            delay(200)
        }
        throw Exception("Write wait timeout")
    }

    suspend fun verifyRom(
        address: Int,
        expected: ByteArray,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Boolean {
        val actual = readRom(address, expected.size, onProgress)
        for (i in expected.indices) {
            if (actual[i] != expected[i]) {
                throw Exception("ROM verify mismatch at 0x%06X: expected 0x%02X, got 0x%02X".format(
                    address + i, expected[i].toInt() and 0xFF, actual[i].toInt() and 0xFF
                ))
            }
        }
        return true
    }
}
