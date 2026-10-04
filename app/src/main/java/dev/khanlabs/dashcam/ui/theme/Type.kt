package dev.khanlabs.dashcam.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Sans-serif for UI labels/chrome; monospace reserved for telemetry values,
// timestamps and filenames so they read as "engineering terminal" data, per
// the KhanLabs brand spec.
val UiFontFamily = FontFamily.SansSerif
val TelemetryFontFamily = FontFamily.Monospace

val KhanLabsTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = UiFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = UiFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = UiFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = UiFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = TelemetryFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )
)
