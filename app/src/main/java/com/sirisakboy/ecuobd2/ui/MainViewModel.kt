package com.sirisakboy.ecuobd2.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sirisakboy.ecuobd2.protocol.*
import com.sirisakboy.ecuobd2.simulator.HondaKlineSimulatorTransport
import com.sirisakboy.ecuobd2.simulator.ObdSimulatorTransport
import com.sirisakboy.ecuobd2.simulator.SimulatedBikeCar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.CRC32

data class LogEntry(
    val time: String,
    val message: String,
    val dir: String, // send, recv, info
    val kind: String? = null // ok, warn, error
)

data class ObdLiveState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val device: String = "sim", // sim, bluetooth, serial
    val protocol: String = "AUTO",
    val detectedProtocol: String? = null,
    val unit: String = "metric", // metric, imperial
    val pollIntervalMs: Long = 250L,
    val vin: String = "",
    val engineRunning: Boolean = false,
    val closedLoop: Boolean = false,
    val mil: Boolean = false,
    // Gauges
    val rpm: Float = 0f,
    val speedKmh: Float = 0f,
    val coolantC: Float = 25f,
    val fuelPct: Float = 62f,
    // Sensor Tiles
    val throttlePct: Float = 0f,
    val airTempC: Float = 22f,
    val maf: Float = 0f,
    val voltage: Float = 12.4f,
    val mapKpa: Float = 25f,
    val o2Volts: Float = 0.5f,
    val trimStPct: Float = 0f,
    val trimLtPct: Float = 0f,
    // DTCs
    val dtcs: List<String> = emptyList(),
    val readinessMask: Int = 0,
    val freezeFrame: FreezeFrameData? = null
)

data class HondaKlineState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val device: String = "sim",
    val ecuState: EcuState = EcuState.UNDEFINED,
    val currentFaults: List<String> = emptyList(),
    val pastFaults: List<String> = emptyList(),
    val probedTables: Map<Int, ByteArray> = emptyMap(),
    val romSize: Int = 131072, // 128KB default
    val romProgress: Float? = null,
    val romProgressText: String = "",
    val loadedRomBytes: ByteArray? = null,
    val loadedRomCrc: String = "",
    val romInfoText: String = "",
    val dangerActionRunning: Boolean = false
)

data class SimulatorControlsState(
    val ignition: Boolean = true,
    val throttle: Float = 0f, // 0.0 .. 1.0
    val selectedDtcToInject: String = "P0301",
    val selectedHondaFaultToInject: String = "19-01"
)

class MainViewModel : ViewModel() {
    private val _obdState = MutableStateFlow(ObdLiveState())
    val obdState: StateFlow<ObdLiveState> = _obdState.asStateFlow()

    private val _hondaState = MutableStateFlow(HondaKlineState())
    val hondaState: StateFlow<HondaKlineState> = _hondaState.asStateFlow()

    private val _simControls = MutableStateFlow(SimulatorControlsState())
    val simControls: StateFlow<SimulatorControlsState> = _simControls.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Shared simulated car instance for synchronized cockpit controls
    val simulatedCar = SimulatedBikeCar()
    private var obdTransport: ObdSimulatorTransport? = null
    private var obdEngine: Elm327Engine? = null
    private var obdPollJob: Job? = null

