package dev.khanlabs.dashcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// OLED dark is the only supported theme -- this is a tactical utility app,
// not a consumer app with a light mode.
private val KhanLabsDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    secondary = KhanLabsOrange,
    background = OledBlack,
    surface = SurfaceDark,
    onPrimary = OledBlack,
    onSecondary = OledBlack,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = WarningAmber
)

@Composable
fun KhanLabsDashcamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KhanLabsDarkColorScheme,
        typography = KhanLabsTypography,
        content = content
    )
}
