package com.watchagents.wa.data.provider

import com.watchagents.wa.data.model.AnthropicProviderSetting
import com.watchagents.wa.data.model.OpenAiCompatibleProviderSetting
import com.watchagents.wa.data.model.OpenAiEndpointMode
import com.watchagents.wa.data.model.ProviderSetting
import com.watchagents.wa.data.model.ProviderSourceTypes

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "你是 WA——运行在用户智能手表（小屏圆窗）上的个人 AI 助手。风格：直接、精炼、口语化中文，先结论后依据；" +
            "绝不复述问题、不空话客套、不编造事实与来源。" +
            "必要时按需使用可用工具：web_search/fetch_url（联网）、memory_*（记忆）、" +
            "files_*/media_*（本地沙盒文件与相册/文档）、skills_*（技能）。" +
            "工具结果以 JSON 呈现，只引用其中真实存在的内容。" +
            "手表无 root 且不依赖 adb：操作其他应用、拨打电话、读写其他应用数据、改系统设置均不可行，明确告知并提供替代方案。"

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"

    /**
     * 内置 DeepSeek API Key —— **默认留空，必须由用户自己填写**。
     *
     * 留空后：首启不预置任何 Key，设置页 DeepSeek 行显示"未填写"，
     * 未填时发送消息不会发出请求，而是提示去 设置 → DeepSeek Key 粘贴 `sk-...`。
     * 若将来想恢复"内置开箱可用"，只改这一行即可
     * （`ProviderRepository.ensureBuiltInsMerged` 会自动把非空默认值回填给空 Key 的 DeepSeek）。
     */
    const val DEFAULT_DEEPSEEK_API_KEY = ""
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"

    val PROVIDERS: List<ProviderSetting> = listOf(
        // Watch 版：DeepSeek 置首并默认选中（Key 默认空，需用户在设置页填写）
        OpenAiCompatibleProviderSetting(
            id = DEEPSEEK_ID,
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            sourceType = ProviderSourceTypes.DEEPSEEK,
            apiKey = DEFAULT_DEEPSEEK_API_KEY,
            isBuiltIn = true,
            sortOrder = 0,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            isBuiltIn = true,
            sortOrder = 1,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        AnthropicProviderSetting(
            id = ANTHROPIC_ID,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            sourceType = ProviderSourceTypes.ANTHROPIC,
            isBuiltIn = true,
            sortOrder = 2,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = BAILIAN_ID,
            name = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
            isBuiltIn = true,
            sortOrder = 3,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = KIMI_ID,
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            sourceType = ProviderSourceTypes.MOONSHOT,
            isBuiltIn = true,
            sortOrder = 4,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = MIMO_ID,
            name = "MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            sourceType = ProviderSourceTypes.MIMO,
            isBuiltIn = true,
            sortOrder = 5,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = MINIMAX_ID,
            name = "MiniMax",
            baseUrl = "https://api.minimaxi.com/v1",
            sourceType = ProviderSourceTypes.MINIMAX,
            isBuiltIn = true,
            sortOrder = 6,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = STEPFUN_ID,
            name = "StepFun",
            baseUrl = "https://api.stepfun.com/v1",
            sourceType = ProviderSourceTypes.STEPFUN,
            isBuiltIn = true,
            sortOrder = 7,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = SILICONFLOW_ID,
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            sourceType = ProviderSourceTypes.SILICONFLOW,
            isBuiltIn = true,
            sortOrder = 8,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = OPENROUTER_ID,
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            sourceType = ProviderSourceTypes.OPENROUTER,
            isBuiltIn = true,
            sortOrder = 9,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        )
    )

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
