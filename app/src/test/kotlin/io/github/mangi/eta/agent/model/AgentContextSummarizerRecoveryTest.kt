package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextSummarizerRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixture",
        contextWindow = 960_000,
    )
    private val firstTrace = "12345678-1234-1234-1234-123456789abc"
    private val secondTrace = "87654321-4321-4321-4321-cba987654321"

    @Test fun timeoutRetriesSameSliceOnFreshConnectionWithoutShrinking() {
        val requests = mutableListOf<ProviderRequest>()
        val events = mutableListOf<AgentEvent>()
        val summarizer = AgentContextSummarizer(config, provider { request ->
            requests += request
            if (requests.size == 1) throw transport("MODEL_TIMEOUT", firstTrace)
            response("完整摘要")
        }, AgentRunController(), false, events::add)

        assertEquals("完整摘要", summarizer.summarize(history(70_000)))
        assertFalse(requests[0].freshConnection)
        assertTrue(requests[1].freshConnection)
        // Slow response is not capacity evidence (AgentCompactionSlicePolicy): the same pending
        // slice is retried on a fresh connection, only capacity failures shrink the slice.
        assertEquals(slice(requests[0]), slice(requests[1]))
        assertEquals(0, contentFragment(requests[1]).getInt("start"))
        assertTrue(contentFragment(requests[0]).getString("value").startsWith(contentFragment(requests[1]).getString("value")))
        assertTrue(requests.drop(2).all { !it.freshConnection })
        val retry = events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single()
        assertEquals(1, retry.maxAttempts)
        assertEquals(firstTrace, JSONObject(retry.diagnosticJson!!).getString("trace_id"))
        assertEquals(ProviderRequestPurpose.COMPACTION, requests[1].purpose)
        assertEquals(0, requests[1].effectiveTools.length())
    }

    @Test fun connectionFailureGetsOneFreshRetryWithoutShrinkingOrReplayingTools() {
        val requests = mutableListOf<ProviderRequest>()
        val summarizer = AgentContextSummarizer(config, provider { request ->
            requests += request
            if (requests.size == 1) throw transport("MODEL_CONNECTION_FAILED", firstTrace)
            response("完整摘要")
        }, AgentRunController(), false)

        assertEquals("完整摘要", summarizer.summarize(history(1_000)))
        assertEquals(2, requests.size)
        assertFalse(requests[0].freshConnection)
        assertTrue(requests[1].freshConnection)
        assertEquals(requests[0].messages.toString(), requests[1].messages.toString())
        assertEquals(0, requests[1].effectiveTools.length())
    }

    @Test fun timeoutThenConnectionFailureDoesNotSpendASecondRecoveryAndKeepsCurrentTrace() {
        var requests = 0
        val events = mutableListOf<AgentEvent>()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixture"))
            .put(AgentConversationCodec.toJsonObject(history(70_000).single()))
        val original = messages.toString()
        val session = AgentContextSession(config, messages, 1, "run", provider {
            requests++
            if (requests == 1) throw transport("MODEL_TIMEOUT", firstTrace)
            throw transport("MODEL_CONNECTION_FAILED", secondTrace)
        }, AgentRunController(), { emptySet() }, events::add, { fail("Cannot commit a failed summary") })

        val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }

        assertEquals(2, requests)
        assertEquals("CONTEXT_SUMMARY_TRANSPORT_FAILED", failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.contextRecoveryAllowed)
        assertEquals(secondTrace, JSONObject(failure.diagnosticJson!!).getString("trace_id"))
        assertTrue(failure.message!!.contains("原始历史已保留"))
        assertEquals(firstTrace, JSONObject(events.filterIsInstance<AgentEvent.ModelRetryScheduled>()
            .single().diagnosticJson!!).getString("trace_id"))
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test fun validatedPrefixIsNotResubmittedWhenALaterSliceNeedsRecovery() {
        val requests = mutableListOf<ProviderRequest>()
        val events = mutableListOf<AgentEvent>()
        val summarizer = AgentContextSummarizer(config, provider { request ->
            requests += request
            when (requests.size) {
                1 -> response("已验证前缀摘要")
                2 -> throw transport("MODEL_TIMEOUT", firstTrace)
                else -> response("完整摘要")
            }
        }, AgentRunController(), false, events::add)
        val source = history(140_000)

        assertEquals("完整摘要", summarizer.summarize(source))
        assertEquals("已验证前缀摘要", previousSummary(requests[2]))
        assertTrue(requests[2].freshConnection)
        assertEquals(contentFragment(requests[1]).getInt("start"), contentFragment(requests[2]).getInt("start"))
        assertTrue(contentFragment(requests[1]).getString("value").startsWith(contentFragment(requests[2]).getString("value")))
        val successfulContent = requests.filterIndexed { index, _ -> index != 1 }.joinToString("") { contentFragment(it).getString("value") }
        assertEquals(source.single().content, successfulContent)
        assertEquals(2, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().round)
    }

    @Test fun cancellingDuringRecoveryEventStopsBeforeAnotherCallAndPreservesHistory() {
        val controller = AgentRunController()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixture"))
            .put(AgentConversationCodec.toJsonObject(history(70_000).single()))
        val original = messages.toString()
        var requests = 0
        val session = AgentContextSession(config, messages, 1, "run", provider {
            requests++
            throw transport("MODEL_TIMEOUT", firstTrace)
        }, controller, { emptySet() }, { event ->
            if (event is AgentEvent.ModelRetryScheduled) controller.cancel()
        }, { fail("Cancelled summary cannot commit") })

        assertThrows(Exception::class.java) { session.compact(force = true) }

        assertEquals(1, requests)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    private fun history(length: Int) = listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(length)))
    private fun contentFragment(request: ProviderRequest): JSONObject {
        val records = JSONArray(slice(request))
        return (0 until records.length()).map { records.getJSONObject(it) }.first {
            it.optString("kind") == "field" && it.getJSONArray("path").toString() == "[\"content\"]"
        }
    }
    private fun slice(request: ProviderRequest): String = request.messages.getJSONObject(1).getString("content")
        .substringAfter("按原顺序继续的历史数据片段：\n")
    private fun previousSummary(request: ProviderRequest): String = request.messages.getJSONObject(1).getString("content")
        .substringAfter("此前累计摘要（可能为空）：\n").substringBefore("\n\n按原顺序继续的历史数据片段：\n")
    private fun transport(code: String, trace: String) = AgentModelFailure(code, true, "fixture",
        diagnosticJson = JSONObject().put("trace_id", trace).put("phase", "response_headers")
            .put("code", code).put("purpose", "compaction").toString())
    private fun response(content: String) = ProviderResponse(JSONObject().put("content", content).put("finish_reason", "stop"))
    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = block(request)
    }
}
