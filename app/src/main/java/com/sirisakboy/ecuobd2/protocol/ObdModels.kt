package com.sirisakboy.ecuobd2.protocol

data class EngineStatus(
    val raw: Int = 0,
    val mil: Boolean = false,
    val running: Boolean = false,
    val closedLoop: Boolean = false,
    val pfi: Boolean = false,
    val ac: Boolean = false,
    val warm: Boolean = false,
    val neutral: Boolean = false,
    val gearEngaged: Boolean = false
)

data class FreezeFrameData(
    val code: String = "",
    val rpm: Float? = null,
    val speedKmh: Float? = null,
    val coolantC: Float? = null,
    val fuelPct: Float? = null,
    val throttlePct: Float? = null,
    val voltage: Float? = null,
    val maf: Float? = null,
    val airTempC: Float? = null
)

object ObdConstants {
    val DTC_INFO = mapOf(
        "P0008" to "Crankshaft Position A — Camshaft Correlation (Bank 1)",
        "P0011" to "Intake Camshaft Over-Advanced (Bank 1 A)",
        "P0016" to "Crankshaft vs Camshaft Correlation (Bank 1 A)",
        "P0017" to "Crankshaft vs Camshaft Correlation (Bank 2 A)",
        "P0021" to "Exhaust Camshaft Over-Advanced (Bank 1 A)",
        "P0022" to "Exhaust Camshaft Over-Retarded (Bank 1 A)",
        "P0028" to "Intake Valve Control Circuit (Bank 1)",
        "P0030" to "O2 Sensor Heater Circuit (Bank 1, Sensor 1)",
        "P0031" to "O2 Sensor Heater Circuit High (Bank 1, Sensor 1)",
        "P0032" to "O2 Sensor Heater Circuit Low (Bank 1, Sensor 1)",
        "P0034" to "O2 Sensor No Activity Detected (Bank 1, Sensor 1)",
        "P0036" to "O2 Sensor Heater Circuit (Bank 1, Sensor 2)",
        "P003D" to "Mass Air Flow / Manifold Pressure Mismatch",
        "P0087" to "Fuel Rail/System Pressure Low (Bank 1)",
        "P0088" to "Fuel Rail/System Pressure High (Bank 1)",
        "P009C" to "Fuel Injector Group/Enable Circuit (Bank 1)",
        "P0101" to "Mass Air Flow / Manifold Pressure Range/Performance",
        "P0102" to "Mass Air Flow Circuit Low Input",
        "P0103" to "Mass Air Flow Circuit High Input",
        "P0106" to "Manifold Pressure Range/Performance",
        "P0107" to "Manifold Pressure Circuit Low Input",
        "P0108" to "Manifold Pressure Circuit High Input",
        "P0110" to "Intake Air Temp Circuit",
        "P0112" to "Intake Air Temp Circuit Low Input",
        "P0113" to "Intake Air Temp Circuit High Input",
        "P0117" to "Engine Coolant Temp Circuit",
        "P0118" to "Engine Coolant Temp Circuit Low Input",
        "P0119" to "Engine Coolant Temp Circuit Intermittent",
        "P0121" to "Throttle Position A Range/Performance",
        "P0122" to "Throttle Position A Low Input",
        "P0123" to "Throttle Position A High Input",
        "P0128" to "Coolant Temp Below Thermostat Regulating Temp",
        "P0130" to "O2 Sensor Circuit Malfunction (Bank 1, Sensor 1)",
        "P0131" to "O2 Sensor Circuit Low Voltage (Bank 1, Sensor 1)",
        "P0132" to "O2 Sensor Circuit High Voltage (Bank 1, Sensor 1)",
        "P0133" to "O2 Sensor Slow Response (Bank 1, Sensor 1)",
        "P0134" to "O2 Sensor No Activity (Bank 1, Sensor 1)",
        "P0135" to "O2 Sensor Heater Circuit (Bank 1, Sensor 1)",
        "P0136" to "O2 Sensor Circuit Malfunction (Bank 1, Sensor 2)",
        "P0137" to "O2 Sensor Circuit Low Voltage (Bank 1, Sensor 2)",
        "P0138" to "O2 Sensor Circuit High Voltage (Bank 1, Sensor 2)",
        "P0139" to "O2 Sensor Slow Response (Bank 1, Sensor 2)",
        "P0140" to "O2 Sensor No Activity (Bank 1, Sensor 2)",
        "P0141" to "O2 Sensor Slow Response After Fuel Trim",
        "P0142" to "O2 Sensor Circuit Stuck Rich (Bank 1)",
        "P0143" to "O2 Sensor Circuit Stuck Lean (Bank 1)",
        "P0165" to "Intake Air Pressure Sensor Circuit",
        "P0166" to "O2 Sensor Slow Transition Rich-to-Lean (Bank 1, Sensor 1)",
        "P0171" to "System Too Lean (Bank 1)",
        "P0172" to "System Too Rich (Bank 1)",
        "P0174" to "System Too Lean (Bank 2)",
        "P0175" to "System Too Rich (Bank 2)",
        "P0201" to "Fuel Injector A Circuit",
        "P0202" to "Fuel Injector B Circuit",
        "P0203" to "Fuel Injector C Circuit",
        "P0204" to "Fuel Injector D Circuit",
        "P0217" to "Engine Overheat Condition",
        "P0218" to "Transmission Fluid Overheat",
        "P0222" to "Accelerator Pedal A Low Input",
        "P0223" to "Accelerator Pedal A High Input",
        "P0224" to "Throttle Position A/B Correlation",
        "P0227" to "Transmission Range Sensor Circuit",
        "P0234" to "Turbo/Supercharger Overboost Condition",
        "P0299" to "Boost Pressure Low",
        "P0300" to "Random/Multiple Cylinder Misfire Detected",
        "P0301" to "Cylinder 1 Misfire Detected",
        "P0302" to "Cylinder 2 Misfire Detected",
        "P0303" to "Cylinder 3 Misfire Detected",
        "P0304" to "Cylinder 4 Misfire Detected",
        "P0320" to "Engine RPM Input Circuit",
        "P0325" to "Knock Sensor A Circuit",
        "P0326" to "Knock Sensor A Signal Performance",
        "P0335" to "Crankshaft Position A Circuit",
        "P0336" to "Crankshaft Position A Circuit Intermittent",
        "P0340" to "Camshaft Position A Circuit",
        "P0341" to "Camshaft Position A Circuit Range/Performance",
        "P0344" to "Camshaft Position A Circuit Intermittent",
        "P0385" to "Crankshaft Position A/B Correlation (Bank 1)",
        "P0420" to "Catalyst System Efficiency Below Threshold (Bank 1)",
        "P0421" to "Catalyst System Efficiency (Bank 1)",
        "P0422" to "O2 Sensor Slow Response (Bank 1, Sensor 2)",
        "P0430" to "Catalyst System Efficiency (Bank 2)",
        "P0440" to "EVAP Control System Malfunction",
        "P0441" to "EVAP Incorrect Purge Flow",
        "P0442" to "EVAP Control System Leak Detected (Small/Medium Leak)",
        "P0443" to "EVAP Purge Control Valve Circuit",
        "P0445" to "EVAP Purge Control Circuit Open",
        "P0446" to "EVAP Emissions Vent Control Circuit",
        "P0455" to "EVAP Large Leak Detected",
        "P0456" to "EVAP Very Small Leak Detected",
        "P0460" to "Fuel Level Sensor A Circuit",
        "P0462" to "Fuel Level Sensor A Circuit Low Input",
        "P0463" to "Fuel Level Sensor A Circuit High Input",
        "P0480" to "Radiator Fan 1 Control Circuit",
        "P0500" to "Vehicle Speed Sensor Range/Performance",
        "P0505" to "Idle Air Control System",
        "P0506" to "Idle Air Control — Engine RPM Below Stalled Speed",
        "P0507" to "Idle Air Control — Engine RPM Above Stalled Speed",
        "P0520" to "Manifold Absolute Pressure Circuit",
        "P0560" to "System Voltage Unstable",
        "P0562" to "System Voltage Low",
        "P0563" to "System Voltage High",
        "P0600" to "ECU Serial Communication Link",
        "P0601" to "ECU Internal Memory Checksum Error",
        "P0606" to "ECU / ECM Processor Fault",
        "P0700" to "Transmission Control System Malfunction"
    )

