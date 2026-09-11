package com.watchagents.wa.ui.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Watch4 精简版主题。
 *
 * 配色对齐 WA 手机应用（Miuix / HyperOS 蓝）的深、浅两套，并支持在设置中显式切换：
 * 跟随系统 / 浅色 / 深色。
 *
 * 圆屏小字号需求：用户在真机反馈整体与文字过大，要求文字约为旧版二分之一，
 * 因此在 0.5f 基础上按用户反馈上调 10% 至 [WATCH_TEXT_RENDER_SCALE]（仍在设置中整体可调），
 * 通过本地 Density.fontScale 统一渲染文字
 * （仅影响 sp 字号，dp 布局尺寸不受影响，图标/按钮 dp 尺寸由各页面自行控制）。
 */
internal const val WATCH_TEXT_RENDER_SCALE = 0.55f

/** WA 深色（默认，省电、护眼）。 */
private val WatchDarkColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF0A3D6B),
    onPrimaryContainer = Color(0xFFD2E5FF),
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF233243),
    secondaryContainer = Color(0xFF2C4158),
    onSecondaryContainer = Color(0xFFD5E4F8),
    tertiary = Color(0xFF8FC8FF),
    onTertiary = Color(0xFF003352),
    background = Color(0xFF0E1217),
    onBackground = Color(0xFFE4E9F0),
    surface = Color(0xFF141A21),
    onSurface = Color(0xFFE4E9F0),
    surfaceVariant = Color(0xFF212A35),
    onSurfaceVariant = Color(0xFFBBC5D2),
    surfaceContainerHighest = Color(0xFF2A3440),
    outline = Color(0xFF4C5765),
    outlineVariant = Color(0xFF3A4450),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** WA 浅色。 */
private val WatchLightColors = lightColorScheme(
    primary = Color(0xFF0B74E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF0A3D6B),
    secondary = Color(0xFF41586E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC4DCFF),
    onSecondaryContainer = Color(0xFF22364A),
    tertiary = Color(0xFF00639F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF1A2028),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A2028),
    surfaceVariant = Color(0xFFE3EAF2),
    onSurfaceVariant = Color(0xFF414A56),
    surfaceContainerHighest = Color(0xFFD4DCE5),
    outline = Color(0xFF6F7984),
    outlineVariant = Color(0xFFB9C3CE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
internal fun WatchTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val platformDensity = LocalDensity.current
    val scaledDensity = remember(platformDensity) {
        Density(
            density = platformDensity.density,
            fontScale = platformDensity.fontScale * WATCH_TEXT_RENDER_SCALE,
        )
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = if (darkTheme) WatchDarkColors else WatchLightColors,
            content = content,
        )
    }
}
