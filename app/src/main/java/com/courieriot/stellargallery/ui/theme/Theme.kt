package com.courieriot.stellargallery.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

val Neon = Color(0xFF3CF2FF)
val Magenta = Color(0xFFFF3CAC)
val Violet = Color(0xFF8A5CFF)
val Space = Color(0xFF05060F)
val Panel = Color(0xFF0E1128)
val PanelHigh = Color(0xFF161A3A)
val TextHi = Color(0xFFE6E9FF)
val TextLo = Color(0xFFA9B0D6)

private val scheme = darkColorScheme(
    primary = Neon,
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFF0B3A44),
    onPrimaryContainer = Neon,
    secondary = Magenta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3D0F2A),
    onSecondaryContainer = Magenta,
    tertiary = Violet,
    background = Space,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextLo,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    surfaceContainerLow = Panel,
    outline = Color(0xFF3A4170),
    outlineVariant = Color(0xFF262B52),
)

@Composable
fun StellarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

private class Star(val x: Float, val y: Float, val r: Float, val a: Float)

/** Static starfield behind the app. */
@Composable
fun Starfield(modifier: Modifier = Modifier) {
    val stars = remember {
        val rnd = Random(42)
        List(150) { Star(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 1.4f + 0.3f, rnd.nextFloat() * 0.6f + 0.15f) }
    }
    Canvas(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF080A24), Space, Color(0xFF0D0420)))),
    ) {
        stars.forEach { s ->
            drawCircle(
                color = if (s.r > 1.5f) Neon.copy(alpha = s.a) else Color.White.copy(alpha = s.a),
                radius = s.r.dp.toPx(),
                center = Offset(s.x * size.width, s.y * size.height),
            )
        }
    }
}
