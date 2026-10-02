package com.sirisakboy.ecuobd2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirisakboy.ecuobd2.protocol.HondaConstants
import com.sirisakboy.ecuobd2.protocol.ObdConstants
import com.sirisakboy.ecuobd2.ui.MainViewModel
import com.sirisakboy.ecuobd2.ui.theme.*

@Composable
fun SimulatorScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val simControls by viewModel.simControls.collectAsState()
    val obdState by viewModel.obdState.collectAsState()

    var showDtcDropdown by remember { mutableStateOf(false) }
    var showHondaFaultDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_simulator"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Simulator Cockpit Controls
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
                            Icon(Icons.Default.SportsMotorsports, contentDescription = null, tint = AccentCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "VIRTUAL MOTORCYCLE ENGINE",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }

                        // Ignition Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (simControls.ignition) "IGNITION ON" else "IGNITION OFF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (simControls.ignition) OkGreen else DangerRed
                            )
                            Switch(
                                checked = simControls.ignition,
                                onCheckedChange = { viewModel.setSimIgnition(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = OkGreen,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = DarkInset
                                ),
                                modifier = Modifier.testTag("switch_sim_ignition")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Real-time responsive telemetry pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkInset, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SIMULATED RPM", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = "${viewModel.simulatedCar.rpm.toInt()} RPM",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (viewModel.simulatedCar.rpm > 13500f) DangerRed else TextPrimary
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SIMULATED SPEED", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = "${viewModel.simulatedCar.speed.toInt()} km/h",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = OkGreen
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("COOLANT", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = "${viewModel.simulatedCar.coolant.toInt()} °C",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = AccentCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Throttle Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "THROTTLE POSITION (TPS)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${(simControls.throttle * 100).toInt()}%",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                    }

                    Slider(
                        value = simControls.throttle,
                        onValueChange = { viewModel.setSimThrottle(it) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentCyan,
                            inactiveTrackColor = DarkInset
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_sim_throttle")
                    )

                    // Quick throttle presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0f to "Idle (0%)", 0.25f to "25%", 0.50f to "50%", 0.75f to "75%", 1.0f to "WOT (100%)").forEach { (v, label) ->
                            OutlinedButton(
                                onClick = { viewModel.setSimThrottle(v) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // 2. OBD-II DTC Fault Injector
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "OBD-II DTC FAULT INJECTOR",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Inject standard SAE J1979 diagnostic trouble codes to test the app's scanner & freeze frame engine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Selected DTC Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showDtcDropdown = true },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dropdown_dtc_inject")
                        ) {
                            val desc = ObdConstants.DTC_INFO[simControls.selectedDtcToInject] ?: ""
                            Text(
                                text = "${simControls.selectedDtcToInject} — $desc",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = showDtcDropdown,
                            onDismissRequest = { showDtcDropdown = false },
                            modifier = Modifier.background(DarkCard)
                        ) {
                            listOf(
                                "P0301" to "Cylinder 1 Misfire Detected",
                                "P0300" to "Random/Multiple Cylinder Misfire",
                                "P0420" to "Catalyst System Efficiency Below Threshold",
                                "P0442" to "EVAP Control System Leak Detected",
                                "P0135" to "O2 Sensor Heater Circuit (Bank 1, Sensor 1)",
                                "P0030" to "O2 Sensor Heater Circuit (Bank 1, Sensor 1)",
                                "P0171" to "System Too Lean (Bank 1)",
                                "P0101" to "Mass Air Flow Sensor Performance",
                                "P0562" to "System Voltage Low",
                                "P0700" to "Transmission Control System Malfunction"
                            ).forEach { (code, desc) ->
                                DropdownMenuItem(
                                    text = {
                                        Text("$code — $desc", color = TextPrimary)
                                    },
                                    onClick = {
                                        viewModel.setDtcToInject(code)
                                        showDtcDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.injectDtcIntoSimulator() },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_inject_dtc")
                        ) {
                            Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("INJECT DTC", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { viewModel.clearSimulatorDtcs() },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_clear_sim_dtcs")
                        ) {
                            Icon(Icons.Default.ClearAll, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CLEAR SIM DTCS", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            }
        }

        // 3. Honda K-Line Motorcycle Fault Injector
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "HONDA K-LINE MOTORCYCLE FAULT INJECTOR",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Inject Honda motorcycle-specific fault codes into the virtual K-line ECU.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Selected Honda Fault Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showHondaFaultDropdown = true },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dropdown_honda_fault_inject")
                        ) {
                            val desc = HondaConstants.HONDA_DTC[simControls.selectedHondaFaultToInject] ?: ""
                            Text(
                                text = "${simControls.selectedHondaFaultToInject} — $desc",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = showHondaFaultDropdown,
                            onDismissRequest = { showHondaFaultDropdown = false },
                            modifier = Modifier.background(DarkCard)
                        ) {
                            listOf(
                                "19-01" to "CKP sensor no signal",
                                "18-01" to "CMP sensor no signal",
                                "21-01" to "O2 sensor malfunction",
                                "01-01" to "MAP sensor circuit low voltage",
                                "07-01" to "ECT sensor circuit low voltage",
                                "12-01" to "No.1 primary injector circuit malfunction",
                                "54-01" to "Bank angle sensor circuit low voltage",
                                "86-01" to "Serial communication malfunction"
                            ).forEach { (code, desc) ->
                                DropdownMenuItem(
                                    text = {
                                        Text("$code — $desc", color = TextPrimary)
                                    },
                                    onClick = {
                                        viewModel.setHondaFaultToInject(code)
                                        showHondaFaultDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.injectHondaFaultIntoSimulator() },
                            colors = ButtonDefaults.buttonColors(containerColor = WarnAmber),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_inject_honda_fault")
                        ) {
                            Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("INJECT HONDA FAULT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DarkBg)
                        }

                        Button(
                            onClick = { viewModel.clearHondaSimulatorFaults() },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_clear_honda_faults")
                        ) {
                            Icon(Icons.Default.ClearAll, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CLEAR HONDA FAULTS", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}
