package com.sirisakboy.ecuobd2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirisakboy.ecuobd2.protocol.FreezeFrameData
import com.sirisakboy.ecuobd2.protocol.ObdConstants
import com.sirisakboy.ecuobd2.ui.MainViewModel
import com.sirisakboy.ecuobd2.ui.components.CircularArcGauge
import com.sirisakboy.ecuobd2.ui.components.MetricTile
import com.sirisakboy.ecuobd2.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObdDashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val obdState by viewModel.obdState.collectAsState()
    val isMetric = obdState.unit == "metric"

    val displayedSpeed = if (isMetric) obdState.speedKmh else obdState.speedKmh * 0.621371f
    val speedUnit = if (isMetric) "km/h" else "mph"

    val displayedCoolant = if (isMetric) obdState.coolantC else (obdState.coolantC * 9f / 5f) + 32f
    val coolantUnit = if (isMetric) "°C" else "°F"

    val displayedAirTemp = if (isMetric) obdState.airTempC else (obdState.airTempC * 9f / 5f) + 32f

    var showDeviceDropdown by remember { mutableStateOf(false) }
    var showProtoDropdown by remember { mutableStateOf(false) }
    var showIntervalDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_obd_dashboard"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Connection & Protocol Bar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Connection Status Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (obdState.isConnected) OkGreen else if (obdState.isConnecting) WarnAmber else DangerRed)
                            )
                            Text(
                                text = if (obdState.isConnected) "OBD-II CONNECTED" else if (obdState.isConnecting) "CONNECTING..." else "DISCONNECTED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (obdState.isConnected) OkGreen else if (obdState.isConnecting) WarnAmber else TextMuted
                            )
                        }

                        // Connect / Disconnect button
                        Button(
                            onClick = { viewModel.toggleObdConnect() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (obdState.isConnected) DangerRed.copy(alpha = 0.85f) else AccentCyan,
                                contentColor = if (obdState.isConnected) TextPrimary else DarkBg
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("button_obd_connect")
                        ) {
                            Icon(
                                imageVector = if (obdState.isConnected) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                                contentDescription = if (obdState.isConnected) "Disconnect" else "Connect",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (obdState.isConnected) "DISCONNECT" else "CONNECT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Controls Row: Device Picker, Protocol Picker, Interval
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Device Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { if (!obdState.isConnected) showDeviceDropdown = true },
                                enabled = !obdState.isConnected,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dropdown_device_select")
                            ) {
                                Text(
                                    text = when (obdState.device) {
                                        "sim" -> "Simulator"
                                        "bluetooth" -> "Bluetooth"
                                        "serial" -> "USB Serial"
                                        else -> "Simulator"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (obdState.isConnected) TextMuted else TextPrimary,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = showDeviceDropdown,
                                onDismissRequest = { showDeviceDropdown = false },
                                modifier = Modifier.background(DarkCard)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Simulator (Virtual Bike)", color = TextPrimary) },
                                    onClick = {
                                        viewModel.setObdDevice("sim")
                                        showDeviceDropdown = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bluetooth ELM327", color = TextPrimary) },
                                    onClick = {
                                        viewModel.setObdDevice("bluetooth")
                                        showDeviceDropdown = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("USB Serial Adapter", color = TextPrimary) },
                                    onClick = {
                                        viewModel.setObdDevice("serial")
                                        showDeviceDropdown = false
                                    }
                                )
                            }
                        }

                        // Protocol Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { if (!obdState.isConnected) showProtoDropdown = true },
                                enabled = !obdState.isConnected,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dropdown_proto_select")
                            ) {
                                Text(
                                    text = obdState.detectedProtocol ?: obdState.protocol,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (obdState.isConnected) TextMuted else TextPrimary,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = showProtoDropdown,
                                onDismissRequest = { showProtoDropdown = false },
                                modifier = Modifier.background(DarkCard)
                            ) {
                                listOf("AUTO", "CAN413", "CAN250", "ISO9141", "VPW", "PWM").forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p, color = TextPrimary) },
                                        onClick = {
                                            viewModel.setObdProtocol(p)
                                            showProtoDropdown = false
                                        }
                                    )
                                }
                            }
                        }

                        // Unit Switcher
                        OutlinedButton(
                            onClick = { viewModel.setUnit(if (isMetric) "imperial" else "metric") },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("button_unit_toggle")
                        ) {
                            Text(
                                text = if (isMetric) "KM / °C" else "MI / °F",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Engine Status Chips
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(
                            label = "ENGINE",
                            active = obdState.engineRunning,
                            activeColor = OkGreen,
                            inactiveColor = TextMuted
                        )
                        StatusBadge(
                            label = "CLOSED LOOP",
                            active = obdState.closedLoop,
                            activeColor = AccentCyan,
                            inactiveColor = TextMuted
                        )
                        StatusBadge(
                            label = "MIL",
                            active = obdState.mil,
                            activeColor = DangerRed,
                            inactiveColor = TextMuted
                        )
                        if (obdState.vin.isNotEmpty()) {
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "VIN: ${obdState.vin.take(8)}...",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 2. Primary Gauges (RPM Tachometer & Speedometer)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularArcGauge(
                    title = "Engine RPM",
                    value = obdState.rpm,
                    unit = "RPM",
                    min = 0f,
                    max = 15000f,
                    redlineStart = 13500f,
                    amberStart = 11500f,
                    accentColor = AccentCyan,
                    precision = 0,
                    modifier = Modifier.weight(1f),
                    testTag = "gauge_rpm"
                )

                CircularArcGauge(
                    title = "Speed",
                    value = displayedSpeed,
                    unit = speedUnit,
                    min = 0f,
                    max = if (isMetric) 260f else 160f,
                    amberStart = if (isMetric) 180f else 110f,
                    redlineStart = if (isMetric) 220f else 135f,
                    accentColor = OkGreen,
                    precision = 0,
                    modifier = Modifier.weight(1f),
                    testTag = "gauge_speed"
                )
            }
        }

        // 3. Secondary Gauges (Coolant & Fuel)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularArcGauge(
                    title = "Coolant Temp",
                    value = displayedCoolant,
                    unit = coolantUnit,
                    min = if (isMetric) 0f else 32f,
                    max = if (isMetric) 130f else 260f,
                    amberStart = if (isMetric) 98f else 208f,
                    redlineStart = if (isMetric) 110f else 230f,
                    accentColor = WarnAmber,
                    precision = 0,
                    modifier = Modifier.weight(1f),
                    testTag = "gauge_coolant"
                )

                CircularArcGauge(
                    title = "Fuel Level",
                    value = obdState.fuelPct,
                    unit = "%",
                    min = 0f,
                    max = 100f,
                    amberStart = null,
                    redlineStart = null,
                    accentColor = AccentCyan,
                    precision = 0,
                    modifier = Modifier.weight(1f),
                    testTag = "gauge_fuel"
                )
            }
        }

        // 4. Secondary Live Sensor Grid
        item {
            Text(
                text = "LIVE SENSOR TELEMETRY",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "Throttle (TPS)",
                        value = "%.1f".format(obdState.throttlePct),
                        unit = "%",
                        icon = Icons.Default.Speed,
                        accentColor = AccentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Intake Air Temp",
                        value = "%.0f".format(displayedAirTemp),
                        unit = coolantUnit,
                        icon = Icons.Default.Thermostat,
                        accentColor = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "MAF Flow",
                        value = "%.2f".format(obdState.maf),
                        unit = "g/s",
                        icon = Icons.Default.Air,
                        accentColor = OkGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Battery Voltage",
                        value = "%.2f".format(obdState.voltage),
                        unit = "V",
                        icon = Icons.Default.ElectricBolt,
                        accentColor = if (obdState.voltage < 12.0f) WarnAmber else TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "MAP Pressure",
                        value = "%.0f".format(obdState.mapKpa),
                        unit = "kPa",
                        icon = Icons.Default.Compress,
                        accentColor = AccentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "O2 Sensor Bank 1",
                        value = "%.3f".format(obdState.o2Volts),
                        unit = "V",
                        icon = Icons.Default.Sensors,
                        accentColor = OkGreen,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "STFT (Short-term)",
                        value = "%+.1f".format(obdState.trimStPct),
                        unit = "%",
                        icon = Icons.Default.Tune,
                        accentColor = if (kotlin.math.abs(obdState.trimStPct) > 10f) WarnAmber else TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "LTFT (Long-term)",
                        value = "%+.1f".format(obdState.trimLtPct),
                        unit = "%",
                        icon = Icons.Default.Tune,
                        accentColor = if (kotlin.math.abs(obdState.trimLtPct) > 10f) WarnAmber else TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 5. Diagnostic Trouble Codes (DTC) & Readiness
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "DTC Diagnostics",
                                tint = if (obdState.dtcs.isNotEmpty()) DangerRed else OkGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DIAGNOSTIC TROUBLE CODES",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { viewModel.readObdDtcs() },
                                modifier = Modifier.testTag("button_refresh_dtc")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh DTCs", tint = AccentCyan)
                            }
                            if (obdState.dtcs.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.clearObdDtcs() },
                                    modifier = Modifier.testTag("button_clear_dtc")
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Clear DTCs", tint = DangerRed)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (obdState.dtcs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkInset, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No stored DTC fault codes detected. System normal.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OkGreen
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            obdState.dtcs.forEach { code ->
                                val desc = ObdConstants.DTC_INFO[code] ?: "Diagnostic trouble code ($code)"
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkInset, RoundedCornerShape(8.dp))
                                        .border(1.dp, DangerRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .clickable { viewModel.inspectFreezeFrame(code) }
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = code,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = DangerRed
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Tap for Freeze Frame",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = AccentCyan
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextPrimary
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Inspect",
                                            tint = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Readiness Group Monitors
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "I/M READINESS MONITORS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ObdConstants.READINESS_MONITORS.take(4).forEach { (name, bit) ->
                            val ready = (obdState.readinessMask and (1 shl bit)) == 0
                            ReadinessChip(name = name, ready = ready, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ObdConstants.READINESS_MONITORS.drop(4).forEach { (name, bit) ->
                            val ready = (obdState.readinessMask and (1 shl bit)) == 0
                            ReadinessChip(name = name, ready = ready, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // Freeze Frame Dialog
    if (obdState.freezeFrame != null) {
        val ff = obdState.freezeFrame!!
        AlertDialog(
            onDismissRequest = { viewModel.closeFreezeFrame() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AcUnit, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Freeze Frame: ${ff.code}", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = ObdConstants.DTC_INFO[ff.code] ?: "DTC Fault Snapshot",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                    HorizontalDivider(color = BorderColor)
                    FreezeRow("RPM", ff.rpm?.let { "%.0f RPM".format(it) } ?: "--")
                    FreezeRow("Speed", ff.speedKmh?.let { "%.0f km/h".format(it) } ?: "--")
                    FreezeRow("Coolant", ff.coolantC?.let { "%.1f °C".format(it) } ?: "--")
                    FreezeRow("Throttle", ff.throttlePct?.let { "%.1f %%".format(it) } ?: "--")
                    FreezeRow("Fuel Level", ff.fuelPct?.let { "%.1f %%".format(it) } ?: "--")
                    FreezeRow("Voltage", ff.voltage?.let { "%.2f V".format(it) } ?: "--")
                    FreezeRow("MAF Flow", ff.maf?.let { "%.2f g/s".format(it) } ?: "--")
                    FreezeRow("Intake Air", ff.airTempC?.let { "%.1f °C".format(it) } ?: "--")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.closeFreezeFrame() }) {
                    Text("CLOSE", color = AccentCyan)
                }
            },
            containerColor = DarkCard
        )
    }
}

@Composable
private fun StatusBadge(
    label: String,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color
) {
    Box(
        modifier = Modifier
            .background(if (active) activeColor.copy(alpha = 0.15f) else DarkInset, RoundedCornerShape(6.dp))
            .border(1.dp, if (active) activeColor else BorderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (active) activeColor else inactiveColor
        )
    }
}

@Composable
private fun ReadinessChip(
    name: String,
    ready: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(DarkInset, RoundedCornerShape(6.dp))
            .border(1.dp, if (ready) OkGreen.copy(alpha = 0.4f) else WarnAmber.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = name,
                fontSize = 9.sp,
                color = TextMuted,
                maxLines = 1
            )
            Text(
                text = if (ready) "READY" else "NOT READY",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (ready) OkGreen else WarnAmber
            )
        }
    }
}

@Composable
private fun FreezeRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}
