package com.shsh.pleos.playground.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object PleosColors {
    val Background = Color(0xFF071014)
    val Panel = Color(0xFF0D181E)
    val Card = Color(0xFF132128)
    val Raised = Color(0xFF172831)
    val Border = Color(0xFF263940)
    val Text = Color(0xFFF2F7F6)
    val Muted = Color(0xFF8DA0A4)
    val Mint = Color(0xFF3DDCB4)
    val Green = Color(0xFF59D58E)
    val Amber = Color(0xFFF3B74D)
    val Recording = Color(0xFFF05C67)
}

private val PleosScheme = darkColorScheme(
    primary = PleosColors.Mint, onPrimary = PleosColors.Background,
    secondary = PleosColors.Amber, onSecondary = PleosColors.Background,
    background = PleosColors.Background, onBackground = PleosColors.Text,
    surface = PleosColors.Panel, onSurface = PleosColors.Text,
    surfaceVariant = PleosColors.Card, onSurfaceVariant = PleosColors.Muted,
    outline = PleosColors.Border, error = PleosColors.Amber,
    onError = PleosColors.Background
)

@Composable
fun PleosSdkPlaygroundTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PleosScheme, typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}