    private var hondaTransport: HondaKlineSimulatorTransport? = null
    private var hondaEngine: HondaKlineEngine? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        log("ECU OBD2 ready. Select a device and tap Connect.", "info")
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    fun log(msg: String, dir: String = "info", kind: String? = null) {
        val entry = LogEntry(timeFormat.format(Date()), msg, dir, kind)
        _logs.update { list ->
            val updated = list + entry
            if (updated.size > 300) updated.takeLast(300) else updated
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ------------------------------------------------------------- OBD ACTIONS

    fun setObdDevice(dev: String) {
        _obdState.update { it.copy(device = dev) }
    }

    fun setObdProtocol(proto: String) {
        _obdState.update { it.copy(protocol = proto) }
    }

    fun setUnit(unit: String) {
        _obdState.update { it.copy(unit = unit) }
    }

    fun setPollInterval(ms: Long) {
        _obdState.update { it.copy(pollIntervalMs = ms) }
        if (_obdState.value.isConnected) {
            startObdPolling()
        }
    }

    fun toggleObdConnect() {
        if (_obdState.value.isConnected) {
            disconnectObd()
        } else {
            connectObd()
        }
    }

    private fun connectObd() {
        if (_obdState.value.isConnecting) return
        _obdState.update { it.copy(isConnecting = true) }

        viewModelScope.launch {
            try {
                log("Connecting OBD-II (Device: ${_obdState.value.device})...", "info")
                val transport = ObdSimulatorTransport(simulatedCar, viewModelScope)
                transport.init()
                obdTransport = transport

                val engine = Elm327Engine(transport) { msg, dir, kind ->
                    log(msg, dir, kind)
                }
                obdEngine = engine

                val fw = engine.reset()
                log("Adapter firmware: $fw", "info")
                engine.setup()

                val selProto = _obdState.value.protocol
                if (selProto == "AUTO") {
                    val det = engine.detectProtocol()
                    log("Auto-detected protocol: $det", "info", "ok")
                    _obdState.update { it.copy(detectedProtocol = det) }
                } else {
                    engine.setProtocol(selProto)
                    _obdState.update { it.copy(detectedProtocol = selProto) }
                }

                // Read VIN
                val vin = try { engine.readVin() } catch (_: Exception) { "" }
                log("VIN: ${vin.ifEmpty { "Unavailable" }}", "info")

                // Initial DTCs
                val initialDtcs = try { engine.readDTCs() } catch (_: Exception) { emptyList() }
                val readiness = try { engine.readReadiness() } catch (_: Exception) { 0 }

                _obdState.update {
                    it.copy(
                        isConnected = true,
                        isConnecting = false,
                        vin = vin,
                        dtcs = initialDtcs,
                        readinessMask = readiness,
                        mil = initialDtcs.isNotEmpty()
                    )
                }

                showToast("Connected to OBD-II ($fw)")
                startObdPolling()
            } catch (e: Exception) {
                log("OBD Connect failed: ${e.message}", "info", "error")
                showToast("Connect failed: ${e.message}")
                disconnectObd()
            }
        }
    }

    private fun startObdPolling() {
        obdPollJob?.cancel()
        val pids = listOf(0x01, 0x0C, 0x0D, 0x0E, 0x08, 0x24, 0x25, 0x2F, 0x0B, 0x11, 0x04, 0x05, 0x42)
        obdPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _obdState.value.isConnected) {
                try {
                    val engine = obdEngine ?: break
                    val res = engine.readPids(pids)

                    val status = res[0x01] as? EngineStatus
                    val rpm = (res[0x0C] as? Float) ?: _obdState.value.rpm
                    val coolant = (res[0x0D] as? Float) ?: _obdState.value.coolantC
                    val throttle = (res[0x0E] as? Float) ?: _obdState.value.throttlePct
                    val speed = (res[0x08] as? Float) ?: _obdState.value.speedKmh
                    val fuel = (res[0x24] as? Float) ?: _obdState.value.fuelPct
                    val airTemp = (res[0x25] as? Float) ?: _obdState.value.airTempC
                    val volt = (res[0x2F] as? Float) ?: _obdState.value.voltage
                    val map = (res[0x0B] as? Float) ?: _obdState.value.mapKpa
                    val o2 = (res[0x11] as? Float) ?: _obdState.value.o2Volts
                    val stft = (res[0x04] as? Float) ?: _obdState.value.trimStPct
                    val ltft = (res[0x05] as? Float) ?: _obdState.value.trimLtPct
                    val maf = (res[0x42] as? Float) ?: _obdState.value.maf

                    _obdState.update { s ->
                        s.copy(
                            rpm = rpm,
                            speedKmh = speed,
                            coolantC = coolant,
                            fuelPct = fuel,
                            throttlePct = throttle,
                            airTempC = airTemp,
                            voltage = volt,
                            mapKpa = map,
                            o2Volts = o2,
                            trimStPct = stft,
                            trimLtPct = ltft,
                            maf = maf,
                            engineRunning = status?.running ?: (rpm > 500f),
                            closedLoop = status?.closedLoop ?: false,
                            mil = status?.mil ?: (s.dtcs.isNotEmpty())
                        )
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("closed", ignoreCase = true) == true) {
                        disconnectObd()
                        break
                    }
                }
                delay(_obdState.value.pollIntervalMs)
            }
        }
    }

    fun disconnectObd() {
        obdPollJob?.cancel()
        obdPollJob = null
        viewModelScope.launch {
            obdTransport?.close()
            obdTransport = null
            obdEngine = null
            _obdState.update {
                it.copy(
                    isConnected = false,
                    isConnecting = false,
                    rpm = 0f,
                    speedKmh = 0f,
                    engineRunning = false
                )
            }
            log("OBD-II disconnected.", "info")
        }
    }

