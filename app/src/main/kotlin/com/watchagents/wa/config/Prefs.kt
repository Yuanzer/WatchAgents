package com.watchagents.wa.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Watch4 精简版配置中枢（无 Xposed/LSPosed/root 依赖）。
 *
 * 与手机版差异：没有 remote/Hook 进程配置，全部开关只保存在 App 私有
 * SharedPreferences 中，由 UI 与 Runtime（进程内直调）读取。
 */
internal object Prefs {

    private const val LOCAL_AGENT_GROUP = "wa_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_THINKING_EFFORT = "agent_thinking_effort"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            // 手表版：终端/root 设备工具一律关闭
            AGENT_TERMINAL_TOOLS to false,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to false,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to false,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to false,
            // 默认不展开思考：需要推理时在模型弹窗滑块或设置里按需开启
            AGENT_THINKING_ENABLED to false
        )
    }

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        return localAgent?.getBoolean(key, default) ?: default
    }

    fun setEnabled(key: String, value: Boolean) {
        localAgent?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getString(key: String): String {
        return localAgent?.getString(key, "") ?: ""
    }

    fun putString(key: String, value: String) {
        localAgent?.edit()?.putString(key, value)?.apply()
    }

    /** WA 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent
}
