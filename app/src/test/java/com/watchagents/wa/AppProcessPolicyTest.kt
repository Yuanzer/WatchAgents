package com.watchagents.wa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("com.watchagents.wa", "com.watchagents.wa"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("com.watchagents.wa:voice", "com.watchagents.wa"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("com.watchagents.wa:voice_session", "com.watchagents.wa"))
    }
}
