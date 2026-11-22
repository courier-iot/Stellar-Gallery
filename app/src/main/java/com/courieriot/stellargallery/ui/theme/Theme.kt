package com.courieriot.stellargallery.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

enum class Accent(val label: String, val color: Color) {
    CYAN("Neon cyan", Color(0xFF3CF2FF)),
    MAGENTA("Nebula magenta", Color(0xFFFF3CAC)),
    VIOLET("Void violet", Color(0xFFA67CFF)),
    LIME("Acid lime", Color(0xFFB6FF3C)),
    AMBER("Solar amber", Color(0xFFFFB13C)),
    ICE("Comet ice", Color(0xFFBFE9FF)),
}

// Theme colors are snapshot state so changing the accent recolors the whole app instantly.
var Neon by mutableStateOf(Accent.CYAN.color)
    private set
var Space by mutableStateOf(Color(0xFF05060F))
    private set
var Panel by mutableStateOf(Color(0xFF0E1128))
    private set
var PanelHigh by mutableStateOf(Color(0xFF161A3A))
    private set
var PureBlack by mutableStateOf(false)
    private set

val Magenta = Color(0xFFFF3CAC)
val Violet = Color(0xFF8A5CFF)
val TextHi = Color(0xFFE6E9FF)
val TextLo = Color(0xFFA9B0D6)

object ThemeState {
    fun apply(accent: Accent, pureBlack: Boolean) {
        Neon = accent.color
        PureBlack = pureBlack
        Space = if (pureBlack) Color.Black else Color(0xFF05060F)
        Panel = if (pureBlack) Color(0xFF0B0B10) else Color(0xFF0E1128)
        PanelHigh = if (pureBlack) Color(0xFF16161E) else Color(0xFF161A3A)
    }
}

@Composable
fun StellarTheme(content: @Composable () -> Unit) {
    val primary = Neon
    val space = Space
    val panel = Panel
    val panelHigh = PanelHigh
    val scheme = remember(primary, space, panel, panelHigh) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF00161A),
            primaryContainer = primary.copy(alpha = 0.22f),
            onPrimaryContainer = primary,
            secondary = Magenta,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF3D0F2A),
            onSecondaryContainer = Magenta,
            tertiary = Violet,
            background = space,
            onBackground = TextHi,
            surface = panel,
            onSurface = TextHi,
            surfaceVariant = panelHigh,
            onSurfaceVariant = TextLo,
            surfaceContainer = panel,
            surfaceContainerHigh = panelHigh,
            surfaceContainerHighest = panelHigh,
            surfaceContainerLow = panel,
            outline = Color(0xFF3A4170),
            outlineVariant = Color(0xFF262B52),
        )
    }
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
    val black = PureBlack
    val accent = Neon
    val bg = if (black) {
        Brush.verticalGradient(listOf(Color.Black, Color.Black))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF080A24), Space, Color(0xFF0D0420)))
    }
    Canvas(modifier.fillMaxSize().background(bg)) {
        stars.forEach { s ->
            val alpha = if (black) s.a * 0.5f else s.a
            drawCircle(
                color = if (s.r > 1.5f) accent.copy(alpha = alpha) else Color.White.copy(alpha = alpha),
                radius = s.r.dp.toPx(),
                center = Offset(s.x * size.width, s.y * size.height),
            )
        }
    }
}
