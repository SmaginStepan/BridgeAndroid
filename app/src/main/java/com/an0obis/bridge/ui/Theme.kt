package com.an0obis.bridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Felt-table inspired palette
val TableGreen = Color(0xFF1B5E20)
val TableGreenDark = Color(0xFF0D3311)
val AccentGold = Color(0xFFD4AF37)
val CardRed = Color(0xFFC62828)

private val BridgeColorScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = Color.Black,
    secondary = Color(0xFF81C784),
    background = Color(0xFF101010),
    surface = Color(0xFF1C1C1C),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // app is always dark-themed like the original
    MaterialTheme(
        colorScheme = BridgeColorScheme,
        content = content
    )
}
