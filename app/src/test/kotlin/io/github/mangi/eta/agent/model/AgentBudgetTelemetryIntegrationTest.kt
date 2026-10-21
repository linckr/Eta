package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration coverage for the read-only budget telemetry protocol: pre/post request rounds,
 * one terminal decision per compaction attempt, deferred rounds, fallback compatibility and
 * cancellation/post-measurement completeness. No request budget policy is exercised here.
 */
class AgentBudgetTelemetryIntegrationTest {
    private val collector = mutableListOf<String>()
    private val baseConfig = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
        contextWindow = 960_000,
    )

    @Test fun noPressureCountsOnlyOnePreBudgetLine() {
        val loop = loop(messages(100), budget = 16_000)
        loop.run()

        assertEquals(1, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        assertTrue(decisionEntries().isEmpty())
    }

    @Test fun repeatedNoPressureRoundsLogOnePreLineEach() {
        val session = session(messages(100), budget = 16_000)

        repeat(3) { assertFalse(session.prepareForRequest(100, 1_000)) }

        assertEquals(3, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        assertTrue(decisionEntries().isEmpty())
    }

    @Test fun successfulByteCompactionCountsOneTerminalAttemptAndOnePostMeasurement() {
        val loop = loop(messages(20_000), budget = 16_000)
        assertEquals("complete", loop.run().content)

        assertEquals(1, budgetLines("pre"))
        assertEquals(1, budgetLines("post"))
        val done = decisions("compact_done")
        assertEquals(1, done.size)
        assertTrue(done.single().getValue("duration_ms").toLong() >= 0)
        assertTrue(done.single().containsKey("bytes_after"))
        assertEquals("bytes", done.single()["trigger"])
        assertEquals("preflight", done.single()["source"])
        assertTrue(done.single().containsKey("decision_id"))
        assertEquals(preDecisionId(), done.single()["decision_id"])
        assertEquals(1, terminalEntries().size)
    }

    @Test fun byteFallbackCountsOneTerminalAttemptAndKeepsLegacyLine() {
        val loop = loop(messages(20_000), budget = 16_000, fakeProvider = { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture")
            }
            response("complete")
        })
        assertEquals("complete", loop.run().content)

        assertEquals(1, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        val fallback = decisions("fallback")
        assertEquals(1, fallback.size)
        assertTrue(fallback.single().getValue("duration_ms").toLong() >= 0)
        assertEquals("CONTEXT_SUMMARY_TIMEOUT", fallback.single()["code"])
        assertEquals(preDecisionId(), fallback.single()["decision_id"])
        assertEquals(1, terminalEntries().size)
        assertEquals(1, legacyFallbackLines("send_original_after_summary_failure"))
    }

    @Test fun softNoOpCountsOneTerminalAttemptAndKeepsLegacyLine() {
        val currentOnly = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "fixed"))
            .put(JSONObject().put("role", "user").put("content", "KEEP_LATEST_REQUEST"))
        val loop = loop(currentOnly, budget = 1)
        assertEquals("complete", loop.run().content)

        assertEquals(1, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        val softNoop = decisions("soft_noop")
        assertEquals(1, softNoop.size)
        assertEquals("CONTEXT_NOT_COMPACTABLE", softNoop.single()["code"])
        assertEquals(1, terminalEntries().size)
        assertEquals(1, legacyFallbackLines("send_original"))
    }

    @Test fun tokenPressureHardFailureCountsOneTerminalAttemptAndStillAborts() {
        val tokenConfig = baseConfig.copy(contextWindow = 8_000, requestByteBudget = 0)
        var chats = 0
        val loop = AgentLoop(tokenConfig, messages(30_000), JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("HTTP_400", false, "fixture invalid request")
            }
            chats++
            response("complete")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1,
            onBudgetLog = collector::add)

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("HTTP_400", failure.code)
        assertEquals(0, chats)
        assertEquals(1, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        val hard = decisions("hard_failure")
        assertEquals(1, hard.size)
        assertEquals("token", hard.single()["trigger"])
        assertEquals("HTTP_400", hard.single()["code"])
        assertTrue(hard.single().getValue("duration_ms").toLong() >= 0)
        assertEquals(preDecisionId(), hard.single()["decision_id"])
        assertEquals(1, terminalEntries().size)
    }

    @Test fun bothPressureFailureKeepsRawTriggerAndCountsOneAttempt() {
        val bothConfig = baseConfig.copy(contextWindow = 8_000, requestByteBudget = 16_000)
        val loop = AgentLoop(bothConfig, messages(30_000), JSONArray(), provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture")
            }
            response("complete")
        }, { error("No local tool replay") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1,
            onBudgetLog = collector::add)

        assertThrows(AgentModelFailure::class.java) { loop.run() }

        val hard = decisions("hard_failure")
        assertEquals(1, hard.size)
        assertEquals("both", hard.single()["trigger"])
        assertEquals("true", hard.single()["essential"])
        assertEquals(1, terminalEntries().size)
    }

    @Test fun deferredSignedToolRoundIsNotCountedAsAttempt() {
        val signedMessages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "fixed"))
            .put(signedToolAssistant())
        val session = session(signedMessages, budget = 1)

        assertFalse(session.prepareForRequest(100, 20_000))

        assertEquals(1, budgetLines("pre"))
        val deferred = decisions("deferred")
        assertEquals(1, deferred.size)
        assertEquals("SIGNED_TOOL_ROUND", deferred.single()["code"])
        assertEquals(preDecisionId(), deferred.single()["decision_id"])
        assertTrue(terminalEntries().isEmpty())
        assertEquals(0, legacyLines().size)
    }

    @Test fun byteFailureDefersNextByteRoundWithoutNewAttemptUntilUserTurns() {
        var summaries = 0
        val active = AgentContextSession(
            config = baseConfig.copy(requestByteBudget = 16_000),
            messages = messages(20_000),
            systemCount = 1,
            operationId = "telemetry-fixture",
            provider = provider { request ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    throw AgentModelFailure("CONTEXT_SUMMARY_TIMEOUT", false, "fixture")
                }
                response("complete")
            },
            runController = AgentRunController(),
            sensitiveIds = { emptySet() },
            onContextSnapshot = {},
            onBudgetLog = collector::add,
        )

        assertFalse(active.prepareForRequest(100, 20_000))
        assertEquals(1, summaries)
        assertFalse(active.prepareForRequest(100, 21_000))
        assertEquals(1, summaries)

        assertEquals(2, budgetLines("pre"))
        val deferred = decisions("deferred")
        assertEquals(1, deferred.size)
        assertEquals("BYTE_SUMMARY_FAILED_THIS_TURN", deferred.single()["code"])
        assertEquals(preDecisionIds()[1], deferred.single()["decision_id"])
        assertEquals(1, decisions("fallback").size)
        assertEquals(1, terminalEntries().size)

        active.userAppended()
        assertFalse(active.prepareForRequest(100, 22_000))
        assertEquals(2, summaries)
        assertEquals(2, decisions("fallback").size)
        assertEquals(2, terminalEntries().size)
    }

    @Test fun cancellationEndsTheAttemptWithRunCancelledAndPropagates() {
        val controller = AgentRunController()
        val messages = messages(20_000)
        val session = session(messages, budget = 16_000, controller = controller, customProvider = {
            controller.cancel()
            response("summary")
        })

        assertThrows(AgentRunCancelledException::class.java) { session.prepareForRequest(100, 20_000) }

        val hard = decisions("hard_failure")
        assertEquals(1, hard.size)
        assertEquals("RUN_CANCELLED", hard.single()["code"])
        assertTrue(hard.single().getValue("duration_ms").toLong() >= 0)
        assertEquals(1, terminalEntries().size)
    }

    @Test fun cancelledPostMeasurementIsRecordedAsCompactDoneOnce() {
        val session = session(messages(20_000), budget = 16_000)
        assertTrue(session.prepareForRequest(100, 20_000))

        session.reportRequestBudgetFailure(AgentRunCancelledException())

        val done = decisions("compact_done")
        assertEquals(1, done.size)
        assertEquals("POST_BUDGET_CANCELLED", done.single()["code"])
    }

    @Test fun checkpointFailureCountsOneHardFailureTerminal() {
        val loop = loop(messages(20_000), budget = 16_000, fakeProvider = { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                throw AgentModelFailure("CONTEXT_SUMMARY_CHECKPOINT_FAILED", false, "fixture")
            }
            response("complete")
        })

        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }

        assertEquals("CONTEXT_SUMMARY_CHECKPOINT_FAILED", failure.code)
        assertEquals(1, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        val hard = decisions("hard_failure")
        assertEquals(1, hard.size)
        assertEquals("CONTEXT_SUMMARY_CHECKPOINT_FAILED", hard.single()["code"])
        assertEquals(1, terminalEntries().size)
    }

    @Test fun summaryInputBudgetFailureFallsBackWithOneAttempt() {
        val session = session(messages(8_000), budget = 1_024)

        assertFalse(session.prepareForRequest(100, 20_000))

        assertEquals(1, budgetLines("pre"))
        assertEquals(0, budgetLines("post"))
        val fallback = decisions("fallback")
        assertEquals(1, fallback.size)
        assertEquals("CONTEXT_SUMMARY_INPUT_BUDGET", fallback.single()["code"])
        assertEquals(1, terminalEntries().size)
        assertEquals(1, legacyFallbackLines("send_original_after_summary_failure"))
    }

    private fun budgetLines(phase: String) =
        collected("context_request_budget").count { fields(it)["phase"] == phase }

    private fun preDecisionId(): String =
        fields(collected("context_request_budget").first { fields(it)["phase"] == "pre" }).getValue("decision_id")

    private fun preDecisionIds(): List<String> =
        collected("context_request_budget")
            .filter { fields(it)["phase"] == "pre" }
            .map { fields(it).getValue("decision_id") }

    private fun legacyFallbackLines(action: String) =
        collected("context_request_budget_fallback").count { fields(it)["action"] == action }

    private fun legacyLines() = collected("context_request_budget_fallback")

    private fun decisionEntries() = collected("context_budget_decision").map(::fields)

    private fun decisions(action: String) = decisionEntries().filter { it["action"] == action }

    private fun terminalEntries() = decisionEntries().filter {
        it["action"] in setOf("compact_done", "fallback", "soft_noop", "hard_failure")
    }

    private fun collected(prefix: String) = collector.filter { it.startsWith("$prefix ") }

    private fun fields(line: String): Map<String, String> =
        line.substringAfter(' ').split(' ')
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator > 0) token.substring(0, separator) to token.substring(separator + 1) else null
            }
            .toMap()

    private fun loop(
        messages: JSONArray,
        budget: Int,
        fakeProvider: ((ProviderRequest) -> ProviderResponse)? = null,
    ): AgentLoop = AgentLoop(
        baseConfig.copy(requestByteBudget = budget), messages, JSONArray(),
        fakeProvider?.let { block -> provider(block) } ?: defaultProvider(),
        { error("No local tool replay") },
        AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1, onBudgetLog = collector::add,
    )

    private fun defaultProvider() = provider { request ->
        if (request.purpose == ProviderRequestPurpose.COMPACTION) response("summary") else response("complete")
    }

    private fun session(
        messages: JSONArray,
        budget: Int,
        controller: AgentRunController = AgentRunController(),
        customProvider: (() -> ProviderResponse)? = null,
    ) = AgentContextSession(
        config = baseConfig.copy(requestByteBudget = budget),
        messages = messages,
        systemCount = 1,
        operationId = "telemetry-fixture",
        provider = if (customProvider == null) defaultProvider() else provider { customProvider() },
        runController = controller,
        sensitiveIds = { emptySet() },
        onContextSnapshot = {},
        onBudgetLog = collector::add,
    )

    private fun signedToolAssistant(): JSONObject = JSONObject().apply {
        put("role", "assistant")
        put("content", "")
        put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "signed-tool")
            .put("type", "function")
            .put("function", JSONObject().put("name", "test").put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(this, JSONArray().put(
            JSONObject().put("type", "thinking").put("thinking", "fixture").put("signature", "signature"),
        ))
    }

    private fun messages(length: Int) = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "fixed"))
        .put(JSONObject().put("role", "assistant").put("content", "x".repeat(length)))
        .put(JSONObject().put("role", "user").put("content", "KEEP_LATEST_REQUEST"))

    private fun response(content: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", content).put("finish_reason", "stop"))

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = block(request)
    }
}

