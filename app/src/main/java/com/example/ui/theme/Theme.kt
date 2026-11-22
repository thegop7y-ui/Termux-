package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TermuxDarkColorScheme = darkColorScheme(
    primary = TermuxEmerald,
    onPrimary = Color(0xFF002110),
    primaryContainer = Color(0xFF004D27),
    onPrimaryContainer = Color(0xFF8CFFBA),
    secondary = TermuxCyan,
    onSecondary = Color(0xFF002B3D),
    secondaryContainer = Color(0xFF10384B),
    onSecondaryContainer = Color(0xFFBFE9FF),
    tertiary = TermuxAmber,
    background = TermuxObsidian,
    onBackground = TermuxTextPrimary,
    surface = TermuxSurface,
    onSurface = TermuxTextPrimary,
    surfaceVariant = TermuxSurfaceElevated,
    onSurfaceVariant = TermuxTextMuted,
    outline = TermuxBorder,
    error = TermuxRed
)

@Composable
fun TermuxCppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TermuxDarkColorScheme,
        typography = Typography,
        content = content
    )
}