    fun readObdDtcs() {
        viewModelScope.launch {
            try {
                log("Reading stored DTCs (Mode 03)...", "info")
                val codes = obdEngine?.readDTCs() ?: emptyList()
                val readiness = obdEngine?.readReadiness() ?: 0
                _obdState.update { it.copy(dtcs = codes, readinessMask = readiness, mil = codes.isNotEmpty()) }
                log("DTCs found: ${codes.ifEmpty { listOf("None") }.joinToString(", ")}", "info", if (codes.isEmpty()) "ok" else "warn")
                showToast(if (codes.isEmpty()) "No DTC faults detected" else "Found ${codes.size} DTC code(s)")
            } catch (e: Exception) {
                log("DTC read failed: ${e.message}", "info", "error")
                showToast("Read DTCs failed: ${e.message}")
            }
        }
    }

    fun clearObdDtcs() {
        viewModelScope.launch {
            try {
                log("Clearing DTCs & resetting MIL (Mode 04)...", "warn")
                obdEngine?.clearDTCs()
                _obdState.update { it.copy(dtcs = emptyList(), mil = false, freezeFrame = null) }
                log("DTCs cleared successfully.", "info", "ok")
                showToast("DTCs cleared via Mode 04")
            } catch (e: Exception) {
                log("Clear DTCs failed: ${e.message}", "info", "error")
                showToast("Clear DTCs failed: ${e.message}")
            }
        }
    }

    fun inspectFreezeFrame(code: String) {
        viewModelScope.launch {
            try {
                log("Reading Freeze Frame for $code (Mode 05)...", "info")
                val ff = obdEngine?.readFreezeFrame(code) ?: FreezeFrameData(code = code)
                _obdState.update { it.copy(freezeFrame = ff) }
            } catch (e: Exception) {
                log("Freeze frame error: ${e.message}", "info", "warn")
                _obdState.update { it.copy(freezeFrame = FreezeFrameData(code = code)) }
            }
        }
    }

    fun closeFreezeFrame() {
        _obdState.update { it.copy(freezeFrame = null) }
    }

    // ----------------------------------------------------------- HONDA K-LINE

    fun setHondaDevice(dev: String) {
        _hondaState.update { it.copy(device = dev) }
    }

    fun setRomSize(size: Int) {
        _hondaState.update { it.copy(romSize = size) }
    }

    fun toggleHondaConnect() {
        if (_hondaState.value.isConnected) {
            disconnectHonda()
        } else {
            connectHonda()
        }
    }

    private fun connectHonda() {
        if (_hondaState.value.isConnecting) return
        _hondaState.update { it.copy(isConnecting = true) }

        viewModelScope.launch {
            try {
                log("[K-line] Connecting to Honda ECU @ 10.4 kbps...", "info")
                val transport = HondaKlineSimulatorTransport(viewModelScope)
                transport.init()
                hondaTransport = transport

                val engine = HondaKlineEngine(transport) { msg, dir ->
                    log("[K-line] $msg", dir)
                }
                hondaEngine = engine

                val state = engine.detectEcuState()
                log("[K-line] Detected ECU State: ${state.displayName}", "info", if (state == EcuState.OK) "ok" else "warn")

                _hondaState.update {
                    it.copy(
                        isConnected = true,
                        isConnecting = false,
                        ecuState = state
                    )
                }
                showToast("Honda K-line Connected (ECU State: ${state.name})")
            } catch (e: Exception) {
                log("[K-line] Connect failed: ${e.message}", "info", "error")
                showToast("K-line connect failed: ${e.message}")
                disconnectHonda()
            }
        }
    }

    fun disconnectHonda() {
        viewModelScope.launch {
            hondaTransport?.close()
            hondaTransport = null
            hondaEngine = null
            _hondaState.update {
                it.copy(
                    isConnected = false,
                    isConnecting = false,
                    ecuState = EcuState.UNDEFINED,
                    currentFaults = emptyList(),
                    pastFaults = emptyList(),
                    probedTables = emptyMap(),
                    romProgress = null
                )
            }
            log("[K-line] Disconnected.", "info")
        }
    }

    fun pingHonda() {
        viewModelScope.launch {
            log("[K-line] Ping (FE 72)...", "info")
            val ok = hondaEngine?.ping() ?: false
            log("[K-line] Ping result: ${if (ok) "OK (Response received)" else "No response"}", "info", if (ok) "ok" else "error")
            showToast(if (ok) "Ping OK — ECU responded" else "Ping failed — No answer")
        }
    }

