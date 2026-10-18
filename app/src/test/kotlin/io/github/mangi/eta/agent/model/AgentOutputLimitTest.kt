package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentOutputLimitTest {
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "", contextWindow = 100_000)

    @Test fun totalInputAndUnknownWindowsDoNotConflateOutputReservation() {
        val config = config().copy(customBody = listOf(CustomBody("max_completion_tokens", JsonPrimitive(20_000))))
        assertEquals(80_000, AgentContextBudget.inputBudget(config.copy(contextWindowKind = "total")))
        assertEquals(100_000, AgentContextBudget.inputBudget(config.copy(contextWindowKind = "input")))
        assertEquals(100_000, AgentContextBudget.inputBudget(config))
        assertEquals(50_000, AgentContextBudget.inputBudget(config.copy(contextWindowKind = "total", inputTokenLimit = 50_000)))
        assertEquals(50_000, AgentContextBudget.inputBudget(config.copy(inputTokenLimit = 50_000)))
        assertEquals(50_000, AgentContextBudget.inputBudget(config.copy(contextWindow = null, inputTokenLimit = 50_000)))
        assertNull(AgentContextBudget.inputBudget(config.copy(contextWindow = null)))
    }

    @Test fun requestOptionsAndBudgetUseIdenticalMergesWithoutChangingOutputLimit() {
        val chat = config().copy(extraBodyJson = """{"max_completion_tokens":8192,"max_tokens":4096}""",
            customBody = listOf(CustomBody("max_completion_tokens", JsonPrimitive(12_000))))
        val chatRequest = JSONObject()
        AgentOutputLimit.applyChatOptions(chatRequest, chat)
        assertEquals(12_000, chatRequest.getInt("max_completion_tokens"))
        assertEquals(12_000, AgentOutputLimit.effective(chat))
        val responses = chat.copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
            extraBodyJson = """{"max_output_tokens":9000}""",
            customBody = listOf(CustomBody("max_output_tokens", JsonPrimitive(3000))))
        val request = ResponsesRequestBuilder.build(responses, JSONArray(), JSONArray())
        assertEquals(request.getInt("max_output_tokens"), AgentOutputLimit.effective(responses))
        assertEquals(3000, request.getInt("max_output_tokens"))
    }

    @Test fun anthropicDefaultsThinkingAndExplicitOutputCapsStayAligned() {
        val anthropic = config().copy(providerType = ProviderTypes.ANTHROPIC)
        assertEquals(4096, AgentOutputLimit.effective(anthropic))
        assertEquals(16_384, AgentOutputLimit.effective(anthropic.copy(model = "claude-fable-5-1")))
        val thinking = anthropic.copy(reasoningEffort = ReasoningEffort.XHIGH)
        assertEquals(65_536, AgentOutputLimit.effective(thinking))
        val explicit = thinking.copy(customBody = listOf(CustomBody("max_tokens", JsonPrimitive(8192))))
        assertEquals(8192, AgentOutputLimit.effective(explicit))
        val request = JSONObject()
        AgentOutputLimit.applyAnthropicOptions(request, explicit)
        assertEquals(8192, request.getInt("max_tokens"))
        assertEquals(91_808, AgentContextBudget.inputBudget(explicit.copy(contextWindowKind = "total")))
    }

    @Test fun nonChatPurposesUseClearedOptionsRatherThanOriginalOutputCap() {
        val config = config().copy(providerType = ProviderTypes.ANTHROPIC,
            customBody = listOf(CustomBody("max_tokens", JsonPrimitive(80_000))), extraBodyJson = """{"max_output_tokens":12345}""")
        for (purpose in listOf(ProviderRequestPurpose.COMPACTION, ProviderRequestPurpose.REPLY_REWRITE, ProviderRequestPurpose.TRANSCRIPT_REFINE)) {
            assertEquals(4096, AgentOutputLimit.effective(config, purpose))
            assertEquals(95_904, AgentContextBudget.inputBudget(config.copy(contextWindowKind = "total"), purpose))
        }
        assertEquals(80_000, AgentOutputLimit.effective(config))
        val responses = config.copy(providerType = ProviderTypes.OPENAI_COMPATIBLE, openAiEndpointMode = OpenAiEndpointMode.RESPONSES)
        assertNull(AgentOutputLimit.effective(responses, ProviderRequestPurpose.COMPACTION))
    }

    @Test fun absentOrInvalidCapsAreNotInventedAndNegativeBudgetsFailBeforeMutatingHistory() {
        assertNull(AgentOutputLimit.effective(config()))
        // RequestBodyMerge already converts numeric JsonPrimitive strings into wire numbers.
        assertEquals(8000, AgentOutputLimit.effective(config().copy(customBody = listOf(CustomBody("max_tokens", JsonPrimitive("8000"))))))
        for (value in listOf(JsonPrimitive("invalid"), JsonPrimitive(-1), JsonPrimitive(0), JsonPrimitive(1.5), JsonNull)) {
            assertNull(AgentOutputLimit.effective(config().copy(customBody = listOf(CustomBody("max_tokens", value)))))
        }
        for (limit in listOf(100_000, 120_000)) {
            val invalid = config().copy(contextWindowKind = "total", customBody = listOf(CustomBody("max_tokens", JsonPrimitive(limit))))
            assertEquals(0, AgentContextBudget.inputBudget(invalid))
            val failure = assertThrows(AgentModelFailure::class.java) { AgentContextBudget.requirePositiveBudget(invalid) }
            assertEquals("CONTEXT_OUTPUT_BUDGET_INVALID", failure.code)
            assertFalse(failure.contextRecoveryAllowed)
            assertTrue(failure.message!!.contains("降低输出上限"))
        }
    }
}
