package com.zhiwei.ffmpegx.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zhiwei.ffmpegx.core.settings.ThemeMode

// ---------------------------------------------------------------- 品牌色板 ----
// 工具类应用走「克制」路线：主色偏低饱和的蓝，强调色用青绿表示「硬件加速/成功」，
// 橙红留给告警。避免大面积高饱和色块干扰信息阅读。

private val Blue40 = Color(0xFF2F6BFF)
private val Blue80 = Color(0xFFAEC6FF)
private val BlueContainerLight = Color(0xFFDCE5FF)
private val BlueContainerDark = Color(0xFF11315F)

private val Teal40 = Color(0xFF00796B)
private val Teal80 = Color(0xFF7FD9CC)
private val TealContainerLight = Color(0xFFD0F1EB)
private val TealContainerDark = Color(0xFF00453C)

private val Orange40 = Color(0xFFB3531A)
private val Orange80 = Color(0xFFFFB786)
private val OrangeContainerLight = Color(0xFFFFE0CC)
private val OrangeContainerDark = Color(0xFF6B2E00)

private val Neutral10 = Color(0xFF0B1220)
private val Neutral99 = Color(0xFFFCFCFF)

private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = Color(0xFF001B4D),

    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = Color(0xFF00201A),

    tertiary = Orange40,
    onTertiary = Color.White,
    tertiaryContainer = OrangeContainerLight,
    onTertiaryContainer = Color(0xFF3A1400),

    background = Neutral99,
    onBackground = Color(0xFF1A1C1E),
    surface = Neutral99,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF002B75),
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = Color(0xFFDCE5FF),

    secondary = Teal80,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = Color(0xFFD0F1EB),

    tertiary = Orange80,
    onTertiary = Color(0xFF5C1A00),
    tertiaryContainer = OrangeContainerDark,
    onTertiaryContainer = Color(0xFFFFE0CC),

    background = Neutral10,
    onBackground = Color(0xFFE3E2E6),
    surface = Neutral10,
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** 等宽字体用于命令行与日志展示 */
val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun FFmpegXTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
