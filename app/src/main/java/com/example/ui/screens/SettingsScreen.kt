package com.example.ui.screens

import android.widget.Toast
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AskScreenApp
import com.example.data.model.WindowSizePreset
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val scope = rememberCoroutineScope()

    var bubbleSize by remember { mutableIntStateOf(prefs.bubbleSizeDp) }
    var bubbleOpacity by remember { mutableIntStateOf(prefs.bubbleOpacityPercent) }
    var backendUrl by remember { mutableStateOf(prefs.backendUrl) }
    var apiKeyOverride by remember { mutableStateOf(prefs.apiKeyOverride) }
    var selectedModel by remember { mutableStateOf(prefs.geminiModel) }
    var selectedPreset by remember { mutableStateOf(prefs.windowPreset) }
    var isTtsEnabled by remember { mutableStateOf(prefs.isTtsEnabled) }
    var ttsSpeed by remember { mutableFloatStateOf(prefs.ttsSpeed) }
    var isPermanentHistory by remember { mutableStateOf(prefs.isPermanentHistoryEnabled) }
    var isAutoSubmit by remember { mutableStateOf(prefs.isAutoSubmitEnabled) }
    var monitorInterval by remember { mutableFloatStateOf(prefs.autoMonitorIntervalMs.toFloat() / 1000f) }
    var audioLanguageTag by remember { mutableStateOf(prefs.audioLanguageTag) }

    var isTestingConnection by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings_screen_content")
    ) {
        Text(
            text = "ASSISTANT SETTINGS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 1. BUBBLE CUSTOMIZATION
        SettingsCard(title = "Floating AI Bubble Customization", icon = Icons.Default.Circle) {
            Text("Bubble Size", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(48 to "Small", 60 to "Medium", 72 to "Large").forEach { (size, label) ->
                    FilterChip(
                        selected = bubbleSize == size,
                        onClick = {
                            bubbleSize = size
                            prefs.bubbleSizeDp = size
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = Slate950,
                            containerColor = Slate800,
                            labelColor = Slate300
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Bubble Opacity", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text("$bubbleOpacity%", fontSize = 12.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
            }

            Slider(
                value = bubbleOpacity.toFloat(),
                onValueChange = {
                    bubbleOpacity = it.toInt()
                    prefs.bubbleOpacityPercent = it.toInt()
                },
                valueRange = 40f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = ElectricCyan,
                    activeTrackColor = ElectricCyan,
                    inactiveTrackColor = Slate800
                )
            )

            // Live Bubble Preview
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate800.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(bubbleSize.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(ElectricCyan, VibrantPurple)),
                            alpha = bubbleOpacity / 100f
                        )
                        .border(1.5.dp, ElectricCyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size((bubbleSize * 0.45).dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Live Bubble Preview", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Displays above apps with edge snapping", fontSize = 11.sp, color = Slate400)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. WINDOW DEFAULTS
        SettingsCard(title = "Answer Window Preset", icon = Icons.Default.WebAsset) {
            Text("Default Window Size", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WindowSizePreset.values().forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            prefs.windowPreset = preset
                        },
                        label = { Text(preset.label, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = Slate950,
                            containerColor = Slate800,
                            labelColor = Slate300
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. AI BACKEND (only the link is needed; the AI keys live on the backend)
        SettingsCard(title = "AI Backend", icon = Icons.Default.Cloud) {
            Text("Backend link", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Text(
                "Paste your Netlify ask-screen function URL here, ending in /ask-screen. The app uses this same backend link for account usage and secure admin monitoring.",
                fontSize = 11.sp,
                color = Slate400
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = backendUrl,
                onValueChange = {
                    backendUrl = it
                    prefs.backendUrl = it.trim()
                },
                placeholder = { Text("https://your-site.netlify.app/.netlify/functions/ask-screen", fontSize = 11.sp, color = Slate600) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = Slate700,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. VOICE & TTS
        SettingsCard(title = "Voice & Text-To-Speech", icon = Icons.Default.VolumeUp) {
            Text("Speech recognition language", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Text("Choose the language spoken into your microphone. Support depends on your Android speech-recognition provider.", fontSize = 11.sp, color = Slate400)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("" to "Device default", "en-NG" to "English", "yo-NG" to "Yoruba").forEach { (tag, label) ->
                    FilterChip(
                        selected = audioLanguageTag == tag,
                        onClick = {
                            audioLanguageTag = tag
                            prefs.audioLanguageTag = tag
                        },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = Slate950,
                            containerColor = Slate800,
                            labelColor = Slate300
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Speak Answers", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Text("Read answers aloud automatically via TTS", fontSize = 11.sp, color = Slate400)
                }
                Switch(
                    checked = isTtsEnabled,
                    onCheckedChange = {
                        isTtsEnabled = it
                        prefs.isTtsEnabled = it
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = ElectricCyan)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Speech Rate", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(String.format("%.2fx", ttsSpeed), fontSize = 12.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
            }

            Slider(
                value = ttsSpeed,
                onValueChange = {
                    ttsSpeed = it
                    prefs.ttsSpeed = it
                },
                valueRange = 0.75f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = ElectricCyan,
                    activeTrackColor = ElectricCyan,
                    inactiveTrackColor = Slate800
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // PRACTICE AUTOMATION
        SettingsCard(title = "Practice Automation", icon = Icons.Default.Settings) {
            Text(
                "Auto-monitoring is started or paused from the persistent AskScreen notification. It checks for stable screen changes and analyses a changed screen. Android may stop background work to save battery.",
                fontSize = 11.sp, color = Slate300, lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Select and submit practice answers", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Text("Off by default. Requires the optional accessibility service and only works on pages exposing accessible answer and Submit controls.", fontSize = 11.sp, color = Slate400)
                }
                Switch(
                    checked = isAutoSubmit,
                    onCheckedChange = {
                        isAutoSubmit = it
                        prefs.isAutoSubmitEnabled = it
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = ElectricCyan)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { Toast.makeText(context, "Open Android Settings > Accessibility to enable AskScreen AI", Toast.LENGTH_LONG).show() }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Android Accessibility Settings") }
            Spacer(modifier = Modifier.height(12.dp))
            Text("Screen check interval: ${String.format("%.1f", monitorInterval)} seconds", fontSize = 12.sp, color = Color.White)
            Slider(
                value = monitorInterval,
                onValueChange = {
                    monitorInterval = it
                    prefs.autoMonitorIntervalMs = (it * 1000).toLong()
                },
                valueRange = 1.5f..10f,
                colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan, inactiveTrackColor = Slate800)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. PRIVACY & SAFETY
        SettingsCard(title = "Privacy & Safety", icon = Icons.Default.Security) {
            Text(
                text = "• Screen and microphone capture are user-started and remain active only while the session is running.\n" +
                        "• Android may block capture of secure, DRM-protected, or otherwise restricted screens.\n" +
                        "• Automatic answer interaction is off by default and requires Android Accessibility permission.\n" +
                        "• Accessibility automation is best-effort; custom controls and some apps/browsers may not expose clickable answers.\n" +
                        "• Review the AI request and app privacy settings before using screen or audio capture. Local history can be disabled below.",
                fontSize = 11.sp,
                color = Slate300,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Save Chat History Locally", fontSize = 13.sp, color = Color.White)
                Switch(
                    checked = isPermanentHistory,
                    onCheckedChange = {
                        isPermanentHistory = it
                        prefs.isPermanentHistoryEnabled = it
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = ElectricCyan)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Slate900,
        border = BorderStroke(1.dp, Slate800)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}
