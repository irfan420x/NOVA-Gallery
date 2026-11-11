package com.nova.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// NOVA brand colors, shared with NOVA Files / Dialer / Contacts.
val NovaBg = Color(0xFF0B0D17)
val NovaSurface = Color(0xFF151827)
val NovaCard = Color(0xFF1C2033)
val NovaAccent = Color(0xFF7C5CFF)
val NovaAccent2 = Color(0xFF00D4FF)
val NovaText = Color(0xFFF2F3F8)
val NovaMuted = Color(0xFF9AA0B5)
val NovaGood = Color(0xFF3ED598)
val NovaDanger = Color(0xFFFF5C5C)

private val NovaColors = darkColorScheme(
    primary = NovaAccent,
    secondary = NovaAccent2,
    background = NovaBg,
    surface = NovaSurface,
    surfaceVariant = NovaCard,
    onPrimary = Color.White,
    onBackground = NovaText,
    onSurface = NovaText,
    onSurfaceVariant = NovaMuted
)

private val NovaLightColors = lightColorScheme(
    primary = NovaAccent,
    secondary = Color(0xFF0088AA),
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF7),
    onPrimary = Color.White,
    onBackground = Color(0xFF12141F),
    onSurface = Color(0xFF12141F),
    onSurfaceVariant = Color(0xFF5A6076)
)

@Composable
fun NovaTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) NovaColors else NovaLightColors, content = content)
}