    fun detectHondaState() {
        viewModelScope.launch {
            log("[K-line] Detecting ECU State...", "info")
            val s = hondaEngine?.detectEcuState() ?: EcuState.OFF
            _hondaState.update { it.copy(ecuState = s) }
            log("[K-line] State: ${s.displayName}", "info", if (s == EcuState.OK) "ok" else "warn")
            showToast("ECU State: ${s.displayName}")
        }
    }

    fun readHondaFaults() {
        viewModelScope.launch {
            log("[K-line] Reading faults (0x74 current / 0x73 past)...", "info")
            val faults = hondaEngine?.getFaults() ?: HondaFaults()
            _hondaState.update { it.copy(currentFaults = faults.current, pastFaults = faults.past) }
            log("[K-line] Faults: ${faults.current.size} current, ${faults.past.size} past", "info", if (faults.current.isEmpty()) "ok" else "warn")
            showToast("Faults: ${faults.current.size} Current, ${faults.past.size} Past")
        }
    }

    fun probeHondaTables() {
        viewModelScope.launch {
            log("[K-line] Probing info tables (0x71)...", "info")
            val tables = hondaEngine?.probeTables() ?: emptyMap()
            _hondaState.update { it.copy(probedTables = tables) }
            log("[K-line] Probed ${tables.size} tables successfully.", "info", "ok")
            showToast("Found ${tables.size} info tables")
        }
    }

    // ROM Read / Write
    fun readRomFromEcu() {
        val size = _hondaState.value.romSize
        viewModelScope.launch {
            try {
                log("[K-line] ROM read started ($size bytes) @ 0x000000...", "warn")
                _hondaState.update { it.copy(romProgress = 0f, romProgressText = "Reading 0%") }

                val bytes = hondaEngine?.readRom(0, size) { done, total ->
                    val pct = done.toFloat() / total.toFloat()
                    _hondaState.update {
                        it.copy(
                            romProgress = pct,
                            romProgressText = "Reading ${(pct * 100).toInt()}% (${done / 1024} KB / ${total / 1024} KB)"
                        )
                    }
                } ?: throw Exception("Engine not connected")

                val crc = CRC32().apply { update(bytes) }.value
                val crcHex = "%08X".format(crc)
                val info = "Read ${size / 1024} KB from ECU — CRC32: $crcHex"

                _hondaState.update {
                    it.copy(
                        romProgress = 1f,
                        romProgressText = "Complete ✓",
                        loadedRomBytes = bytes,
                        loadedRomCrc = crcHex,
                        romInfoText = info
                    )
                }
                log("[K-line] ROM read complete: CRC32 $crcHex", "info", "ok")
                showToast("ROM read successful: CRC32 $crcHex")
            } catch (e: Exception) {
                log("[K-line] ROM read failed: ${e.message}", "info", "error")
                showToast("ROM read failed: ${e.message}")
            } finally {
                delay(2000)
                _hondaState.update { it.copy(romProgress = null) }
            }
        }
    }

    fun loadSampleRom() {
        val sample = HondaKlineSimulatorTransport.buildRom(_hondaState.value.romSize)
        val crc = CRC32().apply { update(sample) }.value
        val crcHex = "%08X".format(crc)
        val info = "Loaded sample .bin image (${sample.size / 1024} KB) — CRC32: $crcHex (Ready to write)"
        _hondaState.update {
            it.copy(
                loadedRomBytes = sample,
                loadedRomCrc = crcHex,
                romInfoText = info
            )
        }
        log("[K-line] Loaded sample ROM image ($crcHex)", "info", "ok")
        showToast("Loaded sample ROM image (${sample.size / 1024} KB)")
    }

    fun writeRomToEcu() {
        val bytes = _hondaState.value.loadedRomBytes ?: run {
            showToast("No .bin file loaded to write")
            return
        }

        viewModelScope.launch {
            try {
                log("[K-line] Initializing write mode (7D sequence)...", "warn")
                hondaEngine?.initWrite()

                log("[K-line] ROM write started (${bytes.size} bytes)...", "warn")
                _hondaState.update { it.copy(romProgress = 0f, romProgressText = "Writing 0%") }

                hondaEngine?.writeRom(0, bytes) { done, total ->
                    val pct = done.toFloat() / total.toFloat()
                    _hondaState.update {
                        it.copy(
                            romProgress = pct,
                            romProgressText = "Writing ${(pct * 100).toInt()}%"
                        )
                    }
                }

                _hondaState.update { it.copy(romProgress = 0.9f, romProgressText = "Verifying readback...") }
                log("[K-line] Verifying ROM readback...", "warn")
                hondaEngine?.verifyRom(0, bytes)

                _hondaState.update {
                    it.copy(
                        romProgress = 1f,
                        romProgressText = "Verified OK ✓",
                        romInfoText = "Written ${bytes.size / 1024} KB and verified OK! Ready for Post-write."
                    )
                }
                log("[K-line] ROM written and verified OK!", "info", "ok")
                showToast("ROM write and verification complete!")
            } catch (e: Exception) {
                log("[K-line] ROM write failed: ${e.message}", "info", "error")
                showToast("ROM write failed: ${e.message}")
            } finally {
                delay(2500)
                _hondaState.update { it.copy(romProgress = null) }
            }
        }
    }

