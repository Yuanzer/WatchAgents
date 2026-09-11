package com.watchagents.wa.ui.watch

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.staticCompositionLocalOf
import com.watchagents.wa.data.model.WatchScreenShape
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 圆屏内切圆几何（窗口为近似正方形，内切圆即可见圆形区域）。
 *
 * 沿 45° 方向，内切圆边界到窗口角部的轴向距离为：
 *   inset = side/2 * (1 - 1/√2) ≈ side * 0.14645
 *
 * 顶部两个悬浮按钮的摆位：外角贴在内切圆内的"安全圆"上（半径 = R - [WatchCircleSafeMargin]），
 * 方向由 [WATCH_CORNER_HUG_ANGLE_DEG] 决定（40°）。既不会被圆弧切掉，也尽量贴左右边缘；
 * 内容上边距直接从"按钮底边 + [WatchCornerContentGap]"起算，把高度全部让给对话。
 *
 * --- 跨机型适配（3.3）---
 * 1. **屏幕形状**：真圆屏才用上面的内切圆几何；明显长方形的手表（OPPO Watch、小米手表等
 *    1.78"~2.0" 长方屏）改用贴边几何，否则上下各要白留 30%~40% 高度，列表只剩一条缝。
 *    1:1 屏且系统没声明圆形时保持圆屏几何（宁可有留白，也不能把角部按钮裁掉），
 *    用户可在设置 → 外观 → 屏幕形状里手动指定。
 * 2. **屏幕尺寸**：按钮/FAB 尺寸按屏宽相对 [WATCH_REFERENCE_SIDE_DP] 等比缩放，
 *    该基准值取 Watch4 的真机表现（466px ÷ 2.04 ≈ 228dp）：在 Watch4 上是 34dp 按钮 / 44dp FAB，
 *    在 Samsung、Pixel 等 ~198dp 屏上自动缩小，避免控件占满圆屏。
 * 3. **贴边与对话空间**：3.2 用固定"内收 14dp / 抬升 10dp"，只用到安全圆的约 98%，
 *    且内容上边距从内切圆边距起算，白留了约 16dp。现在按安全圆精确摆位，
 *    Watch4 上按钮离左右边缘近了约 7dp、对话区多出约 9dp。
 */
internal fun watchCornerInset(side: Dp): Dp = side * ((1f - 1f / sqrt(2f)) / 2f)

/** 可用圆形直径取宽高较小者。 */
internal val BoxWithConstraintsScope.watchCircleSide: Dp
    get() = if (maxWidth < maxHeight) maxWidth else maxHeight

// ---------------------------------------------------------------------------
// 屏幕形状（圆 / 方）判定
// ---------------------------------------------------------------------------

/** 布局使用的屏幕形状。 */
internal enum class WatchScreenKind {
    ROUND,
    SQUARE,
}

/**
 * 用户设置的屏幕形状偏好（AUTO/ROUND/SQUARE），由 MainActivity 从设置读出后下发。
 * 真正生效的 [WatchScreenKind] 在 [watchCircleOffsets] 里结合"系统圆屏标记 + 实测窗口尺寸"解析。
 */
internal val LocalWatchScreenShape = staticCompositionLocalOf { WatchScreenShape.AUTO }

/** 视觉基准屏宽：Watch4 466px ÷ 2.04 ≈ 228dp（3.2 的尺寸都在这个屏上标定）。 */
private const val WATCH_REFERENCE_SIDE_DP = 228f

/** 长宽比超过该阈值才算"真长方形屏"（接近 1:1 的都保守按圆屏处理）。 */
private const val WATCH_SQUARE_RATIO_THRESHOLD = 1.08f

/**
 * 解析布局形状（纯函数，便于回归测试）：
 * - 用户显式指定圆形/方形时以用户为准；
 * - AUTO：系统声明圆形 → 圆形；窗口明显不是 1:1（真长方形屏）→ 方形；
 *   其余（含 1:1 方屏与"圆屏被兼容层误报成非圆屏"）→ 保守沿用圆形几何：
 *   圆屏几何用在方屏上只是上下留白，反过来会把角部按钮切到圆外。
 *   1:1 方屏用户可在 设置 → 外观 → 屏幕形状 手动锁定"方形"。
 *
 * 尺寸用**实测窗口**（edge-to-edge 下等于整屏），不用 `Configuration.screenWidthDp/HeightDp`：
 * 后者会扣掉系统栏，圆屏手表一旦有系统栏就会被误判成长方形。
 */
internal fun resolveWatchScreenKind(
    shape: WatchScreenShape,
    windowWidth: Dp,
    windowHeight: Dp,
    systemIsRound: Boolean,
): WatchScreenKind = when (shape) {
    WatchScreenShape.ROUND -> WatchScreenKind.ROUND
    WatchScreenShape.SQUARE -> WatchScreenKind.SQUARE
    WatchScreenShape.AUTO -> when {
        systemIsRound -> WatchScreenKind.ROUND
        isClearlyRectangular(windowWidth, windowHeight) -> WatchScreenKind.SQUARE
        else -> WatchScreenKind.ROUND
    }
}

