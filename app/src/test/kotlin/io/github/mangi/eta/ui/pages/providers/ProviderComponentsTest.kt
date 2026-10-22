package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.saveable.SaverScope
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderComponentsTest {
    @Test fun invalidEarlyBootstrapNeedsExplicitOptInAndDoesNotKeepLargeLimit() {
        val source = CustomProviderSetting("p", "Fixture", "https://example.invalid", requestByteBudget = 48 * 1024 * 1024)
        val old = source.copy(requestSendPolicy = io.github.mangi.eta.data.model.RequestSendPolicy.freeze(source, null, null)
            .copy(bootstrapRuleVersion = 0, sendLimitBytes = 48L * 1024 * 1024 + 524_288, growthAllowanceBytes = 524_288))
        assertFalse(ProviderConfigDraft.from(old).sendPolicyEnabled)
        fun update(enabled: Boolean?) = buildUpdatedProvider(old, old.name, old.baseUrl, old.apiKey,
            "", old.isEnabled, old.endpointMode, false, "", emptyList(), sendPolicyEnabled = enabled)
        assertEquals(0, update(null).requestSendPolicy.version)
        val optedIn = update(true)
        assertTrue(optedIn.requestSendPolicy.isValidFor(optedIn))
        assertEquals(786_432L, optedIn.requestSendPolicy.sendLimitBytes)
        assertEquals(48 * 1024 * 1024, optedIn.requestByteBudget)
    }
    @Test
    fun sendPolicyExclusionNamesExposeExactlyModelsWithIndependentRequestOverrides() {
        val provider = CustomProviderSetting("p", "Fixture", "https://example.invalid", models = listOf(
            Model("safe", "safe", "无覆盖"),
            Model("headers", "headers", "独立头", customHeaders = listOf(io.github.mangi.eta.data.model.CustomHeader("X-Test", "1"))),
            Model("body", "fallback-id", "", customBody = listOf(io.github.mangi.eta.data.model.CustomBody("temperature", kotlinx.serialization.json.JsonPrimitive(0.5)))),
        ))
        assertEquals(listOf("独立头", "fallback-id"), requestSendPolicyExcludedModelNames(provider))
        assertEquals(emptyList<String>(), requestSendPolicyExcludedModelNames(provider.copy(models = emptyList())))
    }

    @Test
    fun explicitV4OptInFreezesLimitAndRouteEditsRequireNewOptIn() {
        val source = CustomProviderSetting("p", "Fixture", "https://example.invalid")
        fun update(p: CustomProviderSetting, baseUrl: String = p.baseUrl, enabled: Boolean = true) =
            buildUpdatedProvider(p, p.name, baseUrl, p.apiKey, "", p.isEnabled, p.endpointMode,
                false, "", emptyList(), sendPolicyEnabled = enabled, requestObservedBytes = 1_782_914)
                as CustomProviderSetting
        val enabled = update(source)
        assertEquals(4, enabled.requestSendPolicy.version)
        assertEquals(2_228_643L, enabled.requestSendPolicy.sendLimitBytes)
        assertEquals(enabled.requestSendPolicy, update(enabled).requestSendPolicy)
        val changed = update(enabled, "https://new.invalid")
        assertEquals(0, changed.requestSendPolicy.version)
        assertEquals(4, update(changed).requestSendPolicy.version)
        assertEquals(0, update(enabled, enabled = false).requestSendPolicy.version)
        org.junit.Assert.assertTrue(runCatching { positiveRequestBytes("-1") }.isFailure)
        assertEquals(null, positiveRequestBytes(""))
    }

    @Test
    fun contextCapacityDescriptionsDistinguishAutomaticSemanticsAndManualOverride() {
        val model = Model("m", "fixture", "Fixture", contextWindow = 128_000)
        assertEquals(R.string.provider_context_kind_unknown, contextWindowKindResource(model))
        assertEquals(R.string.provider_context_kind_total, contextWindowKindResource(model.copy(contextWindowKind = "total")))
        assertEquals(R.string.provider_context_kind_input, contextWindowKindResource(model.copy(contextWindowKind = "input")))
        assertEquals(R.string.provider_context_kind_unknown,
            contextWindowKindResource(model.copy(contextWindowKind = "total", contextWindowOverride = 64_000)))
    }
    @Test
    fun providerDraftSaverPreservesUnsavedConfiguration() {
        val draft = ProviderConfigDraft(
            headers = listOf(ProviderHeaderDraft(header = io.github.mangi.eta.data.model.CustomHeader("User-Agent", "test-client"))),
            name = "临时提供商",
            baseUrl = "https://api.example.com/v1",
            apiKey = "temporary-key",
            systemPrompt = "临时提示词",
            isEnabled = false,
            endpointMode = "responses",
            hostedWebSearchEnabled = true,
            anthropicVersion = "2023-06-01",
            requestByteBudgetKiB = "256.5",
            sendPolicyEnabled = true,
            observedRequestBytes = "1782914",
            hardRequestBytes = "3000000",
        )

        val saved = with(ProviderConfigDraftSaver) {
            SaverScope { true }.save(draft)
        }

        assertEquals(draft, ProviderConfigDraftSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun requestByteBudgetInputKeepsDefaultDisabledAndExactBytesWithoutOverflow() {
        assertNull(requestByteBudgetFromKiB("  "))
        assertEquals(0, requestByteBudgetFromKiB("0"))
        assertEquals(512 * 1024, requestByteBudgetFromKiB("512"))
        assertEquals(262656, requestByteBudgetFromKiB("256.5"))
        for (bytes in listOf(null, 0, 1, 512 * 1024, Int.MAX_VALUE)) {
            assertEquals(bytes, requestByteBudgetFromKiB(requestByteBudgetKiBText(bytes)))
        }
        for (invalid in listOf("-1", "2097152", "999999999999999999999", "0.001", "NaN", "1e3", "1,024")) {
            org.junit.Assert.assertTrue(requestByteBudgetInputError(invalid))
        }
        org.junit.Assert.assertFalse(requestByteBudgetInputError(""))
    }

    @Test
    fun updatedProviderCopiesPreserveBudgetUnlessExplicitlyChanged() {
        val providers = listOf<io.github.mangi.eta.data.model.ProviderSetting>(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting("o", "Fixture", "https://example.invalid", requestByteBudget = 128 * 1024),
            io.github.mangi.eta.data.model.AnthropicProviderSetting("a", "Fixture", "https://example.invalid", requestByteBudget = 128 * 1024),
            CustomProviderSetting("c", "Fixture", "https://example.invalid", requestByteBudget = 128 * 1024),
        )
        for (provider in providers) {
            val draft = ProviderConfigDraft.from(provider)
            assertEquals("128", draft.requestByteBudgetKiB)
            val saved = with(ProviderConfigDraftSaver) { SaverScope { true }.save(draft) }
            assertEquals(draft, ProviderConfigDraftSaver.restore(requireNotNull(saved)))
            val unchanged = buildUpdatedProvider(provider, draft.name, draft.baseUrl, draft.apiKey,
                draft.systemPrompt, draft.isEnabled, draft.endpointMode, draft.hostedWebSearchEnabled,
                draft.anthropicVersion, emptyList())
            assertEquals(128 * 1024, unchanged.requestByteBudget)
            val disabled = buildUpdatedProvider(provider, draft.name, draft.baseUrl, draft.apiKey,
                draft.systemPrompt, draft.isEnabled, draft.endpointMode, draft.hostedWebSearchEnabled,
                draft.anthropicVersion, emptyList(), requestByteBudget = 0)
            assertEquals(0, disabled.requestByteBudget)
            val restoredDefault = buildUpdatedProvider(provider, draft.name, draft.baseUrl, draft.apiKey,
                draft.systemPrompt, draft.isEnabled, draft.endpointMode, draft.hostedWebSearchEnabled,
                draft.anthropicVersion, emptyList(), requestByteBudget = null)
            assertNull(restoredDefault.requestByteBudget)
        }
    }

    @Test
    fun validatesOptionalPositiveContextWindowOverride() {
        assertNull(contextWindowInputError(""))
        assertEquals(null, contextWindowInputError(" 256000 "))
        assertEquals("Context window must be a positive integer", contextWindowInputError("0"))
        assertEquals("Context window must be a positive integer", contextWindowInputError("999999999999"))
    }

    @Test
    fun automaticContextWindowDisplaysWithoutCreatingAnOverride() {
        val draft = ContextWindowEditDraft(128_000, null)
        assertEquals("128000", draft.text)
        assertNull(draft.overrideForSave())
        assertNull(draft.copy(text = " 128000 ").overrideForSave())
        assertEquals(64_000, draft.copy(text = "64000").overrideForSave())
    }

    @Test
    fun storedOverrideSurvivesUnchangedSaveAndClearingRestoresAutomatic() {
        val draft = ContextWindowEditDraft(128_000, 64_000)
        assertEquals("64000", draft.text)
        assertEquals(64_000, draft.overrideForSave())
        assertEquals(128_000, ContextWindowEditDraft(128_000, 128_000).overrideForSave())
        assertNull(draft.copy(text = "").overrideForSave())
        assertNull(contextWindowInputError("   "))
    }

    @Test
    fun refreshedWindowUpdatesPristineDraftButPreservesUserEdits() {
        val draft = ContextWindowEditDraft(128_000, null)
        val refreshed = draft.refresh(256_000, null)
        assertEquals("256000", refreshed.text)
        assertNull(refreshed.overrideForSave())
        val manualRefresh = ContextWindowEditDraft(128_000, 64_000).refresh(256_000, 96_000)
        assertEquals("96000", manualRefresh.text)
        assertEquals(96_000, manualRefresh.overrideForSave())
        val edited = draft.copy(text = "96000").refresh(256_000, null)
        assertEquals("96000", edited.text)
        assertEquals(96_000, edited.overrideForSave())
        val cleared = draft.copy(text = "").refresh(256_000, null)
        assertEquals("", cleared.text)
        assertNull(cleared.overrideForSave())
    }

    @Test
    fun unknownWindowRemainsBlankAndDoesNotInventCapacity() {
        val draft = ContextWindowEditDraft(null, null)
        assertEquals("", draft.text)
        assertNull(draft.overrideForSave())
        assertEquals("128000", draft.refresh(128_000, null).text)
    }
    @Test
    fun knownSourcesMapToDistinctBrandLogos() {
        val expected = mapOf(
            ProviderSourceTypes.OPENAI to R.drawable.provider_logo_openai,
            ProviderSourceTypes.OPENAI_CODEX to R.drawable.provider_logo_codex,
            ProviderSourceTypes.ANTHROPIC to R.drawable.provider_logo_anthropic,
            ProviderSourceTypes.BAILIAN to R.drawable.provider_logo_bailian,
            ProviderSourceTypes.DEEPSEEK to R.drawable.provider_logo_deepseek,
            ProviderSourceTypes.MOONSHOT to R.drawable.provider_logo_kimi,
            ProviderSourceTypes.MIMO to R.drawable.provider_logo_mimo,
            ProviderSourceTypes.MINIMAX to R.drawable.provider_logo_minimax,
            ProviderSourceTypes.STEPFUN to R.drawable.provider_logo_stepfun,
            ProviderSourceTypes.SILICONFLOW to R.drawable.provider_logo_siliconflow,
            ProviderSourceTypes.OPENROUTER to R.drawable.provider_logo_openrouter,
        )

        expected.forEach { (sourceType, logo) ->
            assertEquals(logo, providerBrandLogoRes(sourceType))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun everyBuiltInProviderHasABrandLogo() {
        val logos = BuiltinProviders.PROVIDERS.map(::providerBrandLogoRes)

        assertEquals(BuiltinProviders.PROVIDERS.size, logos.filterNotNull().size)
        assertEquals(BuiltinProviders.PROVIDERS.size, logos.filterNotNull().toSet().size)
    }

    @Test
    fun customProviderUsesRecognizedBaseUrlAndUnknownSourceFallsBack() {
        val recognized = CustomProviderSetting(
            id = "custom-deepseek",
            name = "DeepSeek 副本",
            baseUrl = "https://api.deepseek.com/v1",
        )
        val unknown = CustomProviderSetting(
            id = "custom-unknown",
            name = "自定义",
            baseUrl = "https://api.example.com/v1",
        )

        assertEquals(R.drawable.provider_logo_deepseek, providerBrandLogoRes(recognized))
        assertNull(providerBrandLogoRes(unknown))
    }

    @Test
    fun modelSearchSupportsCaseInsensitiveFuzzyTokensAcrossNameAndId() {
        val models = listOf(
            model(id = "1", modelId = "openai/gpt-5", displayName = "GPT 5", sortOrder = 2),
            model(id = "2", modelId = "deepseek/deepseek-v3", displayName = "DeepSeek V3", sortOrder = 1),
            model(
                id = "3",
                modelId = "liquid/lfm-2.5-2.6b:free",
                displayName = "LiquidAI: LFM2.5-2.6B (free)",
                sortOrder = 3,
            ),
        )

        assertEquals(listOf("1"), filterProviderModels(models, "  gPt  ").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "  DEEPSEEK-V3  ").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "deep v3").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "dsv3").map { it.id })
        assertEquals(listOf("2"), filterProviderModels(models, "deep___v3").map { it.id })
        assertEquals(listOf("3"), filterProviderModels(models, "liquid 2.6b").map { it.id })
        assertEquals(emptyList<Model>(), filterProviderModels(models, "3vds"))
    }

    @Test
    fun modelSearchKeepsSortOrderForBlankQueryAndReturnsEmptyForNoMatch() {
        val models = listOf(
            model(id = "later", modelId = "model-b", displayName = "Model B", sortOrder = 20),
            model(id = "first", modelId = "model-a", displayName = "Model A", sortOrder = 10),
        )

        assertEquals(listOf("first", "later"), filterProviderModels(models, "   ").map { it.id })
        assertEquals(listOf("first", "later"), filterProviderModels(models, "---").map { it.id })
        assertEquals(emptyList<Model>(), filterProviderModels(models, "missing"))
    }

    private fun model(
        id: String,
        modelId: String,
        displayName: String,
        sortOrder: Int,
    ) = Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        sortOrder = sortOrder,
    )
}
