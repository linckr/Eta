package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderTypes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionModelTest {
    @Test fun summaryTurnsOffReasoningOnlyWithExplicitOptionalCapabilityAndKeepsChatSettings() {
        val optional = io.github.mangi.eta.data.model.ModelReasoningCapabilities(
            supportedEfforts = listOf(io.github.mangi.eta.data.model.ReasoningEffort.HIGH), canDisable = true)
        for (caps in listOf(optional, optional.copy(mandatory = true), optional.copy(canDisable = false), null)) {
            val original = chat.copy(thinkingEnabled = true, reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.HIGH,
                reasoningCapabilities = caps)
            val off = caps?.let { it.canDisable && !it.mandatory } == true
            var called = false
            AgentContextSummarizer(original, provider { request ->
                called = true
                assertEquals(!off, request.config.thinkingEnabled)
                assertEquals(if (off) io.github.mangi.eta.data.model.ReasoningEffort.OFF else io.github.mangi.eta.data.model.ReasoningEffort.HIGH,
                    request.config.reasoningEffort)
                if (off) {
                    val body = OpenAiResponsesProvider.buildRequestJson(request.effectiveConfig, request.messages, request.effectiveTools)
                    assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
                }
                response()
            }, AgentRunController(), false).summarize(history())
            assertTrue(called)
            assertTrue(original.thinkingEnabled)
            assertEquals(io.github.mangi.eta.data.model.ReasoningEffort.HIGH, original.reasoningEffort)
        }
    }
    private val chat = AgentModelClient.ModelConfig(baseUrl = "https://chat.invalid", apiKey = "fixture", model = "chat",
        systemPrompt = "fixture", contextWindow = 960_000)
    private val selected = chat.copy(providerType = ProviderTypes.ANTHROPIC, model = "summary", contextWindow = 12_000,
        hostedWebSearchEnabled = true, extraBodyJson = "{\"fixture\":true}")
    private fun history() = listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(8_000)))

    @Test fun explicitModelUsesItsProviderWindowAndCleanedCompactionPurposeOnly() {
        val requests = mutableListOf<ProviderRequest>()
        val original = history()
        var resolutions = 0
        val injected = provider { fail("Chat provider cannot receive independent summaries"); response() }
        val summarizer = AgentContextSummarizer(chat.copy(compactionModelId = "selected", compactionModelConfig = selected),
            injected, AgentRunController(), false, providerResolver = { config ->
                resolutions++
                assertEquals("summary", config.model)
                assertEquals(ProviderTypes.ANTHROPIC, config.providerType)
                assertEquals("", config.extraBodyJson)
                assertFalse(config.hostedWebSearchEnabled)
                provider { request -> requests += request; response() }
            })
        assertEquals("summary", summarizer.summarize(original))
        assertEquals(1, resolutions)
        assertTrue(requests.size >= 3)
        requests.forEach {
            assertEquals(ProviderRequestPurpose.COMPACTION, it.purpose)
            assertEquals("summary", it.config.model)
            assertEquals(0, it.effectiveTools.length())
            assertEquals("", it.effectiveConfig.extraBodyJson)
        }
        assertEquals(history(), original)
        assertEquals("chat", chat.model)
    }

    @Test fun defaultKeepsInjectedProviderAndUnavailableSelectionNeverFallsBack() {
        var calls = 0
        val injected = provider { calls++; response() }
        assertEquals("summary", AgentContextSummarizer(chat, injected, AgentRunController(), false,
            providerResolver = { fail("Default must keep injected provider"); injected }).summarize(history()))
        assertEquals(1, calls)
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(chat.copy(compactionModelId = "deleted"), injected, AgentRunController(), false)
                .summarize(history())
        }
        assertEquals("CONTEXT_COMPACTION_MODEL_UNAVAILABLE", failure.code)
        assertEquals(1, calls)
    }

    @Test fun selectedProviderCancellationDoesNotModifyOriginalMessages() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixture"))
            .put(AgentConversationCodec.toJsonObject(history().single()))
        val original = messages.toString()
        val controller = AgentRunController()
        val compactor = AgentContextCompactor(chat.copy(compactionModelConfig = selected), provider { fail("No fallback"); response() },
            controller, providerResolver = { provider { controller.cancel(); response() } })
        assertThrows(Exception::class.java) { compactor.compact(messages, 1, emptySet()) }
        assertEquals(original, messages.toString())
    }

    @Test fun selectedProviderFailureKeepsHistoryWithoutFallbackToChat() {
        val messages = JSONArray().put(AgentConversationCodec.toJsonObject(history().single()))
        val original = messages.toString()
        val compactor = AgentContextCompactor(chat.copy(compactionModelConfig = selected),
            provider { fail("No silent fallback to chat provider"); response() }, AgentRunController(),
            providerResolver = { provider { throw AgentModelFailure("MODEL_HTTP_400", false, "synthetic") } })
        assertEquals("MODEL_HTTP_400", assertThrows(AgentModelFailure::class.java) {
            compactor.compact(messages, 0, emptySet())
        }.code)
        assertEquals(original, messages.toString())
    }

    @Test fun signedPendingToolRoundIsProtectedBeforeResolvingSelectedProvider() {
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "signed").put("type", "function")
                .put("function", JSONObject().put("name", "test").put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "fixture").put("signature", "signature"))
            .put(JSONObject().put("type", "tool_use").put("id", "signed").put("name", "test").put("input", JSONObject())))
        val messages = JSONArray().put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "signed").put("content", "done"))
        val original = messages.toString()
        val injected = provider { fail("Cannot call a provider for a signed pending round"); response() }
        val compactor = AgentContextCompactor(chat.copy(compactionModelConfig = selected), injected, AgentRunController(),
            providerResolver = { fail("Must protect signature before resolving"); injected })
        assertEquals("ANTHROPIC_THINKING_CONTEXT_LOCKED", assertThrows(AgentModelFailure::class.java) {
            compactor.compact(messages, 0, emptySet())
        }.code)
        assertEquals(original, messages.toString())
    }

    private fun response() = ProviderResponse(JSONObject().put("content", "summary").put("finish_reason", "stop"))
    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "synthetic"
        override val capabilities = ProviderCapabilities(EndpointKind.ANTHROPIC_MESSAGES, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request)
    }
}
