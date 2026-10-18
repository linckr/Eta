package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRequestByteCompactionTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
        contextWindow = 960_000,
    )

    @Test fun defaultBytePressureCompactsDespiteLargeManualTokenWindow() {
        val messages = messages(600_000)
        val events = mutableListOf<AgentEvent>()
        val budget = AgentRequestBudget.evaluate(config, messages, JSONArray(), ProviderRequestPurpose.CHAT)
        assertTrue(budget.requestBytes > 512 * 1024)
        assertTrue(budget.estimatedTokens < 960_000 * 0.85)
        val session = session(config, messages, events = events)

        assertTrue(session.prepareForRequest(budget.estimatedTokens, budget.requestBytes))

        assertEquals("KEEP_LATEST_REQUEST", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertNotNull(session.snapshot())
        assertEquals("CONTEXT_REQUEST_BYTE_BUDGET", events.filterIsInstance<AgentEvent.ContextCompaction>()
            .single { it.phase == AgentEvent.ContextCompaction.PHASE_STARTED }.reasonCode)
    }

    @Test fun unknownWindowCanUseBytePolicyButZeroAndAutoOffDisableIt() {
        for (disabled in listOf(false, true)) {
            val current = config.copy(contextWindow = null, requestByteBudget = if (disabled) 0 else 8000)
            val messages = messages(8_000)
            val original = messages.toString()
            val budget = AgentRequestBudget.evaluate(current, messages, JSONArray(), ProviderRequestPurpose.CHAT)
            val session = session(current, messages)
            assertEquals(!disabled, session.prepareForRequest(budget.estimatedTokens, budget.requestBytes))
            if (disabled) assertEquals(original, messages.toString()) else assertNotNull(session.snapshot())
        }
        val messages = messages(8_000)
        val original = messages.toString()
        val disabled = config.copy(autoCompactionEnabled = false, contextWindow = 100, requestByteBudget = 1)
        val session = session(disabled, messages, server = provider { fail("Automatic compaction is off"); response("unused") })
        session.observeInputTokens(1_000_000)
        assertFalse(session.prepareForRequest(1_000_000, 1_000_000))
        assertEquals(original, messages.toString())
    }

    @Test fun negativeLegacyRuntimeBudgetFallsBackToDefaultInsteadOfDisablingPolicy() {
        val current = config.copy(requestByteBudget = -1)
        val messages = messages(600_000)
        val budget = AgentRequestBudget.evaluate(current, messages, JSONArray(), ProviderRequestPurpose.CHAT)
        assertTrue(session(current, messages).prepareForRequest(budget.estimatedTokens, budget.requestBytes))
    }

    @Test fun observedUsageAndBytePressureShareOnePreflightCompactionAttempt() {
        val messages = messages(8_000)
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val session = session(config.copy(requestByteBudget = 16_000), messages, events,
            provider { summaries++; response("summary") })
        session.observeInputTokens(900_000)

        assertTrue(session.prepareForRequest(100, 20_000))

        assertEquals(1, summaries)
        assertEquals("CONTEXT_TOKEN_AND_BYTE_BUDGET", events.filterIsInstance<AgentEvent.ContextCompaction>()
            .single { it.phase == AgentEvent.ContextCompaction.PHASE_STARTED }.reasonCode)
    }

    @Test fun pendingToolTailIsPreservedAndSignedAnthropicTailCannotBeRewritten() {
        val messages = messages(8_000)
        val pending = toolCall("pending")
        messages.put(pending)
        val session = session(config.copy(requestByteBudget = 16_000), messages)

        assertTrue(session.prepareForRequest(100, 20_000))

        assertEquals(pending.toString(), messages.getJSONObject(messages.length() - 1).toString())
        assertEquals("KEEP_LATEST_REQUEST", messages.getJSONObject(messages.length() - 2).getString("content"))

        val signed = messages(8_000)
        val assistant = toolCall("signed")
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "thinking").put("signature", "signature"))
            .put(JSONObject().put("type", "tool_use").put("id", "signed").put("name", "test").put("input", JSONObject())))
        signed.put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "signed").put("content", "completed"))
        val original = signed.toString()
        val locked = session(config.copy(requestByteBudget = 1), signed,
            server = provider { fail("Cannot replace a signed pending round"); response("unused") })
        assertFalse(locked.prepareForRequest(1_000_000, 1_000_000))
        assertEquals(original, signed.toString())
        assertNull(locked.snapshot())
    }

    @Test fun toolSchemaStillOverBudgetAfterSummaryDoesNotCauseASecondCompactionOrHardReject() {
        val events = mutableListOf<AgentEvent>()
        var chats = 0
        val messages = messages(8_000)
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "test").put("description", "x".repeat(20_000))
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
        val loop = AgentLoop(config.copy(requestByteBudget = 16_000), messages, tools, provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) response("summary")
            else {
                chats++
                assertEquals(tools.toString(), request.tools.toString())
                response("complete")
            }
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), events::add, systemCount = 1)

        assertEquals("complete", loop.run().content)

        assertEquals(1, chats)
        assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompaction>().count {
            it.phase == AgentEvent.ContextCompaction.PHASE_STARTED
        })
    }

    @Test fun nonCompactableCurrentPromptAndUnhelpfulSummaryRemainSoftAndKeepOriginalRequest() {
        val currentOnly = JSONArray().put(JSONObject().put("role", "user").put("content", "x".repeat(8_000)))
        val original = currentOnly.toString()
        val session = AgentContextSession(config.copy(requestByteBudget = 1), currentOnly, 0, "current-only",
            provider { fail("Current prompt alone cannot be summarized away"); response("unused") },
            AgentRunController(), { emptySet() }, {}, {})
        assertFalse(session.prepareForRequest(100, 20_000))
        assertEquals(original, currentOnly.toString())

        val shortHistory = messages(10)
        val shortOriginal = shortHistory.toString()
        val unhelpful = session(config.copy(requestByteBudget = 16_000), shortHistory,
            server = provider { response("x".repeat(4_000)) })
        assertFalse(unhelpful.prepareForRequest(100, 20_000))
        assertEquals(shortOriginal, shortHistory.toString())
        assertNull(unhelpful.snapshot())
    }

    @Test fun byteOnlySummaryFailuresKeepOriginalHistoryAndStillSendChat() {
        val failures = listOf(
            AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture summary timeout"),
            AgentModelFailure("HTTP_400", false, "fixture invalid request"),
            AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture invalid structure"),
        )
        for (failure in failures) {
            val messages = messages(20_000)
            val original = JSONArray(messages.toString())
            var chats = 0
            val events = mutableListOf<AgentEvent>()
            val loop = AgentLoop(config.copy(requestByteBudget = 16_000), messages, JSONArray(), provider { request ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) throw failure
                chats++
                response("complete")
            }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), events::add, systemCount = 1)

            assertEquals("complete", loop.run().content)
            assertEquals(1, chats)
            assertEquals(4, messages.length())
            for (index in 0 until original.length()) {
                assertEquals(original.get(index).toString(), messages.get(index).toString())
            }
            assertNull(loop.contextSnapshot())
            assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any {
                it.phase == "failed" && it.reasonCode == failure.code
            })
        }
    }

    @Test fun tokenPressureSummaryFailureStillAbortsAndKeepsHistory() {
        val tokenConfig = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
            contextWindow = 8_000, requestByteBudget = 0,
        )
        val messages = messages(30_000)
        val original = messages.toString()
        var chats = 0
        val loop = AgentLoop(tokenConfig, messages, JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("HTTP_400", false, "fixture invalid request")
            }
            chats++
            response("complete")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("HTTP_400", failure.code)
        assertEquals(0, chats)
        assertEquals(original, messages.toString())
        assertNull(loop.contextSnapshot())
    }

    @Test fun checkpointFailureStillAbortsEvenWhenBytePressureOnly() {
        val messages = messages(20_000)
        val original = messages.toString()
        var chats = 0
        val loop = AgentLoop(config.copy(requestByteBudget = 16_000), messages, JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_CHECKPOINT_FAILED", false, "fixture checkpoint failure")
            }
            chats++
            response("complete")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("CONTEXT_SUMMARY_CHECKPOINT_FAILED", failure.code)
        assertEquals(0, chats)
        assertEquals(original, messages.toString())
    }

    @Test fun cancellationDuringByteOnlyCompactionStillAborts() {
        val messages = messages(20_000)
        val controller = AgentRunController()
        val session = AgentContextSession(config.copy(requestByteBudget = 16_000), messages, 1, "cancel-bytes",
            provider { controller.cancel(); response("summary") }, controller, { emptySet() }, {}, {})
        assertThrows(AgentRunCancelledException::class.java) { session.prepareForRequest(100, 20_000) }
    }

    @Test fun byteOnlySummaryInputBudgetFailureFallsBackWithoutNetwork() {
        val messages = messages(8000)
        val before = messages.toString()
        val session = session(config.copy(requestByteBudget = 1024), messages,
            server = provider { error("Preflight must reject before network") })
        assertFalse(session.prepareForRequest(100, 20_000))
        assertEquals(before, messages.toString())
        assertNull(session.snapshot())
    }

    @Test fun newModelBudgetIsRecomputedWithoutAStickyCompactionMarker() {
        val messages = messages(50_000)
        val original = messages.toString()
        val oldConfig = config.copy(requestByteBudget = 0)
        val oldBudget = AgentRequestBudget.evaluate(oldConfig, messages, JSONArray(), ProviderRequestPurpose.CHAT)
        assertFalse(session(oldConfig, messages).prepareForRequest(oldBudget.estimatedTokens, oldBudget.requestBytes))
        assertEquals(original, messages.toString())

        val newConfig = oldConfig.copy(model = "smaller", contextWindow = 16_000, contextWindowKind = "input", inputTokenLimit = 16_000)
        val newBudget = AgentRequestBudget.evaluate(newConfig, messages, JSONArray(), ProviderRequestPurpose.CHAT)
        val events = mutableListOf<AgentEvent>()
        assertTrue(session(newConfig, messages, events = events).prepareForRequest(newBudget.estimatedTokens, newBudget.requestBytes))
        assertEquals("CONTEXT_TOKEN_BUDGET", events.filterIsInstance<AgentEvent.ContextCompaction>()
            .single { it.phase == AgentEvent.ContextCompaction.PHASE_STARTED }.reasonCode)
    }

    @Test fun tokenAndBytePressureSummaryFailureDoesNotUseByteOnlyFallback() {
        val bothConfig = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
            contextWindow = 8_000, requestByteBudget = 16_000,
        )
        val messages = messages(30_000)
        val original = messages.toString()
        var chats = 0
        val loop = AgentLoop(bothConfig, messages, JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture summary timeout")
            }
            chats++
            response("complete")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("CONTEXT_SUMMARY_TIMEOUT", failure.code)
        assertEquals(0, chats)
        assertEquals(original, messages.toString())
        assertNull(loop.contextSnapshot())
    }

    @Test fun unknownFailureCodeStillPropagatesUnderByteOnlyPressure() {
        val messages = messages(20_000)
        val original = messages.toString()
        var chats = 0
        val loop = AgentLoop(config.copy(requestByteBudget = 16_000), messages, JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_UNKNOWN_FUTURE_FAILURE", false, "fixture unknown failure")
            }
            chats++
            response("complete")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("CONTEXT_SUMMARY_UNKNOWN_FUTURE_FAILURE", failure.code)
        assertEquals(0, chats)
        assertEquals(original, messages.toString())
    }

    @Test fun byteOnlyFallbackSendsOnceWithoutReplayingCompletedToolRound() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "fixed"))
            .put(toolCall("done-1"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "done-1").put("content", "previous result"))
            .put(JSONObject().put("role", "user").put("content", "KEEP_LATEST_REQUEST"))
            .put(JSONObject().put("role", "assistant").put("content", "x".repeat(20_000)))
            .put(JSONObject().put("role", "user").put("content", "continue"))
        val original = JSONArray(messages.toString())
        var chats = 0
        val loop = AgentLoop(config.copy(requestByteBudget = 16_000), messages, JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture summary timeout")
            }
            chats++
            response("complete")
        }, { error("completed tool history must not be replayed") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)

        assertEquals("complete", loop.run().content)
        assertEquals(1, chats)
        assertEquals(original.length() + 1, messages.length())
        for (index in 0 until original.length()) {
            assertEquals(original.get(index).toString(), messages.get(index).toString())
        }
        assertNull(loop.contextSnapshot())
    }

    @Test fun byteOnlyFallbackDoesNotMaskTheFollowingChatFailure() {
        val messages = messages(20_000)
        val original = messages.toString()
        var chats = 0
        val loop = AgentLoop(config.copy(requestByteBudget = 16_000), messages, JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture summary timeout")
            }
            chats++
            throw AgentModelFailure("HTTP_400", false, "fixture chat rejected")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("HTTP_400", failure.code)
        assertEquals(1, chats)
        assertEquals(original, messages.toString())
    }

    private fun messages(length: Int) = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
        .put(JSONObject().put("role", "assistant").put("content", "x".repeat(length)))
        .put(JSONObject().put("role", "user").put("content", "KEEP_LATEST_REQUEST"))
    private fun toolCall(id: String) = JSONObject().put("role", "assistant").put("content", "")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "test").put("arguments", "{}"))))
    private fun session(currentConfig: AgentModelClient.ModelConfig, messages: JSONArray,
        events: MutableList<AgentEvent> = mutableListOf(), server: AgentProviderClient = provider { response("summary") }) =
        AgentContextSession(currentConfig, messages, 1, "fixture", server, AgentRunController(), { emptySet() }, events::add, {})
    private fun response(content: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", content).put("finish_reason", "stop"))
    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = block(request)
    }
}
