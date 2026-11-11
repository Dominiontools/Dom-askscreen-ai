package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import com.example.AskScreenApp
import com.example.service.AskScreenOverlayService
import com.example.service.ScreenCaptureActivity
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    onNavigateToUpload: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AskScreenApp.instance.preferencesManager }

    var isServiceRunning by remember { mutableStateOf(AskScreenOverlayService.isRunning()) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Permission Launchers
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordAudioPermission = granted
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required for voice & audio listening.", Toast.LENGTH_SHORT).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    // Refresh overlay permission state when screen comes into focus
    LaunchedEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        isServiceRunning = AskScreenOverlayService.isRunning()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("home_screen_content")
    ) {
        // TOP HERO BANNER
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(ElectricCyan, VibrantPurple)),
                    RoundedCornerShape(20.dp)
                ),
            color = Slate900
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(DeepIndigo.copy(alpha = 0.8f), Slate900),
                            radius = 600f
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Dominion Ask AI",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Floating On-Screen Intelligence",
                                fontSize = 13.sp,
                                color = ElectricCyan
                            )
                        }

                        // Assistant Toggle Switch
                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    if (!hasOverlayPermission) {
                                        Toast.makeText(context, "Please allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                        return@Switch
                                    }
                                    if (!hasRecordAudioPermission) {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                                        action = AskScreenOverlayService.ACTION_START
                                    }
                                    ContextCompat.startForegroundService(context, intent)
                                    isServiceRunning = true
                                    prefs.isAssistantEnabled = true
                                } else {
                                    val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                                        action = AskScreenOverlayService.ACTION_STOP
                                    }
                                    context.stopService(intent)
                                    isServiceRunning = false
                                    prefs.isAssistantEnabled = false
                                }
                            },
                            modifier = Modifier.testTag("assistant_master_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ElectricCyan,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isServiceRunning)
                            "Assistant is running! Tap the floating bubble over any app (YouTube, Chrome, WhatsApp) to answer questions instantly."
                        else
                            "Turn on the assistant to display the floating AI bubble over any Android app.",
                        fontSize = 12.sp,
                        color = Slate300,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // PERMISSION CHECKLIST CARD
        Text(
            text = "SETUP & PERMISSIONS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Slate900,
            border = BorderStroke(1.dp, Slate800)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Overlay Permission
                PermissionItem(
                    title = "Display Over Other Apps",
                    subtitle = "Allows floating bubble & resizable answer window",
                    isGranted = hasOverlayPermission,
                    onGrantClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )

                HorizontalDivider(color = Slate800)

                // 2. Microphone Permission
                PermissionItem(
                    title = "Microphone & Audio",
                    subtitle = "For voice questions & listening to screen audio",
                    isGranted = hasRecordAudioPermission,
                    onGrantClick = {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    HorizontalDivider(color = Slate800)
                    // 3. Notification Permission
                    PermissionItem(
                        title = "Background Notifications",
                        subtitle = "Required to keep floating overlay running safely",
                        isGranted = hasNotificationPermission,
                        onGrantClick = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // QUICK ACTIONS & TEST HUB
        Text(
            text = "QUICK TEST & ACTIONS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionTile(
                modifier = Modifier.weight(1f),
                title = "Read Screen",
                subtitle = "Capture & Answer",
                icon = Icons.Default.CropFree,
                tint = ElectricCyan,
                onClick = {
                    if (!hasOverlayPermission) {
                        Toast.makeText(context, "Grant 'Display over other apps' first", Toast.LENGTH_SHORT).show()
                        return@QuickActionTile
                    }
                    if (!isServiceRunning) {
                        val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                            action = AskScreenOverlayService.ACTION_START
                        }
                        ContextCompat.startForegroundService(context, intent)
                        isServiceRunning = true
                    }
                    val captureIntent = ScreenCaptureActivity.createIntent(context, ScreenCaptureActivity.ACTION_START_SCREEN)
                    context.startActivity(captureIntent)
                }
            )

            QuickActionTile(
                modifier = Modifier.weight(1f),
                title = "Listen Screen",
                subtitle = "Speech & Audio",
                icon = Icons.Default.Headphones,
                tint = AmberWarning,
                onClick = {
                    if (!hasRecordAudioPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@QuickActionTile
                    }
                    if (!isServiceRunning) {
                        val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                            action = AskScreenOverlayService.ACTION_START
                        }
                        ContextCompat.startForegroundService(context, intent)
                        isServiceRunning = true
                    }
                    val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                        action = AskScreenOverlayService.ACTION_LISTEN_SCREEN
                    }
                    context.startService(intent)
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionTile(
                modifier = Modifier.weight(1f),
                title = "Read & Listen",
                subtitle = "For Video Classes",
                icon = Icons.Default.SmartDisplay,
                tint = VibrantPurple,
                onClick = {
                    if (!isServiceRunning) {
                        val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                            action = AskScreenOverlayService.ACTION_START
                        }
                        ContextCompat.startForegroundService(context, intent)
                        isServiceRunning = true
                    }
                    val captureIntent = ScreenCaptureActivity.createIntent(context, ScreenCaptureActivity.ACTION_START_BOTH)
                    context.startActivity(captureIntent)
                }
            )

            QuickActionTile(
                modifier = Modifier.weight(1f),
                title = "Upload Media",
                subtitle = "Photos, PDFs, Video",
                icon = Icons.Default.CloudUpload,
                tint = EmeraldGreen,
                onClick = onNavigateToUpload
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionTile(
                modifier = Modifier.weight(1f),
                title = "Speak to AI",
                subtitle = "Tap mic & ask aloud",
                icon = Icons.Default.Mic,
                tint = ElectricCyan,
                onClick = {
                    if (!hasRecordAudioPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@QuickActionTile
                    }
                    if (!isServiceRunning) {
                        val start = Intent(context, AskScreenOverlayService::class.java).apply {
                            action = AskScreenOverlayService.ACTION_START
                        }
                        ContextCompat.startForegroundService(context, start)
                        isServiceRunning = true
                    }
                    context.startService(Intent(context, AskScreenOverlayService::class.java).apply {
                        action = AskScreenOverlayService.ACTION_VOICE_INPUT
                    })
                }
            )
            QuickActionTile(
                modifier = Modifier.weight(1f),
                title = "Transcribe Audio",
                subtitle = "Transcript only — no answers",
                icon = Icons.Default.ClosedCaption,
                tint = EmeraldGreen,
                onClick = {
                    if (!hasRecordAudioPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@QuickActionTile
                    }
                    if (!isServiceRunning) {
                        val start = Intent(context, AskScreenOverlayService::class.java).apply {
                            action = AskScreenOverlayService.ACTION_START
                        }
                        ContextCompat.startForegroundService(context, start)
                        isServiceRunning = true
                    }
                    context.startService(Intent(context, AskScreenOverlayService::class.java).apply {
                        action = AskScreenOverlayService.ACTION_TRANSCRIBE_AUDIO
                    })
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SAMPLE TEST QUESTION (PRACTICE CANVAS)
        Text(
            text = "SAMPLE TEST CANVAS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )
        Text(
            text = "Try tapping the floating bubble right now to solve this sample question:",
            fontSize = 11.sp,
            color = Slate400
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Slate900,
            border = BorderStroke(1.dp, Slate700)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = ElectricCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "BIOLOGY & CHEMISTRY EXAM",
                            color = ElectricCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Question 14 of 50",
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Which organelle is known as the powerhouse of the cell, responsible for cellular respiration and adenosine triphosphate (ATP) production?",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                val options = listOf(
                    "A. Endoplasmic Reticulum",
                    "B. Mitochondria",
                    "C. Ribosome",
                    "D. Golgi Apparatus"
                )

                options.forEach { opt ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = Slate800,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = opt,
                            color = Slate300,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        if (!isServiceRunning) {
                            val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                                action = AskScreenOverlayService.ACTION_START
                            }
                            ContextCompat.startForegroundService(context, intent)
                            isServiceRunning = true
                        }
                        val captureIntent = ScreenCaptureActivity.createIntent(context, ScreenCaptureActivity.ACTION_START_SCREEN)
                        context.startActivity(captureIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("solve_sample_question_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Slate950,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Read & Solve This Question with AskScreen AI", color = Slate950, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun PermissionItem(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Slate400
            )
        }

        if (isGranted) {
            Surface(
                color = EmeraldBg,
                shape = CircleShape,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Granted",
                        tint = EmeraldGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Allow", fontSize = 11.sp, color = Slate950, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun QuickActionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = Slate900,
        border = BorderStroke(1.dp, Slate800)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = subtitle, fontSize = 11.sp, color = Slate400)
        }
    }
}
