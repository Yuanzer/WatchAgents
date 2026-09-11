package com.watchagents.wa.ui.watch

import android.view.KeyEvent
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠输入映射与平滑模型的纯逻辑回归测试。
 *
 * 覆盖"事件 → 刻度"与"刻度 → 每帧位移"两层换算；事件到达路径（Activity 兜底分发）
 * 只能靠真机验证，这里钉住的是可测的换算规则：方向符号、分数刻度累积、按键白名单、
 * 以及"一个刻度要分摊到多帧、但稳态速度仍与刻度速率成正比"。
 */
class WatchRotaryInputTest {

    @Test
    fun `分数刻度累积到整格才滚动`() {
        // 部分机型每格只给 0.25：攒够整数才滚一格
        // （用 0.25 而非 0.2：0.25 在二进制里精确，避免浮点误差把断言带偏）
        var acc = 0f
        var ticks = 0
        repeat(3) {
            val (rest, whole) = accumulateTicks(acc, 0.25f)
            acc = rest
            ticks += whole
        }
        assertEquals("0.75 格还不该滚", 0, ticks)
        assertEquals(0.75f, acc, 0.0001f)

        val (restUp, wholeUp) = accumulateTicks(acc, 0.25f) // 攒满 1.0 → 正好滚一格
        assertEquals(1, wholeUp)
        assertEquals(0f, restUp, 0.0001f)

        // 反向同理：攒到 -1 才滚，余数保留
        val (restDown, wholeDown) = accumulateTicks(-0.5f, -0.75f)
        assertEquals(-1, wholeDown)
        assertEquals(-0.25f, restDown, 0.0001f)
    }

    @Test
    fun `整数刻度直接透传且不产生余数`() {
        val (rest, whole) = accumulateTicks(0f, -3f)
        assertEquals(-3, whole)
        assertEquals(0f, rest, 0.0001f)
    }

    @Test
    fun `表冠顺时针原始轴值为正，滚动刻度必须取负`() {
        // 真机（Watch4）反馈：不取负时列表滚动方向是反的。
        // 这条断言把"AXIS_SCROLL 正 = 列表向上回滚"的约定钉死。
        val (restUp, up) = axisToTicks(axisValue = 1f, accumulated = 0f)
        assertEquals(-1, up)
        assertEquals(0f, restUp, 0.0001f)

        val (restDown, down) = axisToTicks(axisValue = -1f, accumulated = 0f)
        assertEquals(1, down)
        assertEquals(0f, restDown, 0.0001f)

        // 分数轴值同样先取负再累积：0.25 × 4 格才凑满一整格
        var acc = 0f
        var wholeSum = 0
        repeat(4) {
            val (rest, whole) = axisToTicks(axisValue = 0.25f, accumulated = acc)
            acc = rest
            wholeSum += whole
        }
        assertEquals(-1, wholeSum)
        assertEquals(0f, acc, 0.0001f)
    }

    @Test
    fun `方向键与翻页键映射为刻度，其它键一律不拦`() {
        assertEquals(-1, keyCodeToTicks(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(1, keyCodeToTicks(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(-1, keyCodeToTicks(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(1, keyCodeToTicks(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(-1, keyCodeToTicks(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP))
        assertEquals(1, keyCodeToTicks(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN))
        // 返回键/回车等必须放行给系统
        assertEquals(0, keyCodeToTicks(KeyEvent.KEYCODE_BACK))
        assertEquals(0, keyCodeToTicks(KeyEvent.KEYCODE_ENTER))
        assertEquals(0, keyCodeToTicks(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `音量键默认放行，只有显式开启才当表冠`() {
        // 默认关闭：音量键保持系统语义（不能悄悄占用）
        assertEquals(0, keyCodeToTicks(KeyEvent.KEYCODE_VOLUME_UP, volumeKeysAsRotary = false))
        assertEquals(0, keyCodeToTicks(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeysAsRotary = false))
        // 用户开了"音量键滚列表"（少数把表冠映射成音量键的机型）才拦
        assertEquals(-1, keyCodeToTicks(KeyEvent.KEYCODE_VOLUME_UP, volumeKeysAsRotary = true))
        assertEquals(1, keyCodeToTicks(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeysAsRotary = true))
    }

    @Test
    fun `一个刻度要分摊到多帧，不能一帧跳完`() {
        val frame = 1f / 60f
        val step = rotarySmoothingStep(pendingPx = 52f, dtSeconds = frame)
        // 一帧只走一部分（不是整格瞬跳）→ 这就是"平滑"的来源
        assertTrue("一帧就走完了：$step", step < 52f * 0.7f)
        // 但也不能太黏：20ms 时间常数下首帧应当走掉一半以上
        assertTrue("跟手感不足：$step", step > 52f * 0.5f)
        // 方向不能变
        assertEquals(-step, rotarySmoothingStep(pendingPx = -52f, dtSeconds = frame), 0.0001f)
        // 队列为空就不动
        assertEquals(0f, rotarySmoothingStep(pendingPx = 0f, dtSeconds = frame), 0.0001f)
    }

    @Test
    fun `多帧累积走完整个刻度，稳态速度与刻度速率成正比`() {
        val frame = 1f / 60f
        var pending = 52f
        var moved = 0f
        var frames = 0
        while (pending != 0f && frames < 60) {
            val step = rotarySmoothingStep(pending, frame)
            pending -= step
            moved += step
            frames++
        }
        // 累积位移守恒（不会多走也不会丢）
        assertEquals(52f, moved, 0.01f)
        // 一个刻度在几帧内走完（20ms 时间常数下约 3~4 帧）
        assertTrue("走完用了 $frames 帧", frames in 2..10)

        // 稳态：每帧注入 2 格（120 格/秒），逐帧消耗应当收敛到同样的速度
        var queue = 0f
        val perFrameInput = 2 * 52f
        repeat(60) {
            queue += perFrameInput
            queue -= rotarySmoothingStep(queue, frame)
        }
        // 注意：循环里是对"加入本帧输入之后"的队列取步长，所以这里也要带上本帧输入
        val consumedPerFrame = rotarySmoothingStep(queue + perFrameInput, frame)
        // 收敛后每帧消耗 ≈ 每帧注入（误差 <5%）→ 稳态速度 = 刻度速率 × 每格像素，与转速成正比
        assertEquals(perFrameInput.toDouble(), consumedPerFrame.toDouble(), perFrameInput * 0.05)
        // 积压量有界（不会无限增长导致"越转越落后"）
        assertTrue("积压 $queue px 过大", queue < perFrameInput / 0.2f)
    }

    @Test
    fun `掉帧或极端 dt 不会一帧跳过整段`() {
        // dt 被夹到 50ms 上限，且单帧最多推进积压的 65%：
        // 即使卡了 500ms，也不会一帧把整段跳完
        val step = rotarySmoothingStep(pendingPx = 1000f, dtSeconds = 0.5f)
        assertTrue("单帧推进过多：$step", step <= 1000f * 0.66f)
        assertTrue(step > 0f)
        // dt = 0 / 负数也不该产生 NaN 或反向
        val tiny = rotarySmoothingStep(pendingPx = 100f, dtSeconds = 0f)
        assertTrue(tiny > 0f && tiny < 100f)
        assertTrue(abs(rotarySmoothingStep(pendingPx = 100f, dtSeconds = -1f) - tiny) < 0.001f)
    }
}
