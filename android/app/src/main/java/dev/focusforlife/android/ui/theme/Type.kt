package dev.focusforlife.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.focusforlife.android.R

// Urbanist variable font; each Font entry pins a weight on the wght axis.
val Urbanist = FontFamily(
    Font(R.font.urbanist, weight = FontWeight.Normal),
    Font(R.font.urbanist, weight = FontWeight.Medium),
    Font(R.font.urbanist, weight = FontWeight.SemiBold),
    Font(R.font.urbanist, weight = FontWeight.Bold),
    Font(R.font.urbanist, weight = FontWeight.ExtraBold)
)

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Urbanist),
    displayMedium = base.displayMedium.copy(fontFamily = Urbanist),
    displaySmall = base.displaySmall.copy(fontFamily = Urbanist),
    headlineLarge = base.headlineLarge.copy(fontFamily = Urbanist),
    headlineMedium = base.headlineMedium.copy(fontFamily = Urbanist),
    headlineSmall = base.headlineSmall.copy(fontFamily = Urbanist),
    titleLarge = base.titleLarge.copy(fontFamily = Urbanist),
    titleMedium = base.titleMedium.copy(fontFamily = Urbanist),
    titleSmall = base.titleSmall.copy(fontFamily = Urbanist),
    bodyLarge = base.bodyLarge.copy(fontFamily = Urbanist),
    bodyMedium = base.bodyMedium.copy(fontFamily = Urbanist),
    bodySmall = base.bodySmall.copy(fontFamily = Urbanist),
    labelLarge = base.labelLarge.copy(fontFamily = Urbanist),
    labelMedium = base.labelMedium.copy(fontFamily = Urbanist),
    labelSmall = base.labelSmall.copy(fontFamily = Urbanist)
)
