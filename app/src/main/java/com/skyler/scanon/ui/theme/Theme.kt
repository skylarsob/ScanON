package com.skyler.scanon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.skyler.scanon.data.AppSettings
import com.skyler.scanon.data.AppearancePreset
import com.skyler.scanon.data.DensityMode
import com.skyler.scanon.data.ThemeMode

val Teal = Color(0xFF2DD4BF)
val Sky = Color(0xFF38BDF8)
val Violet = Color(0xFFA78BFA)
val Good = Color(0xFF43A047)
val Bad = Color(0xFFE53935)
val Warn = Color(0xFFFB8C00)
val Amber = Color(0xFFF9A825)
val Neutral = Color(0xFF9E9E9E)

val ScanonDark: ColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0B3B36),
    onPrimaryContainer = Color(0xFF9FF5E6),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0A1815),
    surface = Color(0xFF10201B),
    onSurface = Color(0xFFE6F4F0),
    surfaceVariant = Color(0xFF1B2E28),
    onSurfaceVariant = Color(0xFFA7C4BB),
)

val ScanonLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF0284C7),
    background = Color(0xFFEAF6F3),
    surface = Color.White,
    surfaceVariant = Color(0xFFDFF2EC),
)

/** Obsidian Dark: deep carbon + electric cyan (design brief). */
val ObsidianDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF04222B),
    primaryContainer = Color(0xFF0B2A38),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = Color(0xFF818CF8),
    background = Color(0xFF04070A),
    surface = Color(0xFF0A1016),
    onSurface = Color(0xFFE9F5FA),
    surfaceVariant = Color(0xFF131C24),
    onSurfaceVariant = Color(0xFF9DB4C0),
)

/** Tokyo Night: indigo + neon magenta (design brief). */
val TokyoDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFF471B5),
    onPrimary = Color(0xFF38091F),
    primaryContainer = Color(0xFF33122B),
    onPrimaryContainer = Color(0xFFFBCFE8),
    secondary = Color(0xFFA78BFA),
    background = Color(0xFF10101C),
    surface = Color(0xFF181826),
    onSurface = Color(0xFFEDEDF7),
    surfaceVariant = Color(0xFF23233A),
    onSurfaceVariant = Color(0xFFAAAAC6),
)

/** Matrix Phosphor: monochrome emerald on near-black green (design brief). */
val MatrixDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF3DFF88),
    onPrimary = Color(0xFF02120A),
    primaryContainer = Color(0xFF0A2E1A),
    onPrimaryContainer = Color(0xFFB4FFCE),
    secondary = Color(0xFF22C55E),
    background = Color(0xFF020503),
    surface = Color(0xFF050D08),
    onSurface = Color(0xFFDCFCE7),
    surfaceVariant = Color(0xFF0B1F12),
    onSurfaceVariant = Color(0xFF8FBF9F),
)

/** Frosted Daylight: clean alabaster + cobalt (design brief). */
val FrostedLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E5FC),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0891B2),
    background = Color(0xFFF1F4F7),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2EAF1),
    onSurfaceVariant = Color(0xFF5A6B7C),
)

/** Accent swatches for the customizer; stored as ARGB in settings. */
val AccentSwatches: List<Color> = listOf(
    Color(0xFF22D3EE), Color(0xFF2DD4BF), Color(0xFFF471B5), Color(0xFF3DFF88),
    Color(0xFF2563EB), Color(0xFFF59E0B), Color(0xFFA78BFA), Color(0xFFEF4444),
)

private fun ColorScheme.withAccent(accent: Color): ColorScheme = copy(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.45f) Color(0xFF0B1016) else Color.White,
    primaryContainer = lerp(background, accent, 0.30f),
    onPrimaryContainer = lerp(onSurface, accent, 0.18f),
)

/** Whether the app is currently drawn dark (used by the animated background). */
val LocalIsDark = staticCompositionLocalOf { true }
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Glass intensity 0..1 from the appearance settings (panel translucency + glow strength). */
val LocalGlass = staticCompositionLocalOf { 0.6f }

