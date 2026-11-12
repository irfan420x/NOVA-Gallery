package com.nova.gallery.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// NOVA Nebula — Dark Premium design tokens (shared across all four NOVA
// apps; never change a token value per app). Dark is the default theme.
val NovaBg = Color(0xFF0A0C1C)
val NovaSurface = Color(0xFF14172B)
val NovaCard = Color(0xFF1B1F38)
val NovaOutline = Color(0x14FFFFFF)
val NovaAccent = Color(0xFF8B5CF6)
val NovaAccent2 = Color(0xFF22D3EE)
val NovaPink = Color(0xFFEC4899)
val NovaText = Color(0xFFEDEFF7)
val NovaMuted = Color(0xFF9AA3C7)
val NovaGood = Color(0xFF34D399)
val NovaDanger = Color(0xFFF87171)
val NovaGradientStart = Color(0xFF7C3AED)
val NovaGradientEnd = Color(0xFF3B82F6)

// Primary gradient: FABs, primary buttons, active tab pills, hero accents.
val NovaGradientBrush = Brush.horizontalGradient(listOf(NovaGradientStart, NovaGradientEnd))

// v1.8 shapes: cards 20dp, buttons 14dp, chips/pills 12dp.
private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp)
)

private val NovaColors = darkColorScheme(
    primary = NovaAccent,
    secondary = NovaAccent2,
    tertiary = NovaPink,
    background = NovaBg,
    surface = NovaSurface,
    surfaceVariant = NovaCard,
    onPrimary = Color.White,
    onBackground = NovaText,
    onSurface = NovaText,
    onSurfaceVariant = NovaMuted,
    error = NovaDanger,
    outline = NovaOutline
)

private val NovaLightColors = lightColorScheme(
    primary = NovaAccent,
    secondary = NovaAccent2,
    tertiary = NovaPink,
    background = Color(0xFFF4F5FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF7),
    onPrimary = Color.White,
    onBackground = Color(0xFF12141F),
    onSurface = Color(0xFF12141F),
    onSurfaceVariant = Color(0xFF5A6076),
    error = Color(0xFFD92D20),
    outline = Color(0x1F12141F)
)

@Composable
fun NovaTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NovaColors else NovaLightColors,
        shapes = NovaShapes,
        content = content
    )
}
