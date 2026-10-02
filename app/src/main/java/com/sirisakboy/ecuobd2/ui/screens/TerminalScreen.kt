package com.sirisakboy.ecuobd2.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirisakboy.ecuobd2.ui.LogEntry
import com.sirisakboy.ecuobd2.ui.MainViewModel
import com.sirisakboy.ecuobd2.ui.theme.*

@Composable
fun TerminalScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    var filterType by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filteredLogs = remember(logs, filterType, searchQuery) {
        logs.filter { entry ->
            val matchesFilter = when (filterType) {
                "SEND" -> entry.dir == "send"
                "RECV" -> entry.dir == "recv"
                "WARN/ERR" -> entry.kind in listOf("warn", "error")
                else -> true
            }
            val matchesSearch = if (searchQuery.isEmpty()) true else entry.message.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("screen_terminal"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Toolbar: Filters, Search, Copy, Clear
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderColor))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PROTOCOL TRAFFIC LOG (${filteredLogs.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                val text = logs.joinToString("\n") { "[${it.time}] [${it.dir.uppercase()}] ${it.message}" }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Traffic Log", text)
                                clipboard?.setPrimaryClip(clip)
                                viewModel.showToast("Copied ${logs.size} log lines")
                            },
                            modifier = Modifier.testTag("button_copy_logs")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", tint = AccentCyan, modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = { viewModel.clearLogs() },
                            modifier = Modifier.testTag("button_clear_logs")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = DangerRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search & Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("ALL", "SEND", "RECV", "WARN/ERR").forEach { f ->
                        FilterChip(
                            selected = filterType == f,
                            onClick = { filterType = f },
                            label = { Text(f, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentDim,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkInset,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = filterType == f,
                                borderColor = if (filterType == f) AccentCyan else BorderColor
                            )
                        )
                    }
                }
            }
        }

        // Terminal Console Body
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DarkInset, RoundedCornerShape(12.dp))
                .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No traffic messages logged yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredLogs) { entry ->
                        LogRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val dirColor = when (entry.dir) {
        "send" -> AccentCyan
        "recv" -> OkGreen
        else -> TextMuted
    }

    val msgColor = when (entry.kind) {
        "error" -> DangerRed
        "warn" -> WarnAmber
        "ok" -> OkGreen
        else -> TextPrimary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.time,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = when (entry.dir) {
                "send" -> "TX ▶"
                "recv" -> "RX ◀"
                else -> "INFO"
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = dirColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = msgColor,
            modifier = Modifier.weight(1f)
        )
    }
}
