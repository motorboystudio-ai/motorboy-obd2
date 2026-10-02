package com.sirisakboy.ecuobd2.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.sirisakboy.ecuobd2.protocol.EcuState
import com.sirisakboy.ecuobd2.protocol.HondaConstants
import com.sirisakboy.ecuobd2.ui.MainViewModel
import com.sirisakboy.ecuobd2.ui.theme.*

@Composable
fun HondaKlineScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val hondaState by viewModel.hondaState.collectAsState()
    var showDangerConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showRomSizeMenu by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_honda_kline"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Connection Header & ECU State
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (hondaState.isConnected) OkGreen else DangerRed)
                            )
                            Column {
                                Text(
                                    text = "HONDA K-LINE (10.4 KBPS)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hondaState.isConnected) OkGreen else TextMuted
                                )
                                Text(
                                    text = "eculib protocol engine",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        Button(
                            onClick = { viewModel.toggleHondaConnect() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hondaState.isConnected) DangerRed.copy(alpha = 0.85f) else AccentCyan,
                                contentColor = if (hondaState.isConnected) TextPrimary else DarkBg
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("button_honda_connect")
                        ) {
                            Icon(
                                imageVector = if (hondaState.isConnected) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hondaState.isConnected) "DISCONNECT" else "CONNECT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ECU State Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkInset, RoundedCornerShape(10.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "ECU OPERATING STATE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = hondaState.ecuState.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when (hondaState.ecuState) {
                                        EcuState.OK -> OkGreen
                                        EcuState.READ, EcuState.READING -> AccentCyan
                                        EcuState.WRITE, EcuState.WRITING, EcuState.INIT_WRITE -> WarnAmber
                                        EcuState.ERROR -> DangerRed
                                        else -> TextPrimary
                                    }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.pingHonda() },
                                    enabled = hondaState.isConnected,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("button_honda_ping")
                                ) {
                                    Text("PING (FE 72)", style = MaterialTheme.typography.labelSmall, color = AccentCyan)
                                }

                                OutlinedButton(
                                    onClick = { viewModel.detectHondaState() },
                                    enabled = hondaState.isConnected,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("button_honda_detect_state")
                                ) {
                                    Text("DETECT", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                }
                            }
                        }
                    }

                    // Quick Actions Toolbar
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.readHondaFaults() },
                            enabled = hondaState.isConnected,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_honda_read_faults")
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = WarnAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("READ FAULTS", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }

                        Button(
                            onClick = { viewModel.probeHondaTables() },
                            enabled = hondaState.isConnected,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_honda_probe_tables")
                        ) {
                            Icon(Icons.Default.TableChart, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PROBE TABLES", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            }
        }

        // 2. Honda Diagnostic Fault Codes (Current & Past)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "MOTORCYCLE FAULT CODES (0x74 / 0x73)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Current Faults
                    Text(
                        text = "CURRENT FAULTS (0x74): ${hondaState.currentFaults.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hondaState.currentFaults.isNotEmpty()) DangerRed else OkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (hondaState.currentFaults.isEmpty()) {
                        Text(
                            text = "No current active DTC faults.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            hondaState.currentFaults.forEach { code ->
                                val desc = HondaConstants.HONDA_DTC[code] ?: "Honda ECU fault ($code)"
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkInset, RoundedCornerShape(6.dp))
                                        .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = code,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = DangerRed
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Past Faults
                    Text(
                        text = "PAST FAULTS (0x73): ${hondaState.pastFaults.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hondaState.pastFaults.isNotEmpty()) WarnAmber else TextMuted,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (hondaState.pastFaults.isEmpty()) {
                        Text(
                            text = "No past history faults recorded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            hondaState.pastFaults.forEach { code ->
                                val desc = HondaConstants.HONDA_DTC[code] ?: "Honda ECU fault ($code)"
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkInset, RoundedCornerShape(6.dp))
                                        .border(1.dp, WarnAmber.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = code,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = WarnAmber
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Probed ECU Information Tables (0x10..0xD1)
        if (hondaState.probedTables.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "PROBED ECU TABLES (0x71)",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            hondaState.probedTables.forEach { (tbl, data) ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkInset, RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Table 0x%02X".format(tbl),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentCyan
                                        )
                                        Text(
                                            text = HondaConstants.toHexStr(data),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. ROM Read / Write / Verify Engine
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
                        Text(
                            text = "ROM MANAGER",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )

                        // ROM Size Selector
                        Box {
                            OutlinedButton(
                                onClick = { showRomSizeMenu = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("dropdown_rom_size")
                            ) {
                                Text(
                                    text = "${hondaState.romSize / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentCyan
                                )
                            }
                            DropdownMenu(
                                expanded = showRomSizeMenu,
                                onDismissRequest = { showRomSizeMenu = false },
                                modifier = Modifier.background(DarkCard)
                            ) {
                                listOf(16 * 1024, 64 * 1024, 128 * 1024, 256 * 1024).forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text("${s / 1024} KB", color = TextPrimary) },
                                        onClick = {
                                            viewModel.setRomSize(s)
                                            showRomSizeMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (hondaState.romInfoText.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkInset, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = hondaState.romInfoText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = OkGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Progress bar if reading/writing
                    if (hondaState.romProgress != null) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = hondaState.romProgressText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentCyan
                                )
                                Text(
                                    text = "${((hondaState.romProgress ?: 0f) * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { hondaState.romProgress ?: 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AccentCyan,
                                trackColor = DarkInset
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.readRomFromEcu() },
                            enabled = hondaState.isConnected && hondaState.romProgress == null,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_honda_read_rom")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("READ ROM", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }

                        Button(
                            onClick = { viewModel.loadSampleRom() },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_honda_load_bin")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("LOAD .BIN", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }

                        Button(
                            onClick = { viewModel.writeRomToEcu() },
                            enabled = hondaState.isConnected && hondaState.loadedRomBytes != null && hondaState.romProgress == null,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkInset),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(WarnAmber)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("button_honda_write_rom")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, tint = WarnAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("WRITE ROM", style = MaterialTheme.typography.labelSmall, color = WarnAmber)
                        }
                    }
                }
            }
        }

        // 5. Flash / Recovery Danger Zone
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DangerRed.copy(alpha = 0.5f)))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FLASH / RECOVERY DANGER ZONE",
                            style = MaterialTheme.typography.titleMedium,
                            color = DangerRed
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Low-level ECU state transitions. Use caution when issuing commands to a physical motorcycle ECU.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDangerConfirmDialog = "recover" },
                            enabled = hondaState.isConnected && !hondaState.dangerActionRunning,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("INIT RECOVER", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = { showDangerConfirmDialog = "write" },
                            enabled = hondaState.isConnected && !hondaState.dangerActionRunning,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("INIT WRITE", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDangerConfirmDialog = "erase" },
                            enabled = hondaState.isConnected && !hondaState.dangerActionRunning,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ERASE FLASH", style = MaterialTheme.typography.labelSmall, color = DangerRed)
                        }

                        OutlinedButton(
                            onClick = { showDangerConfirmDialog = "post" },
                            enabled = hondaState.isConnected && !hondaState.dangerActionRunning,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("POST WRITE", style = MaterialTheme.typography.labelSmall, color = OkGreen)
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for danger actions
    if (showDangerConfirmDialog != null) {
        val action = showDangerConfirmDialog!!
        AlertDialog(
            onDismissRequest = { showDangerConfirmDialog = null },
            title = {
                Text(
                    text = "Confirm: ${action.uppercase()}",
                    color = DangerRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to execute $action on the connected Honda ECU? This will send raw low-level state frames.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.executeDangerAction(action)
                        showDangerConfirmDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("EXECUTE", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDangerConfirmDialog = null }) {
                    Text("CANCEL", color = TextMuted)
                }
            },
            containerColor = DarkCard
        )
    }
}
