package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummaryValidationTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixture")
    private val history = listOf(AgentModelClient.ConversationMessage("assistant", "SYNTHETIC_PRIVATE_SOURCE"))

    @Test fun nonTerminalReasonsHaveFixedMessagesAndSafeNumericReason() {
        listOf("length" to "生成上限", "content_filter" to "内容限制", "untrusted-private-endpoint" to "未确认").forEach { (stop, expected) ->
            val failure = failure(JSONObject().put("content", "SYNTHETIC_PRIVATE_REPLY").put("finish_reason", stop))
            assertTrue(failure.message!!.contains(expected))
            assertEquals(1, JSONObject(failure.diagnosticJson!!).getInt("summary_invalid_reason"))
            assertFalse(failure.message!!.contains(stop))
        }
    }

    @Test fun toolCallsAreRejectedEvenWhenFinishReasonSaysStop() {
        val assistant = JSONObject().put("content", "SYNTHETIC_PRIVATE_REPLY").put("finish_reason", "stop")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "private-id").put("type", "function")
                .put("function", JSONObject().put("name", "private-tool").put("arguments", "{}"))))
        val failure = failure(assistant)
        assertTrue(failure.message!!.contains("工具调用"))
        assertEquals(2, JSONObject(failure.diagnosticJson!!).getInt("summary_invalid_reason"))
        assertFalse(failure.diagnosticJson!!.contains("private-tool"))
    }

    @Test fun blankNullAndAbsentContentHaveEmptyBodyReason() {
        listOf<Any?>("", " \n\t", "null", JSONObject.NULL, null).forEach { value ->
            val response = JSONObject().put("finish_reason", "stop")
            if (value != null) response.put("content", value)
            val failure = failure(response)
            assertTrue(failure.message!!.contains("正文为空"))
            assertEquals(3, JSONObject(failure.diagnosticJson!!).getInt("summary_invalid_reason"))
        }
    }

    @Test fun oversizedSummaryStillFailsAfterBoundedUnsuccessfulRefinementsAndDoesNotSave() {
        val failure = failure(JSONObject().put("content", "x".repeat(12_001)).put("finish_reason", "stop"), expectedCalls = 3)
        assertTrue(failure.message!!.contains("12000"))
        assertTrue(failure.message!!.contains("12001"))
        assertEquals(4, JSONObject(failure.diagnosticJson!!).getInt("summary_invalid_reason"))
        assertEquals(12_001, JSONObject(failure.diagnosticJson!!).getInt("summary_chars"))
        assertTrue(failure.diagnosticJson!!.length < 200)
    }

    @Test fun exactLimitIsAcceptedAndStreamingToolEventCannotPassValidation() {
        val valid = provider { _, _ -> ProviderResponse(JSONObject().put("content", "x".repeat(12_000)).put("finish_reason", "stop")) }
        assertEquals(12_000, AgentContextSummarizer(config, valid, AgentRunController(), false).summarize(history).length)
        var calls = 0
        val stream = provider { _, event ->
            calls++
            event(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 0, "private-id", "private-tool"))
            error("Invalid streaming tool event must throw immediately")
        }
        val failure = assertThrows(AgentModelFailure::class.java) { AgentContextSummarizer(config, stream, AgentRunController(), false).summarize(history) }
        assertEquals(1, calls)
        assertEquals(2, JSONObject(failure.diagnosticJson!!).getInt("summary_invalid_reason"))
    }

    @Test fun diagnosticDropsOutOfRangeReasonsCountsAndArbitraryResponseText() {
        val safe = JSONObject(ModelFailureDiagnostic.sanitize(JSONObject().put("code", "CONTEXT_SUMMARY_INVALID")
            .put("summary_invalid_reason", 5).put("summary_chars", -1).put("body", "SYNTHETIC_PRIVATE_REPLY").toString())!!)
        assertFalse(safe.has("summary_invalid_reason"))
        assertFalse(safe.has("summary_chars"))
        assertFalse(safe.has("body"))
    }

    @Test fun invalidSummaryRetainsCompletedHttpTraceWithoutResponseBody() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.COMPACTION, log = {})
        trace.terminalReceived()
        val failure = failure(JSONObject().put("content", "x".repeat(12_001)).put("finish_reason", "stop"), trace, expectedCalls = 3)
        val diagnostic = JSONObject(failure.diagnosticJson!!)
        assertEquals(trace.traceId, diagnostic.getString("trace_id"))
        assertEquals("complete", diagnostic.getString("phase"))
        assertEquals("compaction", diagnostic.getString("purpose"))
        assertEquals(4, diagnostic.getInt("summary_invalid_reason"))
        assertEquals(12_001, diagnostic.getInt("summary_chars"))
        assertFalse(diagnostic.has("body"))
    }

    private fun failure(assistant: JSONObject, trace: ModelHttpTrace? = null, expectedCalls: Int = 1): AgentModelFailure {
        var calls = 0
        val original = history.toList()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider { _, _ -> calls++; ProviderResponse(assistant, httpTrace = trace) }, AgentRunController(), false,
                support = AgentCompactionSupport(checkpoints = object : AgentCompactionCheckpointStore {
                    override fun read(key: String): AgentCompactionCheckpoint? = null
                    override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { fail("Invalid summary cannot be cached") }
                    override fun clear(key: String) {}
                })).summarize(history)
        }
        assertEquals(expectedCalls, calls)
        assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.contextRecoveryAllowed)
        assertTrue(failure.message!!.contains("原始上下文已保留"))
        assertEquals(original, history)
        assertFalse(failure.message!!.contains("SYNTHETIC_PRIVATE_REPLY"))
        assertFalse(failure.diagnosticJson!!.contains("SYNTHETIC_PRIVATE_REPLY"))
        return failure
    }
    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "synthetic"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, onEvent)
    }
}