    // Flash Danger Zone
    fun executeDangerAction(action: String) {
        viewModelScope.launch {
            _hondaState.update { it.copy(dangerActionRunning = true) }
            try {
                when (action) {
                    "recover" -> {
                        log("[K-line] Executing Init Recovery (7B sequence)...", "warn")
                        hondaEngine?.initRecover()
                        log("[K-line] Init Recovery sent.", "info", "ok")
                    }
                    "write" -> {
                        log("[K-line] Executing Init Write (7D sequence)...", "warn")
                        hondaEngine?.initWrite()
                        log("[K-line] Init Write sent.", "info", "ok")
                    }
                    "erase" -> {
                        log("[K-line] Executing Erase Sequence (7E)...", "warn")
                        hondaEngine?.erase()
                        log("[K-line] Waiting for erase completion...", "warn")
                        hondaEngine?.eraseWait()
                        log("[K-line] Erase reported done.", "info", "ok")
                    }
                    "post" -> {
                        log("[K-line] Executing Post-write Finalization (7E)...", "warn")
                        val ok = hondaEngine?.postWrite() ?: false
                        log("[K-line] Post-write confirm: ${if (ok) "Confirmed (0x0F)" else "Unconfirmed"}", "info", if (ok) "ok" else "warn")
                    }
                }
                detectHondaState()
                showToast("Action $action completed")
            } catch (e: Exception) {
                log("[K-line] Danger action $action failed: ${e.message}", "info", "error")
                showToast("Action failed: ${e.message}")
            } finally {
                _hondaState.update { it.copy(dangerActionRunning = false) }
            }
        }
    }

    // ---------------------------------------------------- SIMULATOR CONTROLS

    fun setSimIgnition(on: Boolean) {
        _simControls.update { it.copy(ignition = on) }
        simulatedCar.ignition = on
        hondaTransport?.ignition = on
        log("Simulator: Ignition ${if (on) "ON" else "OFF"}", "info")
    }

    fun setSimThrottle(pct: Float) {
        val clamped = pct.coerceIn(0f, 1f)
        _simControls.update { it.copy(throttle = clamped) }
        simulatedCar.throttleSet = clamped
    }

    fun setDtcToInject(code: String) {
        _simControls.update { it.copy(selectedDtcToInject = code) }
    }

    fun injectDtcIntoSimulator() {
        val code = _simControls.value.selectedDtcToInject
        simulatedCar.injectDtc(code)
        log("Simulator: Injected OBD DTC $code", "info", "warn")
        showToast("Injected $code into simulator")
        if (_obdState.value.isConnected) {
            readObdDtcs()
        }
    }

    fun clearSimulatorDtcs() {
        simulatedCar.clearDtcs()
        log("Simulator: Cleared all simulated DTCs", "info", "ok")
        showToast("Simulator DTCs cleared")
        if (_obdState.value.isConnected) {
            readObdDtcs()
        }
    }

    fun setHondaFaultToInject(code: String) {
        _simControls.update { it.copy(selectedHondaFaultToInject = code) }
    }

    fun injectHondaFaultIntoSimulator() {
        val code = _simControls.value.selectedHondaFaultToInject
        hondaTransport?.let { t ->
            if (!t.currentFaults.contains(code)) {
                t.currentFaults.add(code)
            }
        }
        log("[K-line Sim] Injected fault $code", "info", "warn")
        showToast("Injected Honda Fault $code")
        if (_hondaState.value.isConnected) {
            readHondaFaults()
        }
    }

    fun clearHondaSimulatorFaults() {
        hondaTransport?.let { t ->
            t.currentFaults.clear()
            t.pastFaults.clear()
        }
        log("[K-line Sim] Cleared all faults", "info", "ok")
        showToast("Simulator Honda faults cleared")
        if (_hondaState.value.isConnected) {
            readHondaFaults()
        }
    }
}