/** 窗口长宽比是否明显偏离 1:1。 */
internal fun isClearlyRectangular(width: Dp, height: Dp): Boolean {
    val shorter = minOf(width.value, height.value)
    val longer = maxOf(width.value, height.value)
    if (shorter <= 0f) return false
    return longer / shorter >= WATCH_SQUARE_RATIO_THRESHOLD
}

// ---------------------------------------------------------------------------
// 尺寸常量（基准：228dp 屏；其它屏按屏宽等比缩放）
// ---------------------------------------------------------------------------

/** 角部悬浮圆形按钮在基准屏上的尺寸。 */
internal val WatchCornerButtonSize: Dp = 34.dp

/** 内切圆与角部控件之间的安全余量（越小越贴边；0 会正好落在弧线上，不建议）。 */
internal val WatchCircleSafeMargin: Dp = 2.dp

/**
 * 顶角按钮"贴边角度"：把按钮外角放在内切圆内的安全圆上，角度自屏幕顶部量起。
 * 0° = 贴屏幕顶部中央，45° = 贴 45° 对角。
 *
 * 40° 是"更贴左右边缘"与"给对话留高度"的平衡点：角越小按钮越高（对话空间越大）
 * 但离左右边缘越远；角越大越贴边角，但按钮整体下移。
 * 真机上想再贴一点就调大这个值，想再给对话让高度就调小。
 */
private const val WATCH_CORNER_HUG_ANGLE_DEG = 40f

/** 角部按钮与其下方内容之间的最小间隙。 */
private val WatchCornerContentGap: Dp = 4.dp

/** 右下角 FAB 与其上方内容之间的最小间隙。 */
private val WatchFabContentGap: Dp = 12.dp

/** 新建会话 FAB 在基准屏上的直径。 */
internal val WatchFabSize: Dp = 44.dp

/** 方形/长方形屏只需躲开圆角，不需要内切圆内缩。 */
private val WatchSquareCornerInset: Dp = 6.dp

/** 按屏宽等比缩放一个尺寸，并夹在 [min]~[max] 之间（防止小屏控件小到点不中、大屏控件过大）。 */
internal fun watchScaledSize(side: Dp, fraction: Float, min: Dp, max: Dp): Dp {
    val target = side * fraction
    return when {
        target < min -> min
        target > max -> max
        else -> target
    }
}

/**
 * 说明：早期尝试过"按行实时位置自动收窄"(WatchCircleFitItem)，真机上列表行坐标
 * 与窗口内切圆坐标系存在系统级偏移，导致中部错收窄/设置空白，已整体移除。
 * 现在改为静态安全策略：各页列表内容只用固定边距避让弧区，
 * 行保持全宽全屏铺开（与 3.1 真机验收一致的方案），不做逐行动态变形。
 */
internal data class WatchCircleOffsets(
    val side: Dp,
    val kind: WatchScreenKind,
    /** 角部圆形按钮直径（按屏宽缩放后）。 */
    val buttonSize: Dp,
    /** 右下角 FAB 直径（按屏宽缩放后）。 */
    val fabSize: Dp,
    val topStart: Pair<Dp, Dp>,
    val topEndShift: Pair<Dp, Dp>,
    val bottomEndShift: Pair<Dp, Dp>,
    val contentTopPad: Dp,
    val contentBottomPad: Dp,
)

/**
 * 在 BoxWithConstraintsScope 内计算当前屏幕形状对应的角部偏移。
 *
 * @Composable：读取 [LocalWatchScreenShape]（用户设置）与系统圆屏标记；
 * 形状判定的尺寸用本作用域实测的 `maxWidth/maxHeight`（= 真实窗口，含系统栏区域）。
 */
@Composable
internal fun BoxWithConstraintsScope.watchCircleOffsets(): WatchCircleOffsets {
    val side = watchCircleSide
    val kind = resolveWatchScreenKind(
        shape = LocalWatchScreenShape.current,
        windowWidth = maxWidth,
        windowHeight = maxHeight,
        systemIsRound = LocalConfiguration.current.isScreenRound,
    )
    val buttonSize = watchScaledSize(
        side,
        WatchCornerButtonSize.value / WATCH_REFERENCE_SIDE_DP,
        min = 26.dp,
        max = 36.dp,
    )
    val fabSize = watchScaledSize(
        side,
        WatchFabSize.value / WATCH_REFERENCE_SIDE_DP,
        min = 32.dp,
        max = 46.dp,
    )
    return when (kind) {
        WatchScreenKind.ROUND -> watchRoundOffsets(side, buttonSize, fabSize)
        WatchScreenKind.SQUARE -> watchSquareOffsets(side, buttonSize, fabSize)
    }
}

