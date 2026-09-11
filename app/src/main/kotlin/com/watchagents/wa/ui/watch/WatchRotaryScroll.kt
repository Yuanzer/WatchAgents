package com.watchagents.wa.ui.watch

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.watchagents.wa.data.model.WatchRotaryDirection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 旋转表冠 → 列表滚动（华为 / 小米等国产安卓手表优先，Wear OS 同样适用）。
 *
 * **平滑模型**：表冠刻度只往"待滚像素队列"里加数，真正的滚动在一个逐帧循环里按时间常数
 * 指数逼近（[rotarySmoothingStep]，τ = [ROTARY_SMOOTHING_SECONDS] ≈ 20ms）。所以：
 * - 一个刻度不再是一帧内瞬跳 34dp，而是分摊到约 3~4 帧（约 60ms）→ 跟手且不"一格一格突然滑"；
 * - 表冠一次吐一批刻度（国产 ROM 常见）时，也是一次平滑推进而不是一次大跳；
 * - 稳态速度 = 刻度速率 × [dpPerTick]，**与旋转速度严格成正比**，转多快就滑多快；
 * - 松手后剩下的队列自然排空 ≈ 一段与转速成正比的惯性滑行，且时长有界（约 100ms），不会飞过头。
 *
 * 逐帧循环只在"队列非空"时请求帧，空闲时完全挂起（`wakeUps` 通道），不白耗电。
 *
 * 事件来源覆盖四种透传方式，命中一种即可：
 * 1. 标准旋转编码器：`ACTION_SCROLL` + `AXIS_SCROLL`（Wear OS 全系、多数国产手表）；
 * 2. 只给滚动轴、不打编码器 source 标记、或只填旧式 `AXIS_VSCROLL` 的厂商实现；
 * 3. 表冠被系统映射成按键：`DPAD_UP/DOWN`、`PAGE_UP/DOWN`、`SYSTEM_NAVIGATION_UP/DOWN`；
 * 4. 表冠即音量键（设置里可开）。
 *
 * 刻意**不引入 `androidx.wear.compose.foundation`**：为表冠多背几百 KB 不划算，
 * 而且国产手表的表冠大多不走 Wear 专有通道。
 */
internal object WatchRotaryInput {

    /** 待消费刻度上限：快转时丢旧留新，避免列表追着表冠"排队"卡顿。 */
    private const val MAX_BUFFERED_TICKS = 8

    private val ticks = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = MAX_BUFFERED_TICKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 表冠刻度流：正数 = 向下。没有界面在听时自动丢弃，不会出现"换页后乱滚"。 */
    val tickFlow: SharedFlow<Int> = ticks

    /** 部分机型每格只给 0.2 之类的分数值，累计到整数才走一格。 */
    private var fractional = 0f

    /** 当前有多少个列表在监听表冠：没有列表时干脆不消费系统事件，避免无谓地吞掉按键。 */
    private val consumerCount = AtomicInteger(0)

    /**
     * 部分品牌（及部分"表冠即音量键"的 ROM）把表冠映射成音量键。
     * 默认关闭——打开会占用 App 前台时的音量键，所以交给用户在设置里决定。
     */
    @Volatile
    var volumeKeysAsRotary: Boolean = false

    fun onConsumerStart() {
        consumerCount.incrementAndGet()
    }

    fun onConsumerStop() {
        consumerCount.decrementAndGet()
    }

    private val hasConsumer: Boolean
        get() = consumerCount.get() > 0

