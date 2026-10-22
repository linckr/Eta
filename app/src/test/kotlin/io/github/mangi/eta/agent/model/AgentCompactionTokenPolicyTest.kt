package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionTokenPolicyTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "fixed", contextWindow = 10_000, compactionPolicyVersion = 4,
        requestSendLimitBytes = 2_228_643, requestGrowthAllowanceBytes = 445_729)

    @Test fun commitCeilingRejects95And85PercentButAccepts80IncludingLargeWindows() {
        for (input in listOf(10_000, 128_000, 1_000_000, 2_000_000_000)) {
            assertFalse(AgentCompactionTokenPolicy.accepts((input.toLong() * 95 / 100).toInt(), input))
            assertFalse(AgentCompactionTokenPolicy.accepts((input.toLong() * 85 / 100).toInt(), input))
            assertTrue(AgentCompactionTokenPolicy.accepts((input.toLong() * 80 / 100).toInt(), input))
        }
    }

    @Test fun triggerIncludesExact85PercentBoundary() {
        assertFalse(AgentCompactionTokenPolicy.triggered(8_499, 10_000))
        assertTrue(AgentCompactionTokenPolicy.triggered(8_500, 10_000))
        var calls = 0
        val active = session { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        assertFalse(active.prepareForRequest(8_499, 1_000))
        assertEquals(0, calls)
        assertThrows(AgentModelFailure::class.java) { active.prepareForRequest(8_500, 1_000) }
        assertTrue(calls > 0)
    }

    @Test fun tenRoundsOfTwoPercentGrowthDoNotRetriggerOnlyAfterSixtyPercentTarget() {
        assertTrue(AgentCompactionTokenPolicy.meetsTarget(6_000, 10_000))
        assertFalse(AgentCompactionTokenPolicy.meetsTarget(6_001, 10_000))
        val active = session { error("No summary while growth stays below trigger") }
        repeat(10) { round ->
            val tokens = 6_000 + 200 * (round + 1)
            assertFalse(active.prepareForRequest(tokens, 1_000))
        }
        // Merely reaching the commit ceiling does not promise ten more rounds of headroom.
        assertTrue(AgentCompactionTokenPolicy.triggered(8_000 + 3 * 200, 10_000))
    }

    @Test fun sessionFinalAndCondenseValidationReject95PercentWithoutCommitting() {
        val body = history()
        val original = body.toString()
        var calls = 0
        val active = session(body, project = { requestAtTokens(9_500) }) { calls++; response("large candidate") }
        val failure = assertThrows(AgentModelFailure::class.java) { active.compact(force = true) }
        assertEquals("CONTEXT_SUMMARY_OUTPUT_BUDGET", failure.code)
        assertEquals(2, calls)
        assertNull(active.snapshot())
        assertEquals(original, body.toString())
    }

    @Test fun condenseCanCommitAt80PercentButDoesNotPretendToMeetSixtyPercentTarget() {
        var calls = 0
        val active = session(project = { candidate ->
            val text = candidate.getJSONObject(1).optString("content")
            requestAtTokens(if (text.contains("large candidate")) 9_500 else 8_000)
        }) { if (++calls == 1) response("large candidate") else response("small candidate") }
        active.compact(force = true)
        assertNotNull(active.snapshot())
        assertEquals(2, calls)
        assertTrue(active.snapshot()!!.messages.any { it.content.contains("small candidate") })
    }

    private fun requestAtTokens(tokens: Int): JSONArray {
        fun content(length: Int) = JSONArray().put(JSONObject().put("role", "assistant").put("content", "x".repeat(length)))
        val baseline = AgentRequestBudget.evaluate(config, content(0), JSONArray()).estimatedTokens
        val result = content((tokens - baseline) * 3)
        assertEquals(tokens, AgentRequestBudget.evaluate(config, result, JSONArray()).estimatedTokens)
        return result
    }

    private fun history() = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
        // Fit one source slice so the second call is the shared condensation, not another slice.
        .put(JSONObject().put("role", "assistant").put("content", "x".repeat(2_000)))
        .put(JSONObject().put("role", "user").put("content", "continue"))

    private fun session(body: JSONArray = history(), project: (JSONArray) -> JSONArray = { it },
        block: () -> ProviderResponse): AgentContextSession {
        val server = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit) = block()
        }
        return AgentContextSession(config, body, 1, "fixture", server, AgentRunController(), { emptySet() },
            onContextSnapshot = {}, projectRequest = project)
    }
    private fun response(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
}
