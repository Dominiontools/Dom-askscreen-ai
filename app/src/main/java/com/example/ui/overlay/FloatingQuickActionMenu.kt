package com.example.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FloatingQuickActionMenu(
    onReadScreen: () -> Unit,
    onListenScreen: () -> Unit,
    onReadAndListen: () -> Unit,
    onSelectRegion: () -> Unit,
    onOpenMainApp: () -> Unit,
    onHideBubble: () -> Unit,
    onStopAssistant: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(230.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
        color = Color(0xFF0F172A).copy(alpha = 0.98f),
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Actions",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            MenuItem(
                icon = Icons.Default.CropFree,
                title = "Read Screen",
                subtitle = "Detect question on display",
                iconTint = Color(0xFF38BDF8),
                onClick = onReadScreen
            )
            MenuItem(
                icon = Icons.Default.Headphones,
                title = "Listen Screen",
                subtitle = "Capture audio & speech",
                iconTint = Color(0xFFF59E0B),
                onClick = onListenScreen
            )
            MenuItem(
                icon = Icons.Default.SmartDisplay,
                title = "Read & Listen",
                subtitle = "Video lessons & classes",
                iconTint = Color(0xFFA855F7),
                onClick = onReadAndListen
            )
            MenuItem(
                icon = Icons.Default.HighlightAlt,
                title = "Select Region",
                subtitle = "Crop specific question",
                iconTint = Color(0xFF34D399),
                onClick = onSelectRegion
            )
            MenuItem(
                icon = Icons.Default.Home,
                title = "Open App Hub",
                subtitle = "History, settings & uploads",
                iconTint = Color(0xFF60A5FA),
                onClick = onOpenMainApp
            )

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF1E293B))
            )

            MenuItem(
                icon = Icons.Default.VisibilityOff,
                title = "Hide Bubble",
                subtitle = "Restore from notification",
                iconTint = Color(0xFF94A3B8),
                onClick = onHideBubble
            )
            MenuItem(
                icon = Icons.Default.PowerSettingsNew,
                title = "Stop Assistant",
                subtitle = "Turn off overlay service",
                iconTint = Color(0xFFEF4444),
                onClick = onStopAssistant
            )
        }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = Color(0xFF64748B),
                fontSize = 10.sp
            )
        }
    }
}
