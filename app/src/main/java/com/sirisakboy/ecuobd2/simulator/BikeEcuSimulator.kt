package com.sirisakboy.ecuobd2.simulator

import com.sirisakboy.ecuobd2.protocol.HondaConstants
import com.sirisakboy.ecuobd2.protocol.ObdConstants
import com.sirisakboy.ecuobd2.transport.RawByteTransport
import com.sirisakboy.ecuobd2.transport.Transport
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

class SimulatedBikeCar {
    var ignition: Boolean = false
    var running: Boolean = false
    private var startTimer: Float = 0f
    var time: Float = 0f
    var throttleSet: Float = 0f
    private var throttleActual: Float = 0f

    var rpm: Float = 0f
    var speed: Float = 0f
    var coolant: Float = 25f
    var airTemp: Float = 22f
    var fuel: Float = 62f
    var voltage: Float = 12.4f
    var o2: Float = 0.5f
    private var o2Phase: Float = 0f
    var maf: Float = 0f
    var trimST: Float = 0f
    var trimLT: Float = 0f

    val dtcs = mutableMapOf<String, FreezeSnapshot>()

    data class FreezeSnapshot(
        val rpm: Float,
        val speed: Float,
        val coolant: Float,
        val fuel: Float,
        val throttle: Float,
        val voltage: Float,
        val maf: Float,
        val airTemp: Float
    )

    fun injectDtc(code: String) {
        val upper = code.trim().uppercase()
        if (!dtcs.containsKey(upper)) {
            dtcs[upper] = FreezeSnapshot(
                rpm = rpm,
                speed = speed,
                coolant = coolant,
                fuel = fuel,
                throttle = throttleSet,
                voltage = voltage,
                maf = maf,
                airTemp = airTemp
            )
        }
    }

    fun clearDtcs() {
        dtcs.clear()
    }

