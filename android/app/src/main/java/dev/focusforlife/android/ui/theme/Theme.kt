package dev.focusforlife.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = InkDeep,
    primaryContainer = BrandTealDark,
    onPrimaryContainer = Cream,
    secondary = BrandTealLight,
    onSecondary = InkDeep,
    secondaryContainer = InkRaised,
    onSecondaryContainer = Cream,
    tertiary = WarnAmber,
    onTertiary = InkDeep,
    tertiaryContainer = WarnContainer,
    onTertiaryContainer = Cream,
    error = DangerRed,
    onError = Cream,
    errorContainer = DangerContainer,
    onErrorContainer = Cream,
    background = InkDeep,
    onBackground = Cream,
    surface = Ink,
    onSurface = Cream,
    surfaceVariant = InkRaised,
    onSurfaceVariant = MutedText,
    outline = InkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = BrandOrangeDeep,
    onPrimary = Cream,
    primaryContainer = BrandTeal,
    onPrimaryContainer = Cream,
    secondary = BrandTeal,
    onSecondary = Cream,
    tertiary = BrandOrange,
    error = DangerRed,
    background = Cream,
    onBackground = BrandTealDark,
    surface = Color(0xFFFFFDF8),
    onSurface = BrandTealDark,
    surfaceVariant = Color(0xFFE8E0CF),
    onSurfaceVariant = Color(0xFF50606A),
    outline = Color(0xFFB9AE97)
)

@Composable
fun FocusForLifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Dynamic (wallpaper) color is intentionally disabled: the app always wears
    // the FocusForLife shield colors.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
