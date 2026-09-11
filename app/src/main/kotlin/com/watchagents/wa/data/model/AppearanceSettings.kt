package com.watchagents.wa.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

@Serializable
data class AppearanceSettings(
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.SYSTEM,
    val monetEnabled: Boolean = false,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.TONAL_SPOT,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    /** 屏幕形状：手表有圆屏/方屏/长条屏多种，自动判定不准时可手动指定。 */
    val screenShape: WatchScreenShape = WatchScreenShape.AUTO,
    /** 表冠方向：各家手表"顺时针 = 往下滚"定义不统一，方向反了就切 [WatchRotaryDirection.REVERSED]。 */
    val rotaryDirection: WatchRotaryDirection = WatchRotaryDirection.STANDARD,
    /**
     * 把音量键也当表冠用。
     *
     * 部分品牌/ROM 把旋转表冠映射成音量键；打开会占用 App 前台时的音量键，
     * 所以默认关闭，只有"表冠转了没反应"的机型才需要开。
     */
    val volumeKeyRotary: Boolean = false,
) {
    fun normalized(): AppearanceSettings = copy(
        interfaceScale = normalizeInterfaceScale(interfaceScale),
    )
}

/** 表冠滚动方向。 */
@Serializable
enum class WatchRotaryDirection(val persistedValue: String) {
    STANDARD("standard"),
    REVERSED("reversed");

    companion object {
        fun fromPersistedValue(value: String?): WatchRotaryDirection =
            entries.firstOrNull { it.persistedValue == value } ?: STANDARD
    }
}

/**
 * 布局用的屏幕形状偏好。
 *
 * [AUTO] 以系统 `Configuration.isScreenRound` + 宽高比判定；判错时（例如个别国产手表
 * 兼容层不声明圆屏、或 1:1 方屏被当成圆屏）用户可手动锁定 [ROUND] / [SQUARE]。
 */
@Serializable
enum class WatchScreenShape(val persistedValue: String) {
    AUTO("auto"),
    ROUND("round"),
    SQUARE("square");

    companion object {
        fun fromPersistedValue(value: String?): WatchScreenShape =
            entries.firstOrNull { it.persistedValue == value } ?: AUTO
    }
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: TONAL_SPOT
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
