package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AskScreenDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Slate950,
    primaryContainer = DeepIndigo,
    onPrimaryContainer = ElectricCyan,
    secondary = VibrantPurple,
    onSecondary = Color.White,
    secondaryContainer = Slate800,
    onSecondaryContainer = Color.White,
    background = Slate950,
    onBackground = Color.White,
    surface = Slate900,
    onSurface = Color.White,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    outline = Slate700,
    error = CrimsonRed,
    onError = Color.White
)

@Composable
fun AskScreenTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AskScreenDarkColorScheme,
        typography = Typography,
        content = content
    )
}
