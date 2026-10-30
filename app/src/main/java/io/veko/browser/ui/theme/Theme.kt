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

// Bảng màu thương hiệu Veko gốc: Electric Cobalt (#3B5BFF), Privacy Mint (#00B894), Coral Pulse (#FF6B6B)
val VekoPrimaryBrand = Color(0xFF3B5BFF)
val VekoSecondaryBrand = Color(0xFF00B894)
val VekoTertiaryBrand = Color(0xFFFF6B6B)

private val VekoLightColorScheme = lightColorScheme(
    primary = VekoPrimaryBrand,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE2FF),
    onPrimaryContainer = Color(0xFF001258),
    secondary = VekoSecondaryBrand,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6F7EA),
    onSecondaryContainer = Color(0xFF002119),
    tertiary = VekoTertiaryBrand,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD8),
    onTertiaryContainer = Color(0xFF410006),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF141824),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF141824),
    surfaceVariant = Color(0xFFE2E6F3),
    onSurfaceVariant = Color(0xFF444857),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

// Tối ưu tương phản nền tối sâu cho màn hình OLED nhưng vẫn giữ đúng bản sắc sắc độ Veko
private val VekoDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF002388),
    primaryContainer = VekoPrimaryBrand,
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF56DFBE),
    onSecondary = Color(0xFF00382B),
    secondaryContainer = Color(0xFF005140),
    onSecondaryContainer = Color(0xFFC6F7EA),
    tertiary = Color(0xFFFFB3B0),
    onTertiary = Color(0xFF68000F),
    tertiaryContainer = Color(0xFF92001A),
    onTertiaryContainer = Color(0xFFFFDAD8),
    background = Color(0xFF0D111C),
    onBackground = Color(0xFFE4E7F4),
    surface = Color(0xFF0D111C),
    onSurface = Color(0xFFE4E7F4),
    surfaceVariant = Color(0xFF23293A),
    onSurfaceVariant = Color(0xFFC4C8D8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Theme gốc của Veko: ưu tiên Material You Dynamic Color trên Android 12+ và tự động lùi về bảng màu Veko chuẩn.
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
