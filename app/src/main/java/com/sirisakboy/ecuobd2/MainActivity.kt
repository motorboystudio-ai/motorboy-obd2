package com.sirisakboy.ecuobd2

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirisakboy.ecuobd2.ui.MainViewModel
import com.sirisakboy.ecuobd2.ui.screens.HondaKlineScreen
import com.sirisakboy.ecuobd2.ui.screens.ObdDashboardScreen
import com.sirisakboy.ecuobd2.ui.screens.SimulatorScreen
import com.sirisakboy.ecuobd2.ui.screens.TerminalScreen
import com.sirisakboy.ecuobd2.ui.theme.DarkBg
import com.sirisakboy.ecuobd2.ui.theme.DarkCard
import com.sirisakboy.ecuobd2.ui.theme.EcuObd2Theme
import com.sirisakboy.ecuobd2.ui.theme.TextPrimary
import com.sirisakboy.ecuobd2.ui.theme.AccentCyan
import com.sirisakboy.ecuobd2.ui.theme.TextMuted

enum class AppDestination(val route: String, val title: String, val icon: ImageVector) {
    OBD_DASHBOARD("dashboard", "Dashboard", Icons.Default.Speed),
    HONDA_KLINE("honda", "Bike ECU", Icons.Default.Memory),
    SIMULATOR("simulator", "Simulator", Icons.Default.Tune),
    TERMINAL("terminal", "Traffic", Icons.Default.Terminal)
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            EcuObd2Theme {
                val currentScreen = remember { mutableStateOf(AppDestination.OBD_DASHBOARD) }
                val toastMessage by viewModel.toastMessage.collectAsState()

                LaunchedEffect(toastMessage) {
                    toastMessage?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        viewModel.clearToast()
                    }
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBg),
                    containerColor = DarkBg,
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "ECU OBD2",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                        color = TextPrimary,
                                        letterSpacing = 1.sp
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = DarkBg,
                                titleContentColor = TextPrimary
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = DarkCard,
                            contentColor = TextPrimary,
                            tonalElevation = 4.dp
                        ) {
                            AppDestination.entries.forEach { dest ->
                                val selected = currentScreen.value == dest
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentScreen.value = dest },
                                    icon = {
                                        Icon(
                                            imageVector = dest.icon,
                                            contentDescription = dest.title,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = dest.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentCyan,
                                        selectedTextColor = AccentCyan,
                                        indicatorColor = DarkBg,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    ),
                                    modifier = Modifier.testTag("nav_item_${dest.route}")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentScreen.value) {
                            AppDestination.OBD_DASHBOARD -> ObdDashboardScreen(viewModel = viewModel)
                            AppDestination.HONDA_KLINE -> HondaKlineScreen(viewModel = viewModel)
                            AppDestination.SIMULATOR -> SimulatorScreen(viewModel = viewModel)
                            AppDestination.TERMINAL -> TerminalScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
