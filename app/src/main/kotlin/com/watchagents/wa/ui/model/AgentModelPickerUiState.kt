package com.watchagents.wa.ui.model

import androidx.compose.runtime.Immutable
import com.watchagents.wa.data.model.Model
import com.watchagents.wa.data.model.ProviderSetting
import com.watchagents.wa.data.provider.OfficialModelCatalog
import com.watchagents.wa.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
)

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
)

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter { it.apiKey.isNotBlank() }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
        )
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi {
        val sourceType = ProviderSourceRegistry.resolve(this)
        return AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = sourceType,
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow
                ?: OfficialModelCatalog.contextWindowFor(sourceType, model.modelId),
        )
    }
}

internal fun defaultExpandedModelProviderIds(selectedModel: AgentModelOptionUi?): Set<String> =
    selectedModel?.providerId?.let(::setOf).orEmpty()

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = messages.asReversed()
        .asSequence()
        .filterIsInstance<AgentMessageUi>()
        .mapNotNull { it.usage?.contextTokens }
        .firstOrNull(),
    contextWindow = selectedModel?.contextWindow,
)

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No usage data from the previous response",
    noLimitText: String = "The current model does not provide a context limit",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextTokens == null -> noUsageText
    usage.contextWindow == null || usage.contextWindow <= 0 ->
        "${formatCompactTokenCount(usage.contextTokens, locale)} tokens\n$noLimitText"
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}

/** 汇总本次会话所有回复的 token 消耗（输入含缓存命中/未命中、输出）。 */
internal fun conversationTokenUsage(messages: List<AgentChatMessageUi>): TokenUsageUi? {
    var input = 0
    var output = 0
    var cached = 0
    var found = false
    messages.forEach { message ->
        val usage = (message as? AgentMessageUi)?.usage ?: return@forEach
        if (usage.isEmpty) return@forEach
        found = true
        input += usage.inputTokens ?: 0
        output += usage.outputTokens ?: 0
        cached += usage.cachedTokens ?: 0
    }
    if (!found) return null
    return TokenUsageUi(
        inputTokens = input.takeIf { it > 0 },
        outputTokens = output.takeIf { it > 0 },
        cachedTokens = cached.takeIf { it > 0 },
    )
}

/**
 * DeepSeek V4 官方峰谷定价（每 1M tokens，人民币元），依据官方中文定价页：
 * https://api-docs.deepseek.com/zh-cn/quick_start/pricing/
 * 高峰时段为北京时间周一至周五 9:00-12:00、14:00-18:00（即 UTC 01:00-04:00、06:00-10:00），
 * 其余时间（含整个周末）为谷时，谷时价格为高峰的一半。
 */
internal object DeepSeekV4Pricing {
    private data class Rates(
        val cacheHitPeak: Double,
        val cacheMissPeak: Double,
        val outputPeak: Double,
    ) {
        val cacheHitOffPeak: Double get() = cacheHitPeak / 2.0
        val cacheMissOffPeak: Double get() = cacheMissPeak / 2.0
        val outputOffPeak: Double get() = outputPeak / 2.0
    }

    // deepseek-v4-flash 与 deepseek-v4-flash-vision-exp 同价（人民币）
    private val FLASH_RATES = Rates(cacheHitPeak = 0.10, cacheMissPeak = 3.0, outputPeak = 9.0)
    private val PRO_RATES = Rates(cacheHitPeak = 0.30, cacheMissPeak = 9.0, outputPeak = 27.0)

    private fun ratesForModel(modelId: String?): Rates? = when {
        modelId.isNullOrBlank() -> null
        modelId.startsWith("deepseek-v4-pro") -> PRO_RATES
        modelId.startsWith("deepseek") -> FLASH_RATES
        else -> null
    }

    fun isPeak(now: Long = System.currentTimeMillis()): Boolean {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = now
        }
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return (minutes in 60..239) || (minutes in 360..599)
    }

    /** 估算费用（人民币）；非 DeepSeek 模型或参数缺失时返回 null。 */
    fun estimate(
        cacheHit: Int,
        cacheMiss: Int,
        output: Int,
        modelId: String?,
        now: Long = System.currentTimeMillis(),
    ): Double? {
        val rates = ratesForModel(modelId) ?: return null
        val (hit, miss, out) = if (isPeak(now)) {
            Triple(rates.cacheHitPeak, rates.cacheMissPeak, rates.outputPeak)
        } else {
            Triple(rates.cacheHitOffPeak, rates.cacheMissOffPeak, rates.outputOffPeak)
        }
        return cacheHit / 1_000_000.0 * hit +
            cacheMiss / 1_000_000.0 * miss +
            output / 1_000_000.0 * out
    }
}

/** 人民币金额紧凑格式化：去掉多余尾零。 */
internal fun formatCny(value: Double): String {
    val text = when {
        value >= 100.0 -> String.format(Locale.US, "%.0f", value)
        value >= 1.0 -> String.format(Locale.US, "%.2f", value)
        value >= 0.01 -> String.format(Locale.US, "%.4f", value)
        else -> String.format(Locale.US, "%.6f", value)
    }
    return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
}
