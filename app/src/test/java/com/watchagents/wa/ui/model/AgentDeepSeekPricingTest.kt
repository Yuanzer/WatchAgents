package com.watchagents.wa.ui.model

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeepSeekPricingTest {

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun peakHoursAreOnlyWeekdayOneToFourAndSixToTenUtc() {
        // 2026-08-24 是周一
        assertTrue("周一 02:00 UTC 应为峰时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 24, 2, 0)))
        assertTrue("周一 08:00 UTC 应为峰时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 24, 8, 0)))
        assertFalse("周一 12:00 UTC 应为谷时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 24, 12, 0)))
        assertFalse("周一 00:30 UTC 应为谷时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 24, 0, 30)))
        assertFalse("周一 05:00 UTC 应为谷时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 24, 5, 0)))
        assertFalse("周一 10:30 UTC 应为谷时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 24, 10, 30)))
        // 2026-08-23 是周日：全天谷时
        assertFalse("周日 02:00 UTC 应为谷时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 23, 2, 0)))
        assertFalse("周日 08:00 UTC 应为谷时", DeepSeekV4Pricing.isPeak(utcMillis(2026, 8, 23, 8, 0)))
    }

    @Test
    fun flashVisionEstimateUsesOfficialCnyRatesAndHalvesOffPeak() {
        // deepseek-v4-flash-vision-exp 与 flash 同价（官方人民币定价）
        val model = "deepseek-v4-flash-vision-exp"
        val peak = utcMillis(2026, 8, 24, 2, 0)
        val offPeak = utcMillis(2026, 8, 24, 12, 0)
        // 100 万未命中 token，谷时 ¥1.5
        val off = DeepSeekV4Pricing.estimate(cacheHit = 0, cacheMiss = 1_000_000, output = 0, modelId = model, now = offPeak)
        assertNotNull(off)
        assertEquals(1.5, off!!, 1e-9)
        val peakCost = DeepSeekV4Pricing.estimate(cacheHit = 0, cacheMiss = 1_000_000, output = 0, modelId = model, now = peak)
        assertNotNull(peakCost)
        assertEquals(3.0, peakCost!!, 1e-9)
        // 命中 + 输出混合：谷时 1M 命中 ¥0.05 + 1M 输出 ¥4.5
        val mixed = DeepSeekV4Pricing.estimate(cacheHit = 1_000_000, cacheMiss = 0, output = 1_000_000, modelId = model, now = offPeak)
        assertNotNull(mixed)
        assertEquals(0.05 + 4.5, mixed!!, 1e-9)
        // pro 模型用 pro 价
        val pro = DeepSeekV4Pricing.estimate(cacheHit = 0, cacheMiss = 1_000_000, output = 0, modelId = "deepseek-v4-pro", now = offPeak)
        assertNotNull(pro)
        assertEquals(4.5, pro!!, 1e-9)
        // 非 deepseek 模型返回 null
        assertNull(DeepSeekV4Pricing.estimate(cacheHit = 0, cacheMiss = 100, output = 0, modelId = "gpt-5.5", now = offPeak))
    }

    @Test
    fun formatCnyTrimsTrailingZerosAndScalesPrecision() {
        assertEquals("100", formatCny(100.0))
        assertEquals("1.5", formatCny(1.5))
        assertEquals("0.02", formatCny(0.02))
        assertEquals("0.0012", formatCny(0.0012))
        assertEquals("0.001234", formatCny(0.001234))
    }

    @Test
    fun estimateUsesOfficialCnyPricesDirectly() {
        val model = "deepseek-v4-flash-vision-exp"
        val offPeak = utcMillis(2026, 8, 24, 12, 0)
        // 谷时未命中 1M = ¥1.5（官方人民币定价，非汇率换算）
        val cny = DeepSeekV4Pricing.estimate(
            cacheHit = 0,
            cacheMiss = 1_000_000,
            output = 0,
            modelId = model,
            now = offPeak,
        )
        assertNotNull(cny)
        assertEquals(1.5, cny!!, 1e-9)
        // 输出 1M 谷时 = ¥4.5
        val out = DeepSeekV4Pricing.estimate(
            cacheHit = 0,
            cacheMiss = 0,
            output = 1_000_000,
            modelId = model,
            now = offPeak,
        )
        assertNotNull(out)
        assertEquals(4.5, out!!, 1e-9)
    }

    @Test
    fun conversationTokenUsageAggregatesAssistantUsages() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "hi"),
            AgentMessageUi(
                id = "a1",
                content = "ok",
                usage = TokenUsageUi(inputTokens = 1_000, outputTokens = 300, cachedTokens = 700),
            ),
            AgentMessageUi(
                id = "a2",
                content = "done",
                usage = TokenUsageUi(inputTokens = 2_000, outputTokens = 500, cachedTokens = 1_200),
            ),
        )
        val aggregate = conversationTokenUsage(messages)
        assertNotNull(aggregate)
        assertEquals(3_000, aggregate!!.inputTokens)
        assertEquals(800, aggregate.outputTokens)
        assertEquals(1_900, aggregate.cachedTokens)
    }

    @Test
    fun conversationTokenUsageReturnsNullWithoutAssistantUsage() {
        assertNull(conversationTokenUsage(emptyList()))
        assertNull(conversationTokenUsage(listOf(UserMessageUi(id = "u1", content = "hi"))))
    }
}
