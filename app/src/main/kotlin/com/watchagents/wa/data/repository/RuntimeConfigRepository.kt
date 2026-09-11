package com.watchagents.wa.data.repository

import com.watchagents.wa.agent.model.AgentModelClient
import com.watchagents.wa.config.Prefs
import com.watchagents.wa.data.datastore.SettingsDataStore
import com.watchagents.wa.data.model.AnthropicProviderSetting
import com.watchagents.wa.data.model.CustomProviderSetting
import com.watchagents.wa.data.model.Model
import com.watchagents.wa.data.model.OpenAiCompatibleProviderSetting
import com.watchagents.wa.data.model.OpenAiEndpointMode
import com.watchagents.wa.data.model.ProviderSetting
import com.watchagents.wa.data.model.ReasoningEffort
import com.watchagents.wa.data.model.runtimeProviderType
import com.watchagents.wa.data.model.selectedOrFirstModel
import com.watchagents.wa.data.provider.BuiltinProviders
import com.watchagents.wa.data.provider.OfficialModelCatalog
import com.watchagents.wa.data.provider.ProviderSourceRegistry
import com.watchagents.wa.data.provider.ReasoningCapabilityResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(provider, model)
    }

    /** 将当前配置写入本地 prefs（无 Xposed：仅进程内直调消费）。 */
    suspend fun syncToLocalPreferences(): Boolean {
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig()
        return writeRuntimeConfig(config)
    }

    suspend fun ensureDefaults() {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToLocalPreferences()
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(config)

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuiltinProviders.DEFAULT_SYSTEM_PROMPT
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val endpointMode = when (provider) {
            is OpenAiCompatibleProviderSetting -> provider.endpointMode
            is CustomProviderSetting -> provider.endpointMode
            is AnthropicProviderSetting -> ""
        }
        val inferOpenAiCatalog = sourceType == com.watchagents.wa.data.model.ProviderSourceTypes.CUSTOM &&
            endpointMode == OpenAiEndpointMode.RESPONSES
        val reasoningCapabilities = ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                com.watchagents.wa.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow
                ?: OfficialModelCatalog.contextWindowFor(sourceType, model.modelId),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            thinkingEnabled = reasoningCapabilities != null,
            reasoningEffort = reasoningCapabilities?.let { ReasoningEffort.DEFAULT }
                ?: ReasoningEffort.OFF,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
    }

    private fun writeRuntimeConfig(config: AgentModelClient.ModelConfig): Boolean =
        runCatching {
            Prefs.putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
            true
        }.getOrDefault(false)

    private fun clearRuntimeConfig(): Boolean =
        runCatching {
            Prefs.putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, "")
            true
        }.getOrDefault(false)
}
