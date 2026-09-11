package com.watchagents.wa.ui.watch

import androidx.compose.ui.unit.dp
import com.watchagents.wa.data.model.WatchScreenShape
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕形状判定与尺寸缩放的纯逻辑回归测试。
 *
 * 背景：3.2 之前所有几何都按"圆屏 Watch4"硬编码，长条屏（OPPO Watch / 小米手表）
 * 会白留一半高度、小屏（Samsung ~198dp）控件占满圆屏。这里锁住判定策略与缩放基准。
 */
class WatchScreenKindTest {

    @Test
    fun `自动判定：系统声明圆屏即为圆屏`() {
        assertEquals(
            WatchScreenKind.ROUND,
            resolveWatchScreenKind(WatchScreenShape.AUTO, 228.dp, 228.dp, systemIsRound = true),
        )
    }

    @Test
    fun `自动判定：明显长方形窗口（长条屏）按方形几何处理`() {
        // OPPO Watch 402×476px ≈ 201×238dp（1.18:1）
        assertEquals(
            WatchScreenKind.SQUARE,
            resolveWatchScreenKind(WatchScreenShape.AUTO, 201.dp, 238.dp, systemIsRound = false),
        )
        // 小米手表 368×448px ≈ 184×224dp（1.22:1）
        assertEquals(
            WatchScreenKind.SQUARE,
            resolveWatchScreenKind(WatchScreenShape.AUTO, 184.dp, 224.dp, systemIsRound = false),
        )
    }

    @Test
    fun `自动判定：接近 1比1 且系统未声明圆屏时保守沿用圆屏几何`() {
        // 圆屏被兼容层误报（或实测窗口因状态栏差了几个 dp）时，
        // 只要长宽比接近 1:1 就按圆屏处理：圆屏几何用在方屏上只是留白，
        // 反过来会把角部按钮切到圆外。1:1 方屏可在设置里手动锁定"方形"。
        assertEquals(
            WatchScreenKind.ROUND,
            resolveWatchScreenKind(WatchScreenShape.AUTO, 160.dp, 160.dp, systemIsRound = false),
        )
        assertEquals(
            WatchScreenKind.ROUND,
            resolveWatchScreenKind(WatchScreenShape.AUTO, 228.dp, 220.dp, systemIsRound = false),
        )
        assertFalse(isClearlyRectangular(228.dp, 220.dp))
        assertTrue(isClearlyRectangular(201.dp, 238.dp))
    }

    @Test
    fun `手动指定优先于系统声明`() {
        assertEquals(
            WatchScreenKind.SQUARE,
            resolveWatchScreenKind(WatchScreenShape.SQUARE, 228.dp, 228.dp, systemIsRound = true),
        )
        assertEquals(
            WatchScreenKind.ROUND,
            resolveWatchScreenKind(WatchScreenShape.ROUND, 201.dp, 238.dp, systemIsRound = false),
        )
    }

    @Test
    fun `控件尺寸按屏宽缩放但保留 Watch4 基准值`() {
        // Watch4：466px ÷ 2.04 ≈ 228dp —— 3.2 真机验收值必须原样保留（34dp / 44dp）
        assertEquals(34f, watchScaledSize(228.dp, 34f / 228f, 26.dp, 36.dp).value, 0.05f)
        assertEquals(44f, watchScaledSize(228.dp, 44f / 228f, 32.dp, 46.dp).value, 0.05f)
        // Samsung Galaxy Watch（~198dp）：自动缩小，给列表让出高度
        assertEquals(29.5f, watchScaledSize(198.dp, 34f / 228f, 26.dp, 36.dp).value, 0.2f)
        // 极小方屏（160dp）：不低于可点按下限
        assertEquals(26f, watchScaledSize(160.dp, 34f / 228f, 26.dp, 36.dp).value, 0.05f)
    }

    @Test
    fun `Watch4 228dp 圆屏：角部按钮贴边但绝不越出内切圆`() {
        val side = 228.dp
        val offsets = watchRoundOffsets(side, buttonSize = 34.dp, fabSize = 44.dp)
        val radius = 114f
        val safeRadius = radius - 2f

        // 按钮外角必须落在内切圆（半径 114）以内，且用掉安全圆的 ≥99%（贴边而不是飘在中间）
        val cornerDistance = hypot(
            radius - offsets.topStart.first.value,
            radius - offsets.topStart.second.value,
        )
        assertTrue("按钮外角越出内切圆：$cornerDistance", cornerDistance <= radius - 2f + 0.05f)
        assertTrue("按钮没贴到安全圆上：$cornerDistance", cornerDistance >= safeRadius * 0.99f)

        // 相比 3.2 的 49.39dp：更贴左右边缘（留出更多中缝给状态胶囊）
        assertTrue("按钮应更贴左右边缘", offsets.topStart.first.value <= 45f)
        // 相比 3.2 的 75.39dp：内容上边距更小 = 对话区更高
        assertTrue("对话区应更高", offsets.contentTopPad.value <= 70f)

        // 右下 FAB 同样贴角不越界
        val fabDistance = hypot(
            radius + offsets.bottomEndShift.first.value,
            radius + offsets.bottomEndShift.second.value,
        )
        assertTrue("FAB 越出内切圆：$fabDistance", fabDistance <= radius - 2f + 0.05f)
        assertTrue("FAB 应贴住安全圆：$fabDistance", fabDistance >= safeRadius * 0.99f)
        assertEquals(WatchScreenKind.ROUND, offsets.kind)
    }

    @Test
    fun `长条屏改为贴边几何，内容区显著变大`() {
        // OPPO Watch 402×476px ≈ 201×238dp：方屏几何下上下安全距不再按内切圆内缩
        val square = watchSquareOffsets(side = 201.dp, buttonSize = 30.dp, fabSize = 38.dp)
        val round = watchRoundOffsets(side = 201.dp, buttonSize = 30.dp, fabSize = 38.dp)
        assertEquals(8f, square.topStart.first.value, 0.01f) // 6 + 2（左上返回键再让 2dp）
        assertEquals(6f, square.topStart.second.value, 0.01f)
        assertEquals(42f, square.contentTopPad.value, 0.01f) // 6 + 30 + 6
        assertEquals(52f, square.contentBottomPad.value, 0.01f) // 6 + 38 + 8
        // 同一块屏幕上，方屏几何给内容让出的高度明显更多（实测 ~144dp vs ~98dp，+47%）
        val squareContent = 238f - square.contentTopPad.value - square.contentBottomPad.value
        val roundContent = 238f - round.contentTopPad.value - round.contentBottomPad.value
        assertTrue(squareContent - roundContent > 40f)
        assertTrue(squareContent / roundContent > 1.4f)
    }
}
