package com.vela.android.lab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// VELA cockpit palette from docs/vela-android-cockpit-ux-spec.md — cool dark navy + mint accent.
// Applied at Material 3 semantic tokens so every downstream composable inherits automatically.
private val VelaDarkColors = darkColorScheme(
    primary = Color(0xFF2DE2B7),
    onPrimary = Color(0xFF04101A),
    primaryContainer = Color(0xFF195A59),
    onPrimaryContainer = Color(0xFFE6FAFF),
    secondary = Color(0xFF87AFC0),
    onSecondary = Color(0xFF04101A),
    secondaryContainer = Color(0xFF0E2232),
    onSecondaryContainer = Color(0xFFE6FAFF),
    tertiary = Color(0xFFFFD7AC),
    onTertiary = Color(0xFF04101A),
    surface = Color(0xFF0B1A28),
    onSurface = Color(0xFFE6FAFF),
    surfaceVariant = Color(0xFF0E2232),
    onSurfaceVariant = Color(0xFF87AFC0),
    background = Color(0xFF04101A),
    onBackground = Color(0xFFE6FAFF),
    outline = Color(0xFF1E4D63),
    outlineVariant = Color(0xFF1B4255),
    error = Color(0xFFD76A76),
    onError = Color(0xFF04101A),
    errorContainer = Color(0xFF331B1F),
    onErrorContainer = Color(0xFFFFB4BA),
)

// Light scheme kept only for @Preview support; the app forces dark in practice.
private val VelaLightColors = lightColorScheme(
    primary = Color(0xFF195A59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F1E1),
    onPrimaryContainer = Color(0xFF04101A),
    secondary = Color(0xFF4F6377),
    onSecondary = Color.White,
    surface = Color(0xFFF3F7F9),
    onSurface = Color(0xFF04101A),
    background = Color(0xFFF3F7F9),
    onBackground = Color(0xFF04101A),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val VelaTypography: Typography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun VelaLabTheme(
    // The VELA cockpit is a dark trading surface (matches Vela.WinUI's
    // `RequestedTheme="Dark"` explicit override). System dark-mode changes do not
    // alter the palette here; this is intentional and documented in
    // docs/vela-android-cockpit-ux-spec.md §A.2.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = VelaDarkColors,
        typography = VelaTypography,
        content = {
            CompositionLocalProvider(LocalVelaColors provides VelaExtendedColors.forScheme(dark = true)) {
                content()
            }
        },
    )
}
