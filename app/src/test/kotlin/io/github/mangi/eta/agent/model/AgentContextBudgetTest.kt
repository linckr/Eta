package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextBudgetTest {
    @Test fun preflightWithoutReductionLetsProviderDecideAndPreservesOriginalHistory() {
        val source = JSONArray().put(JSONObject().put("role", "assistant").put("content", "ok"))
            .put(JSONObject().put("role", "user").put("content", "latest"))
        val original = source.toString()
        var summaries = 0
        var chats = 0
        val server = provider { request ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("A summary longer than the short original history, so replacing it has no benefit.")
            } else {
                chats++
                assertEquals(original, request.messages.toString())
                response("done")
            }
        }
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "fixture").put("description", "large description ".repeat(1500))))
        val loop = AgentLoop(config(4096), source, tools, server,
            AgentModelClient.ToolExecutor { fail("no tools may run"); AgentModelClient.ToolResult("unused") },
            AgentRunController(), AgentTraceFormatter(), {})
        assertEquals("done", loop.run().content)
        assertEquals(1, chats)
        assertEquals(1, summaries)
        assertEquals("ok", source.getJSONObject(0).getString("content"))
        assertEquals("latest", source.getJSONObject(1).getString("content"))
        assertFalse((0 until source.length()).any { source.getJSONObject(it).optBoolean("_eta_context_summary") })
    }

    @Test fun manualCompactionStillRejectsSummaryWithoutReduction() {
        val source = JSONArray().put(JSONObject().put("role", "assistant").put("content", "ok"))
            .put(JSONObject().put("role", "user").put("content", "latest"))
        val original = source.toString()
        val session = AgentContextSession(config(4096), source, 0, "manual", provider {
            response("A summary longer than the short original history, so replacing it has no benefit.")
        }, AgentRunController(), { emptySet() }, {}, {})
        val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals("CONTEXT_NO_REDUCTION", failure.code)
        assertEquals(original, source.toString())
        assertNull(session.snapshot())
    }

    @Test fun totalBudgetReservesOutputWhileInputOnlyKeepsItsWholeWindow() {
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("content", "x".repeat(8000)))
            .put(JSONObject().put("role", "user").put("content", "latest current task"))
        val base = config(10_000).copy(customBody = listOf(io.github.mangi.eta.data.model.CustomBody(
            "max_tokens", kotlinx.serialization.json.JsonPrimitive(6000))))
        var summaries = 0
        val server = provider { summaries++; response("summary") }
        val inputMessages = JSONArray(messages.toString())
        val inputSession = AgentContextSession(base.copy(contextWindowKind = "input"), inputMessages, 0, "input", server,
            AgentRunController(), { emptySet() }, {}, {})
        inputSession.prepareForRequest(5000)
        assertEquals(0, summaries)
        assertEquals(messages.toString(), inputMessages.toString())
        val totalSession = AgentContextSession(base.copy(contextWindowKind = "total"), messages, 0, "total", server,
            AgentRunController(), { emptySet() }, {}, {})
        totalSession.prepareForRequest(5000)
        assertTrue(summaries > 0)
        assertEquals("latest current task", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test fun impossibleOutputReservationDoesNotSummarizeOrDropLatestToolRound() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "latest"))
            .put(JSONObject().put("role", "assistant").put("content", "partial")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-kept").put("type", "function")
                    .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call-kept").put("content", "completed output"))
        val original = messages.toString()
        val invalid = config(4096).copy(contextWindowKind = "total", customBody = listOf(io.github.mangi.eta.data.model.CustomBody(
            "max_tokens", kotlinx.serialization.json.JsonPrimitive(8192))))
        val session = AgentContextSession(invalid, messages, 0, "invalid", provider { fail("不得请求摘要"); response("unused") },
            AgentRunController(), { emptySet() }, {}, {})
        val failure = assertThrows(AgentModelFailure::class.java) { session.prepareForRequest(100) }
        assertEquals("CONTEXT_OUTPUT_BUDGET_INVALID", failure.code)
        assertEquals(original, messages.toString())
    }

    @Test fun countsToolDefinitionsAndUnicodeWithoutTreatingImageBytesAsText() {
        val plain = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        val largeTools = JSONArray().put(JSONObject().put("description", "x".repeat(12_000)))
        assertTrue(AgentContextBudget.estimate(plain, largeTools) > AgentContextBudget.estimate(plain, JSONArray()))
        val smallImage = JSONArray().put(JSONObject().put("image_url", "data:image/png;base64,AAAA"))
        val bigImage = JSONArray().put(JSONObject().put("image_url", "data:image/png;base64," + "A".repeat(300_000)))
        assertEquals(AgentContextBudget.estimate(smallImage, JSONArray()), AgentContextBudget.estimate(bigImage, JSONArray()))
    }

    @Test fun sendsBoundedHistoryPartsAndCarriesOnlyValidatedRollingSummary() {
        val requests = mutableListOf<ProviderRequest>()
        val provider = provider { request -> requests += request; response("summary-${requests.size}") }
        val summary = AgentContextSummarizer(config(), provider, AgentRunController(), false)
            .summarize(listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "abcdef".repeat(1_000))))
        assertTrue(requests.size > 1)
        assertEquals("summary-${requests.size}", summary)
        requests.forEach { assertEquals(ProviderRequestPurpose.COMPACTION, it.purpose); assertEquals(0, it.tools.length()) }
        assertTrue(requests[1].messages.toString().contains("summary-1"))
        assertTrue(requests.all { it.messages.toString().length < 3_000 })
    }

    @Test fun shrinksRejectedPartWithoutDroppingHistoryAndStopsAtMinimumBudget() {
        val parts = mutableListOf<String>()
        var calls = 0
        val provider = provider { request ->
            calls++
            if (calls == 1) throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
            parts += request.messages.getJSONObject(1).getString("content")
            response("summary")
        }
        val result = AgentContextSummarizer(config(12_000), provider, AgentRunController(), false)
            .summarize(listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "BEGIN" + "x".repeat(8_000) + "END")))
        assertEquals("summary", result)
        assertTrue(parts.first().contains("BEGIN"))
        assertTrue(parts.last().contains("END"))
        var failures = 0
        assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config(12_000), provider { failures++; throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture") },
                AgentRunController(), false).summarize(listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "x".repeat(5_000))))
        }
        assertEquals(3, failures)
    }

    @Test fun firstRequestCanCompactBeforeReceivingUsage() {
        val history = JSONArray().put(JSONObject().put("role", "assistant").put("content", "x".repeat(5_000)))
            .put(JSONObject().put("role", "user").put("content", "keep current request"))
        var summaries = 0
        val session = AgentContextSession(config(4_096), history, 0, "fixture", provider { summaries++; response("summary") },
            AgentRunController(), { emptySet() }, {}, {})
        session.prepareForRequest(AgentContextBudget.estimate(history, JSONArray().put(JSONObject().put("description", "x".repeat(8_000)))))
        assertTrue(summaries > 0)
        assertTrue(history.getJSONObject(0).getString("content").contains("summary"))
        assertEquals("keep current request", history.getJSONObject(1).getString("content"))
    }

    @Test fun rawTimeoutRetriesOnlySmallerPendingPartAndKeepsValidatedPrefix() {
        val inputs = mutableListOf<String>()
        val provider = provider { request ->
            inputs += request.messages.getJSONObject(1).getString("content")
            if (inputs.size == 2) throw java.net.SocketTimeoutException("timeout")
            response("summary-${inputs.size}")
        }
        val result = AgentContextSummarizer(config(12_000), provider, AgentRunController(), false)
            .summarize(listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "BEGIN" + "x".repeat(9_000) + "END")))
        assertTrue(inputs[0].contains("BEGIN"))
        // The final slice may contain only message metadata; verify the content across committed slices.
        val rebuilt = inputs.filterIndexed { index, _ -> index != 1 }.joinToString("") { input ->
            val records = JSONArray(input.substringAfter("按原顺序继续的历史数据片段：\n"))
            (0 until records.length()).map { records.getJSONObject(it) }
                .filter { it.optString("kind") == "field" && it.getJSONArray("path").optString(0) == "content" }
                .joinToString("") { it.getString("value") }
        }
        assertEquals("BEGIN" + "x".repeat(9_000) + "END", rebuilt)
        assertTrue(inputs[1].contains("summary-1"))
        assertTrue(inputs[2].contains("summary-1"))
        assertTrue(inputs[2].length < inputs[1].length)
        assertEquals("summary-${inputs.size}", result)
    }

    @Test fun repeatedRawTimeoutStopsAfterOneRecoveryAndCannotCommitSnapshot() {
        var calls = 0
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("content", "x".repeat(10_000)))
            .put(JSONObject().put("role", "user").put("content", "continue"))
        val original = messages.toString()
        val session = AgentContextSession(config(12_000), messages, 0, "fixture", provider {
            calls++; throw java.io.InterruptedIOException("timeout")
        }, AgentRunController(), { emptySet() }, {}, { fail("timeout must not checkpoint") })
        val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals("CONTEXT_SUMMARY_TIMEOUT", failure.code)
        assertEquals(2, calls)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test fun largeWindowStillCapsEachInitialHistorySlice() {
        val inputs = mutableListOf<String>()
        AgentContextSummarizer(config(1_000_000), provider { request ->
            inputs += request.messages.getJSONObject(1).getString("content")
            response("summary")
        }, AgentRunController(), false).summarize(listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "x".repeat(150_000)),
        ))
        assertEquals(3, inputs.size)
        assertTrue(inputs.all { it.length < 65_000 })
    }
    private fun config(window: Int = 4_096) = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "", contextWindow = window)
    private fun response(text: String) = ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
    private fun provider(action: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = action(request)
    }
}
