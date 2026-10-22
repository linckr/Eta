package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.CodexCompatibilityProfile
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeConfigRepositoryTest {
    @Test
    fun v4PolicyReachesRuntimeJsonOnlyForMatchingProviderRoute() {
        val base = OpenAiCompatibleProviderSetting("v4", "Fixture", "https://example.invalid/v1")
        val provider = base.copy(requestSendPolicy = io.github.mangi.eta.data.model.RequestSendPolicy.freeze(base, 1_782_914, null))
        val model = Model("id", "model", "Model", contextWindow = 1_000_000)
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        assertEquals(4, config.compactionPolicyVersion)
        assertEquals(2_228_643L, config.requestSendLimitBytes)
        assertEquals(445_729L, config.requestGrowthAllowanceBytes)
        assertEquals(1_782_914L, config.requestObservedBytes)
        assertEquals(config, Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(config)))
        assertEquals(0, RuntimeConfigRepository.buildRuntimeConfig(provider.copy(apiKey = "changed"), model).compactionPolicyVersion)
        assertEquals(0, RuntimeConfigRepository.buildRuntimeConfig(provider, model.copy(customHeaders = listOf(CustomHeader("X-Test", "1")))).compactionPolicyVersion)
    }

    @Test
    fun compactionSelectionDoesNotChangeChatModelOrFallbackWhenMissing() {
        val chat = AgentModelClient.ModelConfig(baseUrl = "https://chat.invalid", apiKey = "fixture", model = "chat", systemPrompt = "")
        val selected = Model("summary-id", "summary", "Summary", contextWindow = 64000)
        val provider = OpenAiCompatibleProviderSetting(id = "p", name = "Summary", baseUrl = "https://summary.invalid", apiKey = "fixture", models = listOf(selected))
        assertEquals(chat, RuntimeConfigRepository.withCompactionModel(chat, null, listOf(provider)))
        val config = RuntimeConfigRepository.withCompactionModel(chat, selected.id, listOf(provider))
        assertEquals("chat", config.model)
        assertEquals("summary", config.compactionModelConfig?.model)
        assertEquals(null, config.compactionModelConfig?.compactionModelConfig)
        val missing = RuntimeConfigRepository.withCompactionModel(chat, selected.id, listOf(provider.copy(isEnabled = false)))
        assertEquals(selected.id, missing.compactionModelId)
        assertEquals(null, missing.compactionModelConfig)
    }

    @Test
    fun nestedCodexSecretIsRemovedFromRuntimeJson() {
        val child = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "sensitive-fixture", model = "summary", systemPrompt = "", authMode = CodexCompatibilityProfile.AUTH_MODE)
        val config = child.copy(authMode = CodexCompatibilityProfile.API_KEY_AUTH_MODE, apiKey = "ordinary-fixture", compactionModelId = "summary", compactionModelConfig = child)
        val decoded = Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(config))
        assertEquals("ordinary-fixture", decoded.apiKey)
        assertEquals("", decoded.compactionModelConfig?.apiKey)
    }
    @Test
    fun switchingProvidersUsesTargetCapacityAndUploadPreference() {
        val largeProvider = OpenAiCompatibleProviderSetting(id = "large", name = "Large", baseUrl = "https://example.invalid", apiKey = "", requestByteBudget = 1_048_576)
        val smallProvider = largeProvider.copy(id = "small", requestByteBudget = 0)
        val large = Model("large-model", "large", "Large", contextWindow = 960_000)
        val small = Model("small-model", "small", "Small", contextWindow = 32_000, contextWindowKind = "total", inputTokenLimit = 24_000)
        val before = RuntimeConfigRepository.buildRuntimeConfig(largeProvider, large)
        val after = RuntimeConfigRepository.buildRuntimeConfig(smallProvider, small)
        assertEquals(960_000, before.contextWindow)
        assertEquals(1_048_576, before.requestByteBudget)
        assertEquals(32_000, after.contextWindow)
        assertEquals(24_000, after.inputTokenLimit)
        assertEquals("total", after.contextWindowKind)
        assertEquals(0, after.requestByteBudget)
        assertEquals(after, Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(after)))
        assertEquals(null, RuntimeConfigRepository.buildRuntimeConfig(smallProvider.copy(requestByteBudget = null), small).requestByteBudget)
    }
    @Test
    fun runtimeCapacityUsesSelectedModelSemanticsWithoutLosingInputCeiling() {
        val provider = OpenAiCompatibleProviderSetting(id = "p1", name = "Provider", baseUrl = "https://example.invalid", apiKey = "")
        val total = Model("m1", "total", "Total", contextWindow = 128_000, contextWindowKind = "total", inputTokenLimit = 96_000)
        val input = Model("m2", "input", "Input", contextWindow = 64_000, contextWindowKind = "input", inputTokenLimit = 64_000)
        val totalConfig = RuntimeConfigRepository.buildRuntimeConfig(provider, total)
        assertEquals("total", totalConfig.contextWindowKind)
        assertEquals(96_000, totalConfig.inputTokenLimit)
        val overrideConfig = RuntimeConfigRepository.buildRuntimeConfig(provider, total.copy(contextWindowOverride = 256_000))
        assertEquals("unknown", overrideConfig.contextWindowKind)
        assertEquals(256_000, overrideConfig.contextWindow)
        assertEquals(96_000, overrideConfig.inputTokenLimit)
        val inputConfig = RuntimeConfigRepository.buildRuntimeConfig(provider, input)
        assertEquals("input", inputConfig.contextWindowKind)
        assertEquals(64_000, inputConfig.contextWindow)
        assertEquals(64_000, inputConfig.inputTokenLimit)
    }
    @Test
    fun buildsStructuredRuntimeConfigFromProviderAndModel() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Provider",
            baseUrl = "https://api.example.com/v1",
            apiKey = "key",
            sourceType = ProviderSourceTypes.OPENAI,
            customHeaders = listOf(CustomHeader("x-provider", "1"))
        )
        val model = Model(
            id = "m1",
            modelId = "gpt-5.5",
            displayName = "GPT-5.5",
            contextWindow = 1_000_000,
            contextWindowOverride = 256_000,
            reasoning = true,
            reasoningOverride = true,
            reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                canDisable = true,
            ),
            customHeaders = listOf(CustomHeader("x-model", "2"))
        )

        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val raw = RuntimeConfigRepository.runtimeConfigJson(config.copy(autoCompactionEnabled = false))
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals(ProviderTypes.OPENAI_COMPATIBLE, root.getValue("providerType").jsonPrimitive.content)
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(256_000, config.contextWindow)
        assertEquals(listOf("x-provider", "x-model"), config.customHeaders.map { it.name })
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(true, config.thinkingEnabled)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
            ),
            config.reasoningCapabilities?.selectableEfforts,
        )
        assertEquals("false", root.getValue("autoCompactionEnabled").jsonPrimitive.content)
        assertEquals(config.copy(autoCompactionEnabled = false), Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
        assertEquals(config.autoCompactionEnabled,
            Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(config)).autoCompactionEnabled)
    }

    @Test
    fun runtimeJsonNeverContainsCodexAccessToken() {
        val config = AgentModelClient.ModelConfig(
            providerId = "builtin-openai-codex",
            providerSourceType = ProviderSourceTypes.OPENAI_CODEX,
            authMode = CodexCompatibilityProfile.AUTH_MODE,
            baseUrl = CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL,
            apiKey = "fake-codex-access-token-for-test-only",
            model = "gpt-6-astra",
            systemPrompt = "",
        )

        val raw = RuntimeConfigRepository.runtimeConfigJson(config)
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals("", root.getValue("apiKey").jsonPrimitive.content)
        org.junit.Assert.assertFalse(raw.contains("fake-codex-access-token-for-test-only"))
    }
}