    val READINESS_MONITORS = listOf(
        "Misfire" to 0,
        "Fuel System" to 1,
        "Catalyst" to 2,
        "Components" to 3,
        "Heated Catalyst" to 4,
        "O2 Sensors" to 5,
        "CVT / Trans" to 6,
        "EVAP" to 7
    )

    val NRC = mapOf(
        0x00 to "service not supported",
        0x01 to "sub-function not supported / incorrect length",
        0x02 to "conditions not correct",
        0x03 to "request sequence error",
        0x04 to "not supported in active session",
        0x05 to "request out of range",
        0x06 to "security access denied",
        0x07 to "incorrect message length or format",
        0x08 to "receiver busy",
        0x09 to "sub-function not supported in session",
        0x0A to "request sequence error (invalid msg)",
        0x0B to "invalid key",
        0x0C to "exceeded limit",
        0x0D to "required time delay not expired",
        0x0E to "upload/download not accepted",
        0x31 to "service not supported (wrong bytes)",
        0x33 to "invalid CRC",
        0x34 to "incorrect data download condition",
        0x71 to "rejected due to excessive errors"
    )

    fun dtcToHexBytes(code: String): ByteArray {
        val clean = code.trim().uppercase()
        if (clean.length < 5) return byteArrayOf(0, 0)
        val type = clean[0]
        val prefix = when (type) {
            'P' -> 0x0
            'C' -> 0x1
            'B' -> 0x2
            'U' -> 0x3
            else -> 0x0
        }
        val digit1 = clean[1].digitToIntOrNull(16) ?: 0
        val digit2 = clean[2].digitToIntOrNull(16) ?: 0
        val digit3 = clean[3].digitToIntOrNull(16) ?: 0
        val digit4 = clean[4].digitToIntOrNull(16) ?: 0

        val hi = (prefix shl 6) or (digit1 shl 4) or digit2
        val lo = (digit3 shl 4) or digit4
        return byteArrayOf(hi.toByte(), lo.toByte())
    }

    fun hexToDtcCode(hi: Int, lo: Int): String? {
        val typeBits = (hi ushr 6) and 0x03
        val type = when (typeBits) {
            0x0 -> "P"
            0x1 -> "C"
            0x2 -> "B"
            0x3 -> "U"
            else -> "P"
        }
        val digit1 = (hi ushr 4) and 0x03
        val digit2 = hi and 0x0F
        val digit3 = (lo ushr 4) and 0x0F
        val digit4 = lo and 0x0F
        return "%s%d%X%X%X".format(type, digit1, digit2, digit3, digit4)
    }

    fun decodeDTCs(data: ByteArray): List<String> {
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val hi = data[i].toInt() and 0xFF
            val lo = data[i + 1].toInt() and 0xFF
            if (hi != 0 || lo != 0) {
                val code = hexToDtcCode(hi, lo)
                if (code != null) {
                    codes.add(code)
                }
            }
            i += 2
        }
        return codes
    }
}
