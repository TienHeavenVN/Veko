package io.veko.browser.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Bảng màu nhận diện Veko Horizon: nền xanh đen Abyssal Navy (#070E1A), Cyan nhấn (#38BDF8) & Electric Cobalt (#2563EB)
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

// Tối ưu tương phản nền xanh đen (Abyssal Navy) làm chủ đạo với điểm nhấn Cyan/Blue hiện đại
private val VekoDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF002B42),
    primaryContainer = Color(0xFF0E3958),
    onPrimaryContainer = Color(0xFFD8F3FF),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF002859),
    secondaryContainer = Color(0xFF143258),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF0F3F3C),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF070E1A),
    onBackground = Color(0xFFE6EEF9),
    surface = Color(0xFF091220),
    onSurface = Color(0xFFE6EEF9),
    surfaceContainerLowest = Color(0xFF050A13),
    surfaceContainerLow = Color(0xFF0C1627),
    surfaceContainer = Color(0xFF101D33),
    surfaceContainerHigh = Color(0xFF162742),
    surfaceVariant = Color(0xFF1C2F4E),
    onSurfaceVariant = Color(0xFF9FB0CA),
    outlineVariant = Color(0xFF253A5E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Theme nhận diện mặc định của Veko: Dark theme nền xanh đen + điểm nhấn Cyan/Blue; tùy chọn Material You khi người dùng bật.
 */
@Composable
fun VekoTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
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