    fun tick(dt: Float) {
        time += dt
        if (ignition) {
            if (!running) {
                startTimer += dt
                if (startTimer > 0.8f) running = true
            }
        } else {
            running = false
            startTimer = 0f
        }

        if (running) {
            val targetRpm = 1300f + (throttleSet.toDouble().pow(1.2) * 12700f).toFloat()
            rpm += (targetRpm - rpm) * minOf(1f, dt * 2.2f) + ((Math.random().toFloat() - 0.5f) * 16f)
            rpm = rpm.coerceIn(1250f, 14800f)

            val targetSpeed = maxOf(0f, rpm - 1300f) * 0.019f * 10f
            speed += (targetSpeed - speed) * minOf(1f, dt * 0.9f)
            if (speed < 0.5f && throttleSet < 0.05f) speed = 0f

            val targetCoolant = 88f + (rpm / 14800f) * 8f
            coolant += (targetCoolant - coolant) * minOf(1f, dt / 100f)

            fuel = maxOf(3f, fuel - dt * (0.002f + (rpm / 14800f) * 0.02f))

            o2Phase += dt * (0.8f + throttleSet * 2.5f)
            o2 = if (coolant > 70f) 0.5f + 0.4f * sign(sin(o2Phase * Math.PI.toFloat())) else 0.45f

            maf = maxOf(0f, 2.5f + throttleSet * 34f + (rpm / 14800f) * 30f + (Math.random().toFloat() - 0.5f) * 0.4f)
            trimST = sin(time * 0.2f) * 1.2f + throttleSet * 2f
            trimLT = sin(time * 0.01f) * 0.8f
            voltage = 14.35f + sin(time * 0.3f) * 0.08f
            airTemp = 22f + throttleSet * 14f + sin(time * 0.1f) * 0.5f
            throttleActual = (throttleSet + (Math.random().toFloat() - 0.5f) * 0.004f).coerceIn(0f, 1f)
        } else {
            rpm = lerp(rpm, 0f, dt * 2.5f)
            speed = lerp(speed, 0f, dt * 0.8f)
            maf = lerp(maf, 0f, dt * 2f)
            trimST = lerp(trimST, 0f, dt)
            trimLT = lerp(trimLT, 0f, dt)
            o2 = 0.5f
            voltage = lerp(voltage, 12.4f, dt * 0.5f)
            throttleActual = 0f
            coolant += (25f - coolant) * minOf(1f, dt / 250f)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * minOf(1f, t)

    fun respond(payload: ByteArray, isCan: Boolean): List<ByteArray> {
        if (payload.isEmpty()) return listOf(byteArrayOf(0x7F, 0x00, 0x11))
        val mode = payload[0].toInt() and 0xFF

        return when (mode) {
            0x01 -> {
                val pids = payload.copyOfRange(1, payload.size)
                val out = mutableListOf<Byte>()
                out.add(0x41.toByte())
                for (p in pids) out.add(p)

                for (p in pids) {
                    val pid = p.toInt() and 0xFF
                    when (pid) {
                        0x01 -> {
                            var b = 0
                            if (dtcs.isNotEmpty()) b = b or 0x01
                            if (running) b = b or (0x02 or 0x08)
                            if (running && coolant > 70f) b = b or 0x04
                            if (running && coolant > 55f) b = b or 0x40
                            out.add(b.toByte())
                        }
                        0x02, 0x0D -> out.add(((coolant + 40f).roundToInt().coerceIn(0, 255)).toByte())
                        0x04 -> {
                            val v = ((trimST + 100f) * 1.28f).roundToInt().coerceIn(0, 65535)
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                        0x05 -> {
                            val v = ((trimLT + 100f) * 1.28f).roundToInt().coerceIn(0, 65535)
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                        0x07, 0x0C -> {
                            val v = (rpm * 4f).roundToInt().coerceIn(0, 65535)
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                        0x08 -> {
                            val v = (speed * 4f).roundToInt().coerceIn(0, 65535)
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                        0x0B -> {
                            val v = if (running) (30 + throttleActual * 80f).roundToInt() else 25
                            out.add(v.coerceIn(0, 255).toByte())
                        }
                        0x0E -> out.add(((throttleActual * 255f).roundToInt().coerceIn(0, 255)).toByte())
                        0x11, 0x13 -> {
                            val v = (o2 * 320f).roundToInt().coerceIn(0, 65535)
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                        0x24 -> out.add(((fuel * 2.55f).roundToInt().coerceIn(0, 255)).toByte())
                        0x25 -> out.add(((airTemp + 40f).roundToInt().coerceIn(0, 255)).toByte())
                        0x2F -> {
                            val v = (voltage * 256f).roundToInt().coerceIn(0, 65535)
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                        0x42 -> {
                            val v = (maf * 100f).roundToInt().coerceIn(0, 16777215)
                            out.add(((v ushr 16) and 0xFF).toByte())
                            out.add(((v ushr 8) and 0xFF).toByte())
                            out.add((v and 0xFF).toByte())
                        }
                    }
                }
                listOf(out.toByteArray())
            }
            0x03 -> {
                val out = mutableListOf<Byte>()
                out.add(0x43.toByte())
                if (dtcs.isEmpty()) {
                    out.add(0x00.toByte())
                } else {
                    for (code in dtcs.keys) {
                        val bytes = ObdConstants.dtcToHexBytes(code)
                        out.add(bytes[0])
                        out.add(bytes[1])
                    }
                }
                listOf(out.toByteArray())
            }
            0x04 -> {
                dtcs.clear()
                listOf(byteArrayOf(0x44.toByte()))
            }
            0x05 -> {
                val out = mutableListOf<Byte>()
                out.add(0x45.toByte())
                val code = if (payload.size >= 3) ObdConstants.hexToDtcCode(payload[1].toInt(), payload[2].toInt()) ?: "P0301" else "P0301"
                val snap = dtcs[code] ?: FreezeSnapshot(rpm, speed, coolant, fuel, throttleSet, voltage, maf, airTemp)
                if (payload.size >= 3) {
                    out.add(payload[1])
                    out.add(payload[2])
                }
                val spdV = (snap.speed * 4f).roundToInt()
                out.add(((spdV ushr 8) and 0xFF).toByte()); out.add((spdV and 0xFF).toByte())
                val rpmV = (snap.rpm * 4f).roundToInt()
                out.add(((rpmV ushr 8) and 0xFF).toByte()); out.add((rpmV and 0xFF).toByte())
                out.add(((snap.coolant + 40f).roundToInt().coerceIn(0, 255)).toByte())
                out.add(((snap.fuel * 2.55f).roundToInt().coerceIn(0, 255)).toByte())
                out.add(((snap.throttle * 255f).roundToInt().coerceIn(0, 255)).toByte())
                val voltV = (snap.voltage * 256f).roundToInt()
                out.add(((voltV ushr 8) and 0xFF).toByte()); out.add((voltV and 0xFF).toByte())
                val mafV = (snap.maf * 100f).roundToInt()
                out.add(((mafV ushr 16) and 0xFF).toByte()); out.add(((mafV ushr 8) and 0xFF).toByte()); out.add((mafV and 0xFF).toByte())
                out.add(((snap.airTemp + 40f).roundToInt().coerceIn(0, 255)).toByte())
                listOf(out.toByteArray())
            }
            0x09 -> {
                if (payload.size >= 2 && (payload[1].toInt() and 0xFF) == 0x49) {
                    val vinBytes = "5YFBP4GE6R0123456".toByteArray()
                    val out = ByteArray(2 + vinBytes.size)
                    out[0] = 0x49.toByte()
                    out[1] = 0x41.toByte()
                    System.arraycopy(vinBytes, 0, out, 2, vinBytes.size)
                    listOf(out)
                } else {
                    listOf(byteArrayOf(0x7F, 0x09, 0x31))
                }
            }
            0x0C -> {
                val hasMisfire = dtcs.keys.any { it.startsWith("P03") }
                listOf(byteArrayOf(0x4C.toByte(), if (hasMisfire) 0x01.toByte() else 0x00.toByte()))
            }
            else -> listOf(byteArrayOf(0x7F, mode.toByte(), 0x11))
        }
    }
}

class ObdSimulatorTransport(
    val car: SimulatedBikeCar,
    private val scope: CoroutineScope
) : Transport {
    override val name = "OBD Simulator"
    private var dataListener: ((String) -> Unit)? = null
    private var closeListener: (() -> Unit)? = null
    private var isClosed = false
    private var echo = false
    private var headers = true
    private var protocol = 4 // CAN 413
    private var tickJob: Job? = null

    override suspend fun init() {
        isClosed = false
        tickJob?.cancel()
        tickJob = scope.launch(Dispatchers.Default) {
            while (isActive && !isClosed) {
                car.tick(0.05f)
                delay(50)
            }
        }
    }

    override fun setOnDataListener(listener: (String) -> Unit) {
        dataListener = listener
    }

    override fun setOnCloseListener(listener: () -> Unit) {
        closeListener = listener
    }

    private fun sendLines(lines: List<String>) {
        scope.launch {
            delay(15 + (Math.random() * 20).toLong())
            if (!isClosed) {
                lines.forEach { dataListener?.invoke(it) }
            }
        }
    }

    override suspend fun sendLine(line: String) {
        if (isClosed) return
        val cmd = line.trim()
        val upper = cmd.uppercase()

        when {
            upper == "ATZ" -> {
                echo = false
                headers = true
                sendLines(listOf("ELM327 v2.3 (ecu-obd2 simulator)"))
            }
            upper.matches(Regex("^ATL[01]$")) -> {
                echo = upper == "ATL1"
                sendLines(if (echo) listOf(upper, "READY") else listOf("READY"))
            }
            upper.matches(Regex("^ATE[01]$")) || upper.matches(Regex("^ATSH[01]$")) -> {
                sendLines(if (echo) listOf(upper, "READY") else listOf("READY"))
            }
            upper.matches(Regex("^ATS[01]$")) -> {
                headers = upper == "ATS1"
                sendLines(if (echo) listOf(upper, "READY") else listOf("READY"))
            }
            upper in listOf("ATSP0", "ATSP1", "ATSP2", "ATSP3", "ATSP4") -> {
                protocol = when (upper) {
                    "ATSP0" -> 3
                    "ATSP1" -> 1
                    "ATSP2" -> 2
                    "ATSP3" -> 4
                    "ATSP4" -> 5
                    else -> 4
                }
                sendLines(if (echo) listOf(upper, "READY") else listOf("READY"))
            }
            upper == "ATDP" -> {
                sendLines(if (echo) listOf(upper, "OBDII PROTOCOL 0X$protocol", "READY") else listOf("OBDII PROTOCOL 0X$protocol", "READY"))
            }
            upper.startsWith("ATD") -> {
                val hex = upper.substring(3)
                val payload = hexToBytes(hex)
                handlePayload(payload, raw = true, cmd = if (echo) cmd else null)
            }
            upper.matches(Regex("^[0-9A-F]{14,24}$")) -> {
                val hex = upper.substring(4)
                val payload = hexToBytes(hex)
                handlePayload(payload, raw = false, cmd = if (echo) cmd else null)
            }
            else -> {
                sendLines(if (echo) listOf(cmd, "ERROR") else listOf("ERROR"))
            }
        }
    }

    private fun handlePayload(payload: ByteArray, raw: Boolean, cmd: String?) {
        val isCan = protocol == 4 || protocol == 5
        val frames = car.respond(payload, isCan)
        val out = mutableListOf<String>()
        if (cmd != null) out.add(cmd)

        for (f in frames) {
            val hex = f.joinToString("") { "%02X".format(it) }
            if (raw) {
                out.add(hex)
            } else {
                val padded = if (isCan) (hex + "0000000000000000").take(16) else (hex + "0000000000").take(12)
                out.add((if (isCan) "7D81" else "7E81") + padded)
            }
        }
        out.add("READY")
        sendLines(out)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").uppercase()
        val len = clean.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    override suspend fun close() {
        isClosed = true
        tickJob?.cancel()
        closeListener?.invoke()
    }
}

class HondaKlineSimulatorTransport(
    private val scope: CoroutineScope
) : RawByteTransport {
    override val name = "Honda K-line Simulator"
    var ignition: Boolean = true
    val currentFaults = mutableListOf<String>()
    val pastFaults = mutableListOf("01-02")
    val rom: ByteArray = buildRom()
    private val queue = ConcurrentLinkedQueue<Byte>()
    private var closeListener: (() -> Unit)? = null
    private var isClosed = false
    private val buffer = mutableListOf<Byte>()

    companion object {
        fun buildRom(size: Int = 131072): ByteArray {
            val rom = ByteArray(size)
            for (i in 0 until size) {
                rom[i] = (((i * 7) + ((i ushr 8) and 0xFF) * 3) and 0xFF).toByte()
            }
            val header = "HONDA-ROM-SIM-ECU-01\u0000\u0000".toByteArray()
            System.arraycopy(header, 0, rom, 0, minOf(header.size, size))
            for (i in 0 until 32) {
                if (0x100 + i < size) {
                    rom[0x100 + i] = ((i * 13) and 0xFF).toByte()
                }
            }
            return rom
        }
    }

    override suspend fun init() {
        isClosed = false
        queue.clear()
        buffer.clear()
    }

    override fun drain() {
        queue.clear()
    }

    override fun setOnCloseListener(listener: () -> Unit) {
        closeListener = listener
    }

    override suspend fun write(bytes: ByteArray) {
        if (isClosed) return
        for (b in bytes) buffer.add(b)
        parseStream()
    }

    private fun parseStream() {
        while (buffer.size >= 2) {
            val b0 = buffer[0].toInt() and 0xFF
            val ml = if (b0 == 0x82) 3 else 1
            if (buffer.size < ml + 1) return
            val msgsize = buffer[ml].toInt() and 0xFF
            if (msgsize < ml + 2 || msgsize > 32) {
                buffer.clear()
                return
            }
            if (buffer.size < msgsize) return
            val msg = ByteArray(msgsize)
            for (i in 0 until msgsize) {
                msg[i] = buffer.removeAt(0)
            }
            val calcChecksum = HondaConstants.checksum8bitHonda(msg.copyOfRange(0, msgsize - 1))
            if (calcChecksum == msg[msgsize - 1]) {
                handleRequest(msg, ml)
            }
        }
    }

    private fun respMtype(reqMtype: ByteArray): ByteArray {
        return when (reqMtype.size) {
            3 -> byteArrayOf(((reqMtype[0].toInt() and 0xFF) or 0x10).toByte(), ((reqMtype[1].toInt() and 0xFF) or 0x10).toByte(), reqMtype[2])
            2 -> reqMtype.clone()
            else -> byteArrayOf(((reqMtype[0].toInt() and 0xFF) and 0x0F).toByte())
        }
    }

    private fun formatResp(reqMtype: ByteArray, data: ByteArray): ByteArray {
        val rm = respMtype(reqMtype)
        val ml = rm.size
        val dl = data.size
        val size = 0x02 + ml + dl
        val msg = ByteArray(size)
        System.arraycopy(rm, 0, msg, 0, ml)
        msg[ml] = size.toByte()
        if (dl > 0) System.arraycopy(data, 0, msg, ml + 1, dl)
        msg[size - 1] = HondaConstants.checksum8bitHonda(msg.copyOfRange(0, size - 1))
        return msg
    }

    private fun handleRequest(msg: ByteArray, ml: Int) {
        if (!ignition) return
        val mtype = msg.copyOfRange(0, ml)
        val data = msg.copyOfRange(ml + 1, msg.size - 1)
        val f0 = mtype[0].toInt() and 0xFF

        var response: ByteArray? = null
        when (f0) {
            0xFE -> response = formatResp(mtype, byteArrayOf(0x72))
            0x72 -> {
                if (data.isNotEmpty()) {
                    val sub = data[0].toInt() and 0xFF
                    when (sub) {
                        0x00 -> response = formatResp(mtype, byteArrayOf(0x00, 0xF0.toByte()))
                        0x71 -> {
                            val t = if (data.size > 1) data[1].toInt() and 0xFF else 0
                            response = if (t == 0x00) {
                                formatResp(mtype, byteArrayOf(0x01, 0x00, 0x00, 0x08, 0x00, 0x12, 0x34, 0x56))
                            } else {
                                formatResp(mtype, byteArrayOf(0x00, 0x04, 0x00, 0x10, 0x00))
                            }
                        }
                        0x74 -> response = formatResp(mtype, buildFaultGroup(currentFaults))
                        0x73 -> response = formatResp(mtype, buildFaultGroup(pastFaults))
                    }
                }
            }
            0x7D -> response = formatResp(mtype, byteArrayOf(0x00))
            0x7B -> response = formatResp(mtype, byteArrayOf(0x00))
            0x7E -> {
                if (data.size >= 2) {
                    val s1 = data[0].toInt() and 0xFF
                    val s2 = data[1].toInt() and 0xFF
                    when {
                        s1 == 0x01 && s2 == 0x00 -> response = formatResp(mtype, byteArrayOf(0x00, 0x01))
                        s1 == 0x01 && s2 == 0x05 -> response = formatResp(mtype, byteArrayOf(0x01, 0x00))
                        s1 == 0x01 && s2 == 0x0D -> response = formatResp(mtype, byteArrayOf(0x01, 0x0F))
                        s1 == 0x01 && s2 == 0x06 && data.size >= 5 -> {
                            val addr = HondaConstants.addressFromBytes(data[2].toInt(), data[3].toInt(), data[4].toInt())
                            val end = minOf(rom.size, addr + 24)
                            if (addr < rom.size) {
                                val chunk = rom.copyOfRange(addr, end)
                                response = formatResp(mtype, chunk)
                            } else {
                                response = formatResp(mtype, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
                            }
                        }
                        s1 == 0x01 && s2 == 0x0B && data.size >= 6 -> {
                            val addr = HondaConstants.addressFromBytes(data[2].toInt(), data[3].toInt(), data[4].toInt())
                            val chunk = data.copyOfRange(5, data.size)
                            for (i in chunk.indices) {
                                if (addr + i < rom.size) rom[addr + i] = chunk[i]
                            }
                            response = formatResp(mtype, byteArrayOf(0x00))
                        }
                        else -> response = formatResp(mtype, byteArrayOf(0x00))
                    }
                }
            }
            0x82 -> response = formatResp(mtype, byteArrayOf(0x08))
        }

        val pushBytes = if (response != null) {
            val total = ByteArray(msg.size + response.size)
            System.arraycopy(msg, 0, total, 0, msg.size)
            System.arraycopy(response, 0, total, msg.size, response.size)
            total
        } else {
            msg
        }

        scope.launch {
            delay(5 + (Math.random() * 10).toLong())
            for (b in pushBytes) queue.add(b)
        }
    }

    private fun buildFaultGroup(faults: List<String>): ByteArray {
        val data = ByteArray(9)
        data[2] = faults.size.toByte()
        for (i in 0 until minOf(3, faults.size)) {
            val parts = faults[i].split("-")
            if (parts.size == 2) {
                data[3 + i * 2] = (parts[0].toIntOrNull(16) ?: 0).toByte()
                data[4 + i * 2] = (parts[1].toIntOrNull(16) ?: 0).toByte()
            }
        }
        return data
    }

    override suspend fun readUntil(n: Int, timeoutMs: Long): ByteArray {
        val out = mutableListOf<Byte>()
        val start = System.currentTimeMillis()
        while (out.size < n && System.currentTimeMillis() - start < timeoutMs) {
            val b = queue.poll()
            if (b != null) {
                out.add(b)
            } else {
                delay(5)
            }
        }
        return out.toByteArray()
    }

    override suspend fun close() {
        isClosed = true
        closeListener?.invoke()
    }
}