/**
 * 圆屏几何（纯函数，便于回归测试）。
 *
 * 摆位规则：把按钮**外角**放在半径 `R - 安全余量` 的圆上，方向取
 * [WATCH_CORNER_HUG_ANGLE_DEG]。这样按钮既不越出弧线（不会被圆屏切掉），
 * 也不浪费空间——3.2 的固定"内收 14dp / 抬升 10dp"只用到安全圆的约 98%，
 * 且内容上边距是从内切圆边距起算，白留了约 16dp。
 * 现在内容上边距直接从"按钮底边 + 4dp"起算，对话区在 Watch4 上多出约 9dp。
 */
internal fun watchRoundOffsets(side: Dp, buttonSize: Dp, fabSize: Dp): WatchCircleOffsets {
    val radius = side / 2
    val safeRadius = radius - WatchCircleSafeMargin
    val angle = Math.toRadians(WATCH_CORNER_HUG_ANGLE_DEG.toDouble())
    val horizontal = (safeRadius.value * sin(angle).toFloat()).dp
    val vertical = (safeRadius.value * cos(angle).toFloat()).dp
    // 右下 FAB 独占一个角，45° 贴角最优
    val fabInset = radius - safeRadius * (1f / sqrt(2f))
    val topX = radius - horizontal
    val topY = radius - vertical
    return WatchCircleOffsets(
        side = side,
        kind = WatchScreenKind.ROUND,
        buttonSize = buttonSize,
        fabSize = fabSize,
        topStart = topX to topY,
        topEndShift = (-topX) to topY,
        bottomEndShift = (-fabInset) to (-fabInset),
        contentTopPad = topY + buttonSize + WatchCornerContentGap,
        contentBottomPad = fabInset + fabSize + WatchFabContentGap,
    )
}

/**
 * 方形 / 长条屏几何（纯函数）。
 *
 * 这类屏没有圆弧可见区，只需贴边并躲开圆角，因此不再使用内切圆内缩，
 * 内容上下安全距只等于"按钮 + 小间隙"。
 */
internal fun watchSquareOffsets(side: Dp, buttonSize: Dp, fabSize: Dp): WatchCircleOffsets {
    val inset = WatchSquareCornerInset
    return WatchCircleOffsets(
        side = side,
        kind = WatchScreenKind.SQUARE,
        buttonSize = buttonSize,
        fabSize = fabSize,
        topStart = (inset + 2.dp) to inset,
        topEndShift = (-(inset + 2.dp)) to inset,
        bottomEndShift = (-inset) to (-inset),
        contentTopPad = inset + buttonSize + 6.dp,
        contentBottomPad = inset + fabSize + 8.dp,
    )
}

// ---------------------------------------------------------------------------
// 液态玻璃（轻量实现）
// ---------------------------------------------------------------------------

@Composable
@ReadOnlyComposable
private fun watchIsDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 玻璃渐变填充：深浅色两套，接近 iOS 液态玻璃的半透明高光质感，无真实高斯模糊、性能友好。 */
@Composable
@ReadOnlyComposable
internal fun watchGlassFill(): Brush = if (watchIsDark()) {
    Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.17f),
            Color(0xFF4DA3FF).copy(alpha = 0.10f),
            Color.White.copy(alpha = 0.05f),
        ),
    )
} else {
    Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.96f),
            Color(0xFF4DA3FF).copy(alpha = 0.07f),
            Color.White.copy(alpha = 0.45f),
        ),
    )
}

/** 玻璃描边颜色。 */
@Composable
@ReadOnlyComposable
internal fun watchGlassBorderColor(): Color = if (watchIsDark()) {
    Color.White.copy(alpha = 0.32f)
} else {
    Color(0xFF9DBBD8).copy(alpha = 0.6f)
}

/** 给任意控件叠加"玻璃"底与描边。 */
@Composable
@ReadOnlyComposable
internal fun Modifier.watchGlass(shape: Shape): Modifier =
    background(watchGlassFill(), shape).border(1.dp, watchGlassBorderColor(), shape)

/**
 * 悬浮圆形按钮（液态玻璃质感）：半透明渐变 + 亮描边 + 按压回弹，
 * 用于无顶栏页面的顶角返回/思考/设置控件。
 */
@Composable
internal fun WatchRoundButton(
    onClick: () -> Unit,
    glyph: String,
    modifier: Modifier = Modifier,
    glyphSize: Int = 22,
    size: Dp = WatchCornerButtonSize,
    glyphColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "roundPressScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .watchGlass(CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            fontSize = glyphSize.sp,
            lineHeight = glyphSize.sp,
            textAlign = TextAlign.Center,
            color = glyphColor,
        )
    }
}

/** 顶部居中的小型状态胶囊（如"思考中…"），呼吸闪烁提示运行中。 */
@Composable
internal fun WatchStatusCapsule(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    if (!visible || text.isBlank()) return
    val pulse = rememberInfiniteTransition(label = "capsulePulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "capsuleAlpha",
    )
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