/** Corner-radius multiplier from the appearance settings. */
val LocalRadiusScale = staticCompositionLocalOf { 1.0f }

/** Spacing multiplier derived from the density mode. */
val LocalSpacingScale = staticCompositionLocalOf { 1.0f }

private fun typographyWith(family: FontFamily): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = family), displayMedium = b.displayMedium.copy(fontFamily = family),
        displaySmall = b.displaySmall.copy(fontFamily = family), headlineLarge = b.headlineLarge.copy(fontFamily = family),
        headlineMedium = b.headlineMedium.copy(fontFamily = family), headlineSmall = b.headlineSmall.copy(fontFamily = family),
        titleLarge = b.titleLarge.copy(fontFamily = family), titleMedium = b.titleMedium.copy(fontFamily = family),
        titleSmall = b.titleSmall.copy(fontFamily = family), bodyLarge = b.bodyLarge.copy(fontFamily = family),
        bodyMedium = b.bodyMedium.copy(fontFamily = family), bodySmall = b.bodySmall.copy(fontFamily = family),
        labelLarge = b.labelLarge.copy(fontFamily = family), labelMedium = b.labelMedium.copy(fontFamily = family),
        labelSmall = b.labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun ScanonTheme(settings: AppSettings, persian: Boolean, systemAnimationsOff: Boolean, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val classicDark = when (settings.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val (base, dark) = when (settings.preset) {
        AppearancePreset.CLASSIC -> (if (classicDark) ScanonDark else ScanonLight) to classicDark
        AppearancePreset.OBSIDIAN -> ObsidianDark to true
        AppearancePreset.TOKYO -> TokyoDark to true
        AppearancePreset.MATRIX -> MatrixDark to true
        AppearancePreset.FROSTED -> FrostedLight to false
    }
    val scheme = if (settings.accentArgb != 0) base.withAccent(Color(settings.accentArgb)) else base
    val typo = if (persian) typographyWith(VazirFamily) else Typography()
    // Rounder shape scale (2.9.1): chips, cards, dialogs, menus and sheets all
    // take their corners from here, so the whole UI shares the fields' and
    // buttons' softer radius instead of Material's tight defaults.
    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    )
    MaterialTheme(colorScheme = scheme, typography = typo, shapes = shapes) {
        val baseDensity = LocalDensity.current
        val density = when (settings.densityMode) {
            DensityMode.COMPACT -> Density(baseDensity.density * 0.95f, baseDensity.fontScale * 0.82f)
            DensityMode.COMFORTABLE -> baseDensity
            DensityMode.SPACIOUS -> Density(baseDensity.density, baseDensity.fontScale * 1.06f)
        }
        val spacing = when (settings.densityMode) {
            DensityMode.COMPACT -> 0.85f
            DensityMode.COMFORTABLE -> 1.0f
            DensityMode.SPACIOUS -> 1.25f
        }
        CompositionLocalProvider(
            // Default text colour for the whole app (2.8.5 fix). Material3 does NOT
            // set one globally and GlassCard is a plain Box, so every Text without an
            // explicit colour fell back to LocalContentColor's default — black —
            // and vanished on the dark themes (Skylar's report; visible in her
            // onboarding screenshot, where bare titles were black but explicitly
            // coloured descriptions were fine). onSurface is the scheme's own
            // readable-on-background colour in every preset, light and dark.
            LocalContentColor provides scheme.onSurface,
            LocalDensity provides density,
            LocalIsDark provides dark,
            LocalReduceMotion provides (settings.reduceMotion || systemAnimationsOff),
            LocalGlass provides settings.glass,
            LocalRadiusScale provides settings.radiusScale,
            LocalSpacingScale provides spacing,
            content = content,
        )
    }
}

/** Vazirmatn (bundled) for Persian text. */
val VazirFamily: FontFamily = FontFamily(Font(com.skyler.scanon.R.font.vazirmatn))
