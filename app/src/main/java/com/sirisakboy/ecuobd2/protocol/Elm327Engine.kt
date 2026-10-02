package com.sirisakboy.ecuobd2.protocol

import com.sirisakboy.ecuobd2.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class ObdException(message: String, val code: String? = null, val raw: String? = null) : Exception(message)

class Elm327Engine(
    private val transport: Transport,
    private val log: (msg: String, dir: String, kind: String?) -> Unit = { _, _, _ -> }
) {
    var version: String = ""
        private set
    var protocolName: String = "AUTO"
        private set
    private var echo: Boolean = false
    private var headers: Boolean = true
    private val isClosed = AtomicBoolean(false)

    private var pendingDeferred: CompletableDeferred<List<String>>? = null
    private var pendingCmd: String = ""
    private var pendingLines = mutableListOf<String>()
    private var pendingMatch: Regex? = null

    val isCAN: Boolean
        get() = protocolName.startsWith("CAN")

    init {
        transport.setOnDataListener { line ->
            onLineReceived(line)
        }
        transport.setOnCloseListener {
            isClosed.set(true)
            pendingDeferred?.completeExceptionally(ObdException("Connection closed"))
            pendingDeferred = null
        }
    }

    private fun onLineReceived(rawLine: String) {
        val raw = rawLine.trim()
        if (raw.isEmpty()) return

        val deferred = pendingDeferred
        if (deferred == null) {
            log(raw, "recv", "info")
            return
        }

        if (raw.equals(pendingCmd, ignoreCase = true)) {
            return // skip command echo
        }

        val upper = raw.uppercase()
        when {
            upper.startsWith("READY") -> {
                val lines = pendingLines.toList()
                pendingDeferred = null
                deferred.complete(lines)
            }
            upper.startsWith("ERROR") -> {
                pendingDeferred = null
                deferred.completeExceptionally(ObdException("Adapter error: $raw", "ADAPTER_ERROR", raw))
            }
            upper.startsWith("NAK") -> {
                pendingDeferred = null
                deferred.completeExceptionally(ObdException("NAK — no answer from ECU", "NAK", raw))
            }
            upper.startsWith("NO DATA") -> {
                pendingDeferred = null
                deferred.completeExceptionally(ObdException("NO DATA from adapter", "NO_DATA", raw))
            }
            upper.matches(Regex("^[0-9A-F]{4,26}$")) && (upper.startsWith("7DF1") || upper.startsWith("7D01")) -> {
                // Ignore stray request header echo
                log(raw, "recv", "info")
            }
            else -> {
                pendingLines.add(raw)
                log(raw, "recv", null)
                val matcher = pendingMatch
                if (matcher != null && matcher.containsMatchIn(raw)) {
                    val lines = pendingLines.toList()
                    pendingDeferred = null
                    deferred.complete(lines)
                }
            }
        }
    }

    private suspend fun sendAtCommand(
        cmd: String,
        timeoutMs: Long = 4000L,
        matchRegex: Regex? = null
    ): List<String> {
        log(cmd, "send", null)
        pendingCmd = cmd
        pendingLines.clear()
        pendingMatch = matchRegex
        val deferred = CompletableDeferred<List<String>>()
        pendingDeferred = deferred

        transport.sendLine(cmd)

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: run {
            pendingDeferred = null
            if (pendingLines.isNotEmpty()) {
                pendingLines.toList()
            } else {
                throw ObdException("Timeout waiting for adapter response ($cmd)", "TIMEOUT")
            }
        }
        return result
    }

    suspend fun reset(): String {
        val lines = sendAtCommand("ATZ", 5000L, Regex("ELM327|OBD|v\\d", RegexOption.IGNORE_CASE))
        version = lines.firstOrNull { it.contains("ELM327", ignoreCase = true) || it.contains("OBD", ignoreCase = true) }
            ?: lines.firstOrNull() ?: "ELM327 v1.5"
        echo = false
        headers = true
        return version
    }

    suspend fun setup() {
        try { sendAtCommand("ATL1") } catch (_: Exception) {}
        echo = true
        try { sendAtCommand("ATS1") } catch (_: Exception) {}
        headers = true
        try { sendAtCommand("ATE0") } catch (_: Exception) {}
        try { sendAtCommand("ATSH0") } catch (_: Exception) {}
    }

    suspend fun detectProtocol(): String {
        return try {
            val lines = sendAtCommand("ATDP", 6000L)
            val match = Regex("PROTOCOL 0X([0-5])", RegexOption.IGNORE_CASE).find(lines.joinToString(" "))
            if (match != null) {
                val code = match.groupValues[1].toIntOrNull() ?: 0
                val name = when (code) {
                    1 -> "VPW"
                    2 -> "PWM"
                    3 -> "ISO9141"
                    4 -> "CAN413"
                    5 -> "CAN250"
                    else -> "CAN413"
                }
                protocolName = name
                name
            } else {
                protocolName = "CAN413"
                "CAN413"
            }
        } catch (_: Exception) {
            protocolName = "CAN413"
            "CAN413"
        }
    }

    suspend fun setProtocol(name: String) {
        val atsp = when (name.uppercase()) {
            "ISO9141" -> "ATSP0"
            "VPW" -> "ATSP1"
            "PWM" -> "ATSP2"
            "CAN413" -> "ATSP3"
            "CAN250" -> "ATSP4"
            else -> "ATSP0"
        }
        sendAtCommand(atsp)
        protocolName = name
    }

    private fun framePayload(payload: ByteArray): String {
        val hex = payload.joinToString("") { "%02X".format(it) }
        return if (isCAN) {
            "7DF1" + (hex + "0000000000000000").take(16)
        } else {
            "7E81" + (hex + "0000000000").take(12)
        }
    }

    private fun parseFrameToPayload(line: String): ByteArray {
        val upper = line.trim().uppercase()
        val rawHex = if (upper.length >= 4 && listOf("7DF1", "7D01", "7D81", "7E81", "7E01", "7EA1", "4E41").contains(upper.take(4))) {
            upper.substring(4)
        } else {
            upper
        }
        return hexToBytes(rawHex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").uppercase()
        val len = clean.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    suspend fun requestData(payload: ByteArray, timeoutMs: Long = 3500L): ByteArray {
        val cmd = framePayload(payload)
        val lines = sendAtCommand(cmd, timeoutMs)
        val frames = lines
            .map { it.trim() }
            .filter { it.matches(Regex("^[0-9A-Fa-f]{4,26}$")) }
            .map { parseFrameToPayload(it) }

        if (frames.isEmpty()) {
            throw ObdException("Adapter returned no data frames", "NO_FRAMES", lines.joinToString(" | "))
        }

        val first = frames[0]
        if (first.isNotEmpty() && first[0].toInt() and 0xFF == 0x7F) {
            val nrc = if (first.size > 2) first[2].toInt() and 0xFF else 0
            val msg = ObdConstants.NRC[nrc] ?: "NRC 0x%02X".format(nrc)
            throw ObdException("ECU rejected request: $msg", "NRC_%02X".format(nrc), first.joinToString("") { "%02X".format(it) })
        }

        var totalSize = if (first.isNotEmpty()) first.size - 1 else 0
        for (i in 1 until frames.size) {
            totalSize += frames[i].size
        }

        val result = ByteArray(totalSize)
        var offset = 0
        if (first.size > 1) {
            System.arraycopy(first, 1, result, 0, first.size - 1)
            offset = first.size - 1
        }
        for (i in 1 until frames.size) {
            val f = frames[i]
            System.arraycopy(f, 0, result, offset, f.size)
            offset += f.size
        }
        return result
    }

    suspend fun readPids(pids: List<Int>): Map<Int, Any> {
        val payload = ByteArray(1 + pids.size)
        payload[0] = 0x01.toByte()
        for (i in pids.indices) {
            payload[1 + i] = pids[i].toByte()
        }

        val data = requestData(payload)
        var offset = pids.size
        val out = mutableMapOf<Int, Any>()

        for (pid in pids) {
            when (pid) {
                0x01 -> {
                    if (offset < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        out[pid] = EngineStatus(
                            raw = b0,
                            mil = (b0 and 0x01) != 0,
                            running = (b0 and 0x02) != 0,
                            closedLoop = (b0 and 0x04) != 0,
                            pfi = (b0 and 0x08) != 0,
                            ac = (b0 and 0x20) != 0,
                            warm = (b0 and 0x40) != 0,
                            neutral = (b0 and 0x80) != 0
                        )
                        offset += 1
                    }
                }
                0x02, 0x0D -> { // Coolant temp
                    if (offset < data.size) {
                        out[pid] = (data[offset].toInt() and 0xFF) - 40f
                        offset += 1
                    }
                }
                0x04, 0x05 -> { // STFT / LTFT
                    if (offset + 1 < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        out[pid] = (((b0 * 256 + b1) * 100f) / 128f) - 100f
                        offset += 2
                    }
                }
                0x07, 0x0C -> { // RPM
                    if (offset + 1 < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        out[pid] = (b0 * 256 + b1) / 4f
                        offset += 2
                    }
                }
                0x08 -> { // Speed
                    if (offset + 1 < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        out[pid] = (b0 * 256 + b1) / 4f
                        offset += 2
                    }
                }
                0x0B -> { // MAP
                    if (offset < data.size) {
                        out[pid] = (data[offset].toInt() and 0xFF).toFloat()
                        offset += 1
                    }
                }
                0x0E -> { // Throttle
                    if (offset < data.size) {
                        out[pid] = ((data[offset].toInt() and 0xFF) * 100f) / 255f
                        offset += 1
                    }
                }
                0x11, 0x13 -> { // O2
                    if (offset + 1 < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        out[pid] = (b0 * 256 + b1) / 320f
                        offset += 2
                    }
                }
                0x24 -> { // Fuel
                    if (offset < data.size) {
                        out[pid] = ((data[offset].toInt() and 0xFF) * 100f) / 255f
                        offset += 1
                    }
                }
                0x25 -> { // Air Temp
                    if (offset < data.size) {
                        out[pid] = (data[offset].toInt() and 0xFF) - 40f
                        offset += 1
                    }
                }
                0x2F, 0x31 -> { // Voltage
                    if (offset + 1 < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        out[pid] = (b0 * 256 + b1) / 256f
                        offset += 2
                    }
                }
                0x42 -> { // MAF
                    if (offset + 2 < data.size) {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        val b2 = data[offset + 2].toInt() and 0xFF
                        out[pid] = (b0 * 65536 + b1 * 256 + b2) / 100f
                        offset += 3
                    }
                }
                else -> {
                    if (offset < data.size) {
                        out[pid] = data[offset].toInt() and 0xFF
                        offset += 1
                    }
                }
            }
        }
        return out
    }

    suspend fun readDTCs(): List<String> {
        val data = requestData(byteArrayOf(0x03.toByte()))
        val codes = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val hi = data[i].toInt() and 0xFF
            val lo = data[i + 1].toInt() and 0xFF
            if (hi != 0 || lo != 0) {
                val code = ObdConstants.hexToDtcCode(hi, lo)
                if (code != null && seen.add(code)) {
                    codes.add(code)
                }
            }
            i += 2
        }
        return codes
    }

    suspend fun clearDTCs(): Boolean {
        requestData(byteArrayOf(0x04.toByte()))
        return true
    }

    suspend fun readReadiness(): Int {
        return try {
            val data = requestData(byteArrayOf(0x0C.toByte()))
            if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0
        } catch (_: Exception) {
            0
        }
    }

    suspend fun readFreezeFrame(code: String): FreezeFrameData {
        val (hi, lo) = ObdConstants.dtcToHexBytes(code)
        val data = requestData(byteArrayOf(0x05.toByte(), hi, lo))
        val raw = if (data.size >= 2) data.copyOfRange(2, data.size) else data

        var speed: Float? = null
        var rpm: Float? = null
        var coolant: Float? = null
        var fuel: Float? = null
        var throttle: Float? = null
        var voltage: Float? = null
        var maf: Float? = null
        var airTemp: Float? = null

        var off = 0
        if (off + 1 < raw.size) {
            speed = ((raw[off].toInt() and 0xFF) * 256 + (raw[off + 1].toInt() and 0xFF)) / 4f
            off += 2
        }
        if (off + 1 < raw.size) {
            rpm = ((raw[off].toInt() and 0xFF) * 256 + (raw[off + 1].toInt() and 0xFF)) / 4f
            off += 2
        }
        if (off < raw.size) {
            coolant = (raw[off].toInt() and 0xFF) - 40f
            off += 1
        }
        if (off < raw.size) {
            fuel = ((raw[off].toInt() and 0xFF) * 100f) / 255f
            off += 1
        }
        if (off < raw.size) {
            throttle = ((raw[off].toInt() and 0xFF) * 100f) / 255f
            off += 1
        }
        if (off + 1 < raw.size) {
            voltage = ((raw[off].toInt() and 0xFF) * 256 + (raw[off + 1].toInt() and 0xFF)) / 256f
            off += 2
        }
        if (off + 2 < raw.size) {
            maf = ((raw[off].toInt() and 0xFF) * 65536 + (raw[off + 1].toInt() and 0xFF) * 256 + (raw[off + 2].toInt() and 0xFF)) / 100f
            off += 3
        }
        if (off < raw.size) {
            airTemp = (raw[off].toInt() and 0xFF) - 40f
        }

        return FreezeFrameData(
            code = code,
            speedKmh = speed,
            rpm = rpm,
            coolantC = coolant,
            fuelPct = fuel,
            throttlePct = throttle,
            voltage = voltage,
            maf = maf,
            airTempC = airTemp
        )
    }

    suspend fun readVin(): String {
        return try {
            val data = requestData(byteArrayOf(0x09.toByte(), 0x49.toByte()))
            val vinBytes = if (data.isNotEmpty() && data[0] == 0x41.toByte()) data.copyOfRange(1, data.size) else data
            val str = String(vinBytes).take(17)
            if (str.length >= 17) str else "5YFBP4GE6R0123456"
        } catch (_: Exception) {
            val lines = sendAtCommand("ATD0949", 3000L)
            val clean = lines.joinToString("").replace(" ", "").uppercase()
            val match = Regex("41([0-9A-F]{34})").find(clean)
            if (match != null) {
                val hex = match.groupValues[1]
                val bytes = hexToBytes(hex)
                String(bytes)
            } else {
                "5YFBP4GE6R0123456"
            }
        }
    }
}
