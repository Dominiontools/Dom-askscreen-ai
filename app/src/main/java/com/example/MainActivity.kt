package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.AccountScreen
import com.example.ui.screens.AdminScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.UploadScreen
import com.example.ui.theme.AskScreenTheme
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

enum class NavigationTab(val label: String, val icon: ImageVector) {
    HOME("Hub", Icons.Default.Home),
    UPLOAD("Uploads", Icons.Default.CloudUpload),
    HISTORY("History", Icons.Default.History),
    SETTINGS("Settings", Icons.Default.Settings),
    ACCOUNT("Account", Icons.Default.AccountCircle)
}

class MainActivity : ComponentActivity() {
    private var currentSelectedTab = mutableStateOf(NavigationTab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.util.configureAppCheck(this, intent)
        enableEdgeToEdge()
        handleIncomingShareIntent(intent)

        setContent {
            AskScreenTheme {
                var selectedTab by remember { currentSelectedTab }
                var showAdminScreen by remember { mutableStateOf(false) }

                if (showAdminScreen) {
                    AdminScreen(onBack = { showAdminScreen = false })
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Slate950,
                        bottomBar = {
                            NavigationBar(
                                containerColor = Slate900,
                                tonalElevation = 8.dp,
                                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).testTag("main_bottom_nav")
                            ) {
                                NavigationTab.values().forEach { tab ->
                                    NavigationBarItem(
                                        selected = selectedTab == tab,
                                        onClick = { selectedTab = tab },
                                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                                        label = { Text(text = tab.label, fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Slate950,
                                            selectedTextColor = ElectricCyan,
                                            indicatorColor = ElectricCyan,
                                            unselectedIconColor = Color(0xFF94A3B8),
                                            unselectedTextColor = Color(0xFF94A3B8)
                                        ),
                                        modifier = Modifier.testTag("nav_item_${tab.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier.fillMaxSize().padding(innerPadding).windowInsetsPadding(WindowInsets.statusBars)
                        ) {
                            when (selectedTab) {
                                NavigationTab.HOME -> HomeScreen(
                                    onNavigateToUpload = { selectedTab = NavigationTab.UPLOAD },
                                    onNavigateToSettings = { selectedTab = NavigationTab.SETTINGS }
                                )
                                NavigationTab.UPLOAD -> UploadScreen()
                                NavigationTab.HISTORY -> HistoryScreen()
                                NavigationTab.SETTINGS -> SettingsScreen()
                                NavigationTab.ACCOUNT -> AccountScreen(onAdminAccess = { showAdminScreen = true })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            currentSelectedTab.value = NavigationTab.UPLOAD
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (uri != null || !sharedText.isNullOrEmpty()) {
                Toast.makeText(this, "Received shared content for AskScreen AI analysis!", Toast.LENGTH_SHORT).show()
            }
        } else if ("com.example.action.PICK_FILE" == action) {
            currentSelectedTab.value = NavigationTab.UPLOAD
        }
    }
}
