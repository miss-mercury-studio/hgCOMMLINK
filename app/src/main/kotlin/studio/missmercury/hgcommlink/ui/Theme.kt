package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.missmercury.hgcommlink.R

// Palette: a dark cyberdeck. Green is "active / good", magenta is magic and matrix, amber is a warning, red is wrong.
val Obsidian = Color(0xFF0B0E14)        // screen background
val MercurySlate = Color(0xFF151B23)    // cards, panels
val SlateHigh = Color(0xFF1C2430)       // inputs, raised cells
val TerminalGrid = Color(0xFF26303D)    // hairlines, inactive segments
val MutedSilver = Color(0xFF8B949E)     // secondary text
val Quicksilver = Color(0xFFD0D7DE)     // primary text
val PhosphorGreen = Color(0xFF00E676)
val AstralMagenta = Color(0xFFE040FB)
val DrainAmber = Color(0xFFFFB300)
val CriticalRed = Color(0xFFFF3D00)
val DataBlue = Color(0xFF40C4FF)        // game data the app cannot place (custom)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 500), variable(R.font.space_grotesk, 700),
)
val InterUi = FontFamily(
    variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600),
)
val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono, 400), variable(R.font.jetbrains_mono, 500), variable(R.font.jetbrains_mono, 700),
)

/** Everything is monospaced, like a terminal. Space Grotesk survives only for the logo wordmark. */
private val HgTypography = Typography(
    displaySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 17.sp, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFeatureSettings = "tnum"),
    titleSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.8.sp, fontFeatureSettings = "tnum"),
    bodyLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp, fontFeatureSettings = "tnum"),
)

/** Sharp corners everywhere: boxes, not cards. */
private val SharpShape = RoundedCornerShape(0.dp)
private val HgShapes = Shapes(
    extraSmall = SharpShape, small = SharpShape, medium = SharpShape, large = SharpShape, extraLarge = SharpShape,
)

private val HgColors = darkColorScheme(
    primary = PhosphorGreen, onPrimary = Obsidian,
    secondary = AstralMagenta, onSecondary = Obsidian,
    tertiary = DrainAmber, onTertiary = Obsidian,
    background = Obsidian, onBackground = Quicksilver,
    surface = Obsidian, onSurface = Quicksilver,
    surfaceVariant = SlateHigh, onSurfaceVariant = MutedSilver,
    surfaceContainerLowest = Obsidian, surfaceContainerLow = MercurySlate, surfaceContainer = MercurySlate,
    surfaceContainerHigh = SlateHigh, surfaceContainerHighest = SlateHigh,
    outline = MutedSilver, outlineVariant = TerminalGrid,
    error = CriticalRed, onError = Quicksilver,
    primaryContainer = PhosphorGreen.copy(alpha = 0.18f), onPrimaryContainer = PhosphorGreen,
    inverseSurface = Quicksilver, inverseOnSurface = Obsidian,
)

/** The one look of the app: always dark, regardless of the phone's setting. */
@Composable
fun HgTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HgColors, typography = HgTypography, shapes = HgShapes, content = content)
}