    /**
     * 处理通用运动事件（在 Activity 的 `onGenericMotionEvent` / `dispatchGenericMotionEvent` 里调用）。
     *
     * @return true 表示已消费（返回 false 时事件继续走系统默认处理）
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        if (!hasConsumer) return false
        val value = readScrollAxis(event) ?: return false
        val (rest, whole) = axisToTicks(value, fractional)
        fractional = rest
        // 分数刻度先攒着，但事件照样消费掉：否则系统会拿同一个事件再滚一次
        if (whole != 0) emit(whole)
        return true
    }

    /**
     * 处理被映射成按键的表冠/导航事件（在 Activity 的 `dispatchKeyEvent` 里调用）。
     *
     * 只认方向/翻页键（以及可选的音量键），绝不碰返回键；输入法窗口有焦点时这些键本来也到不了这里。
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!hasConsumer) return false
        val delta = keyCodeToTicks(event.keyCode, volumeKeysAsRotary)
        if (delta == 0) return false
        emit(delta)
        return true
    }

    private fun emit(value: Int) {
        if (value != 0) ticks.tryEmit(value)
    }
}

/**
 * 依次尝试通用滚动轴、旧式垂直滚动轴：
 * 有些厂商的旋转实现只填 `AXIS_VSCROLL`（老鼠标滚轮那条路），只认 `AXIS_SCROLL` 会漏掉它们。
 */
private fun readScrollAxis(event: MotionEvent): Float? {
    val scroll = runCatching { event.getAxisValue(MotionEvent.AXIS_SCROLL) }.getOrDefault(0f)
    if (scroll != 0f && scroll.isFinite()) return scroll
    val vertical = runCatching { event.getAxisValue(MotionEvent.AXIS_VSCROLL) }.getOrDefault(0f)
    if (vertical != 0f && vertical.isFinite()) return vertical
    return null
}

/**
 * `AXIS_SCROLL` 的符号与"列表滚动方向"相反：正轴值应当让列表**向上回滚**（看更早的内容），
 * 而 [androidx.compose.foundation.gestures.ScrollableState] 的正值含义是"向列表尾部滚动"。
 * 3.3.2 真机（Watch4）反馈方向反了，这里取负修正。
 *
 * 若某台机型方向又相反：用户在 设置 → 外观 → 表冠方向 里切「反向」即可，
 * 不需要改代码（见 [LocalWatchRotaryReversed]）。
 */
private const val ROTARY_AXIS_DIRECTION = -1f

/**
 * 把分数刻度累积成整格：部分机型每格只给 0.2 这类小数值。
 *
 * @return 新的余数 to 本次应滚动的格数
 */
internal fun accumulateTicks(accumulated: Float, delta: Float): Pair<Float, Int> {
    val total = accumulated + delta
    val whole = total.toInt()
    return (total - whole) to whole
}

/**
 * `AXIS_SCROLL` 原始值 → 列表滚动刻度（含方向修正）。
 *
 * 正轴值（顺时针）应当让列表**向上回滚**，所以输出是负的 —— 这条约定由真机反馈确定，
 * 并用单元测试钉住，避免以后又被"顺手改回去"。
 */
internal fun axisToTicks(axisValue: Float, accumulated: Float): Pair<Float, Int> =
    accumulateTicks(accumulated, axisValue * ROTARY_AXIS_DIRECTION)

/**
 * 方向/翻页键 → 表冠刻度（上 = -1，下 = +1，其它键 = 0 表示不拦）。
 *
 * @param volumeKeysAsRotary 是否把音量键也当表冠（"表冠即音量键"的机型用，设置里可开）
 */
internal fun keyCodeToTicks(keyCode: Int, volumeKeysAsRotary: Boolean = false): Int = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
    -> -1

    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
    -> 1

    KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysAsRotary) -1 else 0
    KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysAsRotary) 1 else 0

    else -> 0
}

/** 平滑时间常数（秒）：越小越跟手、越大越绵。20ms ≈ 每格 3~4 帧走完，跟手且不跳。 */
internal const val ROTARY_SMOOTHING_SECONDS = 0.02f

/** 单帧最多推进积压的比例：即使掉帧也不允许一帧跳掉一大段（平滑下限的硬保护）。 */
private const val MAX_STEP_FRACTION = 0.65f

/** 待滚队列上限（px）：约 2.5 屏，防止极端快转攒出一个超长惯性。 */
private const val MAX_PENDING_PX = 1200f

/** 单帧最多推进的时长（秒）：卡顿/掉帧后不要一帧跳很远。 */
private const val MAX_FRAME_SECONDS = 0.05f

/** 两帧间隔超过这个值就认为"被抢占了"（手势/系统），丢掉积压而不是补跳。 */
private const val STALE_FRAME_NANOS = 350_000_000L

/**
 * 单帧应滚动的像素：把待滚队列按 `1 - e^(-dt/τ)` 指数逼近。
 *
 * 纯函数，便于测试：无论表冠一帧来 1 格还是 10 格，位移都是分摊到多帧上的，
 * 这就是"平滑"的来源；而稳态速度 = 刻度速率 × 每格像素，仍然与旋转速度成正比。
 */
