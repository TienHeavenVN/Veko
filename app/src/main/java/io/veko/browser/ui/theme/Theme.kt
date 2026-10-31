package io.veko.browser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Bảng màu nhận diện Veko Horizon: Abyssal Navy (#08101E), Electric Cobalt (#2563EB) & Horizon Cyan (#06B6D4)
val VekoPrimaryBrand = Color(0xFF2563EB)
val VekoSecondaryBrand = Color(0xFF0891B2)
val VekoTertiaryBrand = Color(0xFF0D9488)
val VekoCyanAccent = Color(0xFF38BDF8)

private val VekoLightColorScheme = lightColorScheme(
    primary = VekoPrimaryBrand,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0A2558),
    secondary = VekoSecondaryBrand,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = VekoTertiaryBrand,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF042F2E),
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF0B1528),
    surface = Color(0xFFF4F7FC),
    onSurface = Color(0xFF0B1528),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF3FA),
    surfaceContainer = Color(0xFFE7EEF8),
    surfaceContainerHigh = Color(0xFFDEE7F4),
    surfaceVariant = Color(0xFFDCE5F2),
    onSurfaceVariant = Color(0xFF41506B),
    outlineVariant = Color(0xFFC8D4E6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

// Tối ưu tương phản nền Abyssal Navy cho chế độ tối, dịu mắt ban đêm và nổi bật khối thẻ Bento
private val VekoDarkColorScheme = darkColorScheme(
    primary = Color(0xFF82ACFF),
    onPrimary = Color(0xFF00297A),
    primaryContainer = Color(0xFF1B3B7B),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF003549),
    secondaryContainer = Color(0xFF0E3A53),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF11423E),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF08101E),
    onBackground = Color(0xFFE2E8F5),
    surface = Color(0xFF08101E),
    onSurface = Color(0xFFE2E8F5),
    surfaceContainerLowest = Color(0xFF060C17),
    surfaceContainerLow = Color(0xFF0D1729),
    surfaceContainer = Color(0xFF111E34),
    surfaceContainerHigh = Color(0xFF172742),
    surfaceVariant = Color(0xFF1D2E4A),
    onSurfaceVariant = Color(0xFFAAB8D0),
    outlineVariant = Color(0xFF263859),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Theme gốc của Veko: ưu tiên Material You Dynamic Color trên Android 12+ và tự động lùi về bảng màu Veko Horizon.
 */
@Composable
fun VekoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> VekoDarkColorScheme
        else -> VekoLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VekoTypography,
        content = content,
    )
}
