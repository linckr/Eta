package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.CodexCompatibilityProfile
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
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
        return withCompactionModel(buildRuntimeConfig(provider, model), settings.compactionModelId, ProviderRepository.allProviders())
    }

    /** 会话绑定的模型仍可用时使用它，否则回落到默认模型。 */
    suspend fun runtimeConfigFor(modelId: String?): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            return withCompactionModel(buildRuntimeConfig(provider, model), SettingsDataStore.settings().compactionModelId, ProviderRepository.allProviders())
        }
        return currentRuntimeConfig()
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(config.withoutCodexSecret())

    internal fun withCompactionModel(
        config: AgentModelClient.ModelConfig,
        selectedId: String?,
        providers: List<ProviderSetting>,
    ): AgentModelClient.ModelConfig {
        val id = selectedId?.takeIf { it.isNotBlank() }
        val chosen = providers.enabledModel(id)?.let { (provider, model) -> buildRuntimeConfig(provider, model) }
        return config.copy(compactionModelId = id, compactionModelConfig = if (id == null) null else chosen)
    }

    private fun AgentModelClient.ModelConfig.withoutCodexSecret(): AgentModelClient.ModelConfig {
        val isCodex = authMode == CodexCompatibilityProfile.AUTH_MODE ||
            providerSourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI_CODEX ||
            providerId == BuiltinProviders.OPENAI_CODEX_ID
        return copy(
            apiKey = if (isCodex) "" else apiKey,
            compactionModelConfig = compactionModelConfig?.copy(compactionModelId = null, compactionModelConfig = null)?.withoutCodexSecret(),
        )
    }

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuiltinProviders.DEFAULT_SYSTEM_PROMPT
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val isCodexSubscription = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI_CODEX ||
            provider.id == BuiltinProviders.OPENAI_CODEX_ID
        val endpointMode = provider.endpointMode()
        val reasoningCapabilities = reasoningCapabilities(provider, model)
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = if (isCodexSubscription) {
                CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL
            } else {
                provider.baseUrl.trim()
            },
            apiKey = if (isCodexSubscription) "" else provider.apiKey.trim(),
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            contextWindowKind = model.effectiveContextWindowKind,
            inputTokenLimit = model.inputTokenLimit?.takeIf { it > 0 },
            requestByteBudget = provider.requestByteBudget?.takeIf { it >= 0 },
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = if (isCodexSubscription) OpenAiEndpointMode.RESPONSES else endpointMode,
            authMode = if (isCodexSubscription) {
                CodexCompatibilityProfile.AUTH_MODE
            } else {
                CodexCompatibilityProfile.API_KEY_AUTH_MODE
            },
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            thinkingEnabled = reasoningCapabilities != null,
            reasoningEffort = reasoningCapabilities?.let { ReasoningEffort.DEFAULT }
                ?: ReasoningEffort.OFF,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
    }

    fun reasoningCapabilities(provider: ProviderSetting, model: Model): ModelReasoningCapabilities? {
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val isCodexSubscription = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI_CODEX ||
            provider.id == BuiltinProviders.OPENAI_CODEX_ID
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            provider.endpointMode() == OpenAiEndpointMode.RESPONSES
        return ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
    }

    private fun ProviderSetting.endpointMode(): String = when (this) {
        is OpenAiCompatibleProviderSetting -> endpointMode
        is CustomProviderSetting -> endpointMode
        is AnthropicProviderSetting -> ""
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)
}