internal fun rotarySmoothingStep(
    pendingPx: Float,
    dtSeconds: Float,
    timeConstantSeconds: Float = ROTARY_SMOOTHING_SECONDS,
): Float {
    if (pendingPx == 0f) return 0f
    val dt = dtSeconds.coerceIn(1f / 240f, MAX_FRAME_SECONDS)
    val factor = (1f - exp(-dt / timeConstantSeconds.coerceAtLeast(1f / 240f)))
        .coerceAtMost(MAX_STEP_FRACTION)
    val step = pendingPx * factor
    // 余数很小时一次走完，避免"最后 0.3px"拖出多余帧
    return if (abs(step) < 0.5f) pendingPx else step
}

/**
 * 表冠方向是否整体反向（设置 → 外观 → 表冠方向）。
 *
 * 各家手表对"顺时针 = 往下滚"的定义不统一，所以给一个用户可切的开关：
 * 默认 [WatchRotaryDirection.STANDARD]（与 Watch4 真机一致），方向反了就切「反向」。
 */
internal val LocalWatchRotaryReversed = staticCompositionLocalOf { false }

/**
 * 把表冠接到任意列表上。
 *
 * @param dpPerTick 每格位移（默认 34dp ≈ 0.7 行）。平滑只改变"每格怎么走完"，
 *   不改变稳态速度（= 每格像素 × 每秒格数）。
 */
@Composable
internal fun WatchRotaryScrollEffect(
    listState: LazyListState,
    dpPerTick: Int = 34,
) {
    val density = LocalDensity.current
    val pxPerTick = with(density) { dpPerTick.dp.toPx() }
    val reversed = LocalWatchRotaryReversed.current

    DisposableEffect(Unit) {
        WatchRotaryInput.onConsumerStart()
        onDispose { WatchRotaryInput.onConsumerStop() }
    }

    LaunchedEffect(listState, pxPerTick, reversed) {
        val direction = if (reversed) -1f else 1f
        var pendingPx = 0f
        // 只在有待滚像素时逐帧推进；空闲时这个协程完全挂起，不请求帧
        val wakeUps = Channel<Unit>(Channel.CONFLATED)
        val producer = launch {
            WatchRotaryInput.tickFlow.collect { count ->
                pendingPx = (pendingPx + count * pxPerTick * direction)
                    .coerceIn(-MAX_PENDING_PX, MAX_PENDING_PX)
                wakeUps.trySend(Unit)
            }
        }
        try {
            while (true) {
                wakeUps.receive()
                var lastFrameNanos = 0L
                while (pendingPx != 0f) {
                    val frameNanos = withFrameNanos { it }
                    if (lastFrameNanos != 0L && frameNanos - lastFrameNanos > STALE_FRAME_NANOS) {
                        // 中途被手势/系统长时间抢占：丢掉积压，避免恢复时突然补跳一大段
                        pendingPx = 0f
                        break
                    }
                    val dt = if (lastFrameNanos == 0L) {
                        1f / 60f
                    } else {
                        ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(1f / 240f, MAX_FRAME_SECONDS)
                    }
                    lastFrameNanos = frameNanos

                    val step = rotarySmoothingStep(pendingPx, dt)
                    val consumed = scrollStep(listState, step)
                    if (consumed == null) {
                        // 被用户手势抢占：放弃这一批，交还控制权（手势优先）
                        pendingPx = 0f
                        break
                    }
                    pendingPx -= step
                    if (abs(consumed - step) > 0.5f) {
                        // 已经到列表边界：清空队列，避免"顶住还在攒"
                        pendingPx = 0f
                        break
                    }
                }
            }
        } finally {
            producer.cancel()
        }
    }
}

/**
 * 滚动一小步。
 *
 * 用 Default 优先级：用户手指拖动（UserInput）能抢占表冠的平滑尾，手指永远优先；
 * 被抢占时 `scroll` 会抛 CancellationException，这里吞掉并返回 null，避免把整个协程带崩
 * （如果外层 effect 自己被取消，后续 `withFrameNanos` 会立刻抛出并结束循环）。
 */
private suspend fun scrollStep(listState: LazyListState, pixels: Float): Float? =
    try {
        // 注意：1.9.x 的 ScrollableState.scroll 返回 Unit，消耗量要从块里带出来
        var consumed = 0f
        listState.scroll(MutatePriority.Default) { consumed = scrollBy(pixels) }
        consumed
    } catch (cancelled: CancellationException) {
        null
    }
