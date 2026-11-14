package com.example

import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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
import com.example.ui.screens.SupportScreen
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
                var showSupportScreen by remember { mutableStateOf(false) }
                var availableUpdate by remember { mutableStateOf<Map<String, String>?>(null) }
                var updateBusy by remember { mutableStateOf(false) }
                var updateError by remember { mutableStateOf<String?>(null) }
                val updateScope = rememberCoroutineScope()
                LaunchedEffect(Unit) { availableUpdate = loadSelfHostedUpdate() }

                if (showAdminScreen) {
                    AdminScreen(onBack = { showAdminScreen = false })
                } else if (showSupportScreen) {
                    SupportScreen(onBack = { showSupportScreen = false })
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
                                NavigationTab.ACCOUNT -> AccountScreen(
                                    onAdminAccess = { showAdminScreen = true },
                                    onSupportAccess = { showSupportScreen = true }
                                )
                            }
                        }
                    }
                }
                availableUpdate?.let { release ->
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("DOM Ask AI update available") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Version ${release["versionName"] ?: ""} is ready to install.")
                                val notes = release["releaseNotes"].orEmpty()
                                if (notes.isNotBlank()) Text(notes)
                                Text("Android will show a system approval screen before installing the update.")
                                updateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                updateScope.launch {
                                    updateBusy = true
                                    updateError = null
                                    try {
                                        installSelfHostedUpdate(release)
                                        availableUpdate = null
                                    } catch (e: Exception) {
                                        updateError = e.message ?: "Could not start the update."
                                    } finally {
                                        updateBusy = false
                                    }
                                }
                            }, enabled = !updateBusy) {
                                Text(if (updateBusy) "Preparing…" else "Update")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { availableUpdate = null }) { Text("Later") }
                        }
                    )
                }
            }
        }
    }

    private suspend fun loadSelfHostedUpdate(): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val configured = AskScreenApp.instance.preferencesManager.backendUrl.trim().trimEnd('/')
                if (!configured.endsWith("/ask-screen")) return@withContext null
                val endpoint = configured.removeSuffix("/ask-screen") + "/app-update"
                val request = Request.Builder().url(endpoint).get().build()
                OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val root = org.json.JSONObject(response.body?.string().orEmpty())
                    if (!root.optBoolean("updateAvailable")) return@withContext null
                    val release = root.optJSONObject("release") ?: return@withContext null
                    if (release.optString("packageName") != packageName) return@withContext null
                    val info = packageManager.getPackageInfo(packageName, 0)
                    val current = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                    if (release.optLong("versionCode") <= current) return@withContext null
                    val url = release.optString("downloadUrl")
                    if (!url.startsWith("https://")) return@withContext null
                    mapOf(
                        "versionCode" to release.optLong("versionCode").toString(),
                        "versionName" to release.optString("versionName"),
                        "releaseNotes" to release.optString("releaseNotes"),
                        "downloadUrl" to url,
                        "md5Hash" to release.optString("md5Hash"),
                        "sizeBytes" to release.optLong("sizeBytes").toString()
                    )
                }
            } catch (_: Exception) { null }
        }
    }

    private suspend fun installSelfHostedUpdate(release: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val url = release["downloadUrl"] ?: throw IllegalStateException("Update download link is missing.")
            val expectedSize = release["sizeBytes"]?.toLongOrNull() ?: 0L
            val expectedMd5 = release["md5Hash"].orEmpty()
            val apk = File(cacheDir, "dom-ask-ai-update.apk")
            val request = Request.Builder().url(url).get().build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Could not download the update (HTTP ${response.code}).")
                val body = response.body ?: throw IllegalStateException("The update file was empty.")
                if (expectedSize > 0 && body.contentLength() > 0 && body.contentLength() != expectedSize) {
                    throw IllegalStateException("The update file size does not match the published release.")
                }
                val digest = MessageDigest.getInstance("MD5")
                body.byteStream().use { input ->
                    FileOutputStream(apk).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (expectedSize > 0 && apk.length() != expectedSize) throw IllegalStateException("The downloaded APK is incomplete.")
                val actualMd5 = android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
                if (expectedMd5.isNotBlank() && actualMd5 != expectedMd5) throw IllegalStateException("APK integrity check failed. Please try again.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    Toast.makeText(this@MainActivity, "Allow AskScreen AI to install updates, then return and tap Update again.", Toast.LENGTH_LONG).show()
                }
                throw IllegalStateException("Allow installation from this app in Android settings, then tap Update again.")
            }
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", apk)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
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
