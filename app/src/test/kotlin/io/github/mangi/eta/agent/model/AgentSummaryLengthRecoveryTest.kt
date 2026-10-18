package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummaryLengthRecoveryTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "fixture", contextWindow = 960_000)
    private val history = listOf(AgentModelClient.ConversationMessage("assistant", "PRIVATE_SOURCE_FIXTURE"))

    @Test fun repeated13202CharacterSummaryUsesSmallerStructuredBudgetBeforeCommit() {
        val requests = mutableListOf<ProviderRequest>()
        val saved = mutableListOf<AgentCompactionCheckpoint>()
        val summary = AgentContextSummarizer(config, provider { request ->
            requests += request
            response(if (requests.size <= 2) "x".repeat(13_202) else "verified bounded summary")
        }, AgentRunController(), false, support = AgentCompactionSupport(checkpoints = store(saved))).summarize(history)
        assertEquals(3, requests.size)
        val strict = requests.last()
        assertTrue(strict.messages.getJSONObject(0).getString("content").contains("4000"))
        assertTrue(strict.messages.getJSONObject(0).getString("content").contains("650"))
        assertFalse(strict.messages.toString().contains("PRIVATE_SOURCE_FIXTURE"))
        assertEquals(0, strict.tools.length())
        assertEquals("verified bounded summary", summary)
        assertEquals(1, saved.size)
        assertEquals(1, saved.single().completedSlices)
        assertEquals(summary, saved.single().summary)
    }

    @Test fun strictRefinementCannotCommitPartialTextOrExceedTheRecoveryAllowance() {
        var calls = 0
        val saved = mutableListOf<AgentCompactionCheckpoint>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider {
                if (++calls <= 2) response("x".repeat(13_202)) else response("unfinished", "length")
            }, AgentRunController(), false, support = AgentCompactionSupport(checkpoints = store(saved))).summarize(history)
        }
        assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
        assertEquals(3, calls)
        assertTrue(saved.isEmpty())
    }

    @Test fun terminal12343CharacterSummaryIsRefinedOnceBeforeCheckpointCommit() {
        val requests = mutableListOf<ProviderRequest>()
        val candidate = "CANDIDATE_FIXTURE" + "x".repeat(12_343 - "CANDIDATE_FIXTURE".length)
        val saved = mutableListOf<AgentCompactionCheckpoint>()
        val provider = provider { request ->
            requests += request
            response(if (requests.size == 1) candidate else "verified concise summary")
        }
        val summary = AgentContextSummarizer(config, provider, AgentRunController(), false,
            support = AgentCompactionSupport(checkpoints = store(saved))).summarize(history)
        assertEquals("verified concise summary", summary)
        assertEquals(2, requests.size)
        val refinement = requests[1]
        assertEquals(ProviderRequestPurpose.COMPACTION, refinement.purpose)
        assertEquals(0, refinement.tools.length())
        assertTrue(refinement.messages.toString().contains(candidate))
        assertFalse(refinement.messages.toString().contains("PRIVATE_SOURCE_FIXTURE"))
        assertEquals(1, saved.size)
        assertEquals(summary, saved.single().summary)
        assertEquals(1, saved.single().completedSlices)
        assertEquals("PRIVATE_SOURCE_FIXTURE", history.single().content)
    }

    @Test fun refinementMustAlsoEndNormallyAndCannotCommitTruncatedOutput() {
        var calls = 0
        val saved = mutableListOf<AgentCompactionCheckpoint>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider {
                if (++calls == 1) response("x".repeat(12_343)) else response("short but incomplete", "length")
            }, AgentRunController(), false, support = AgentCompactionSupport(checkpoints = store(saved))).summarize(history)
        }
        assertEquals(2, calls)
        assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
        assertTrue(saved.isEmpty())
    }

    @Test fun refinementUsesRemainingTimeRatherThanResettingRunBudget() {
        var now = 0L
        val allowances = mutableListOf<Long>()
        var calls = 0
        val support = AgentCompactionSupport(clock = { now }, budgetMs = 100,
            limiter = AgentCompactionCallLimiter { controller, remaining, call -> allowances += remaining; call(controller) })
        val summary = AgentContextSummarizer(config, provider {
            now += 40
            response(if (++calls == 1) "x".repeat(12_343) else "short summary")
        }, AgentRunController(), false, support = support).summarize(history)
        assertEquals("short summary", summary)
        assertEquals(listOf(100L, 60L), allowances)
    }

    @Test fun exhaustedTimeBudgetDoesNotSendRefinementOrCacheOversizedCandidate() {
        var now = 0L
        var calls = 0
        val saved = mutableListOf<AgentCompactionCheckpoint>()
        val support = AgentCompactionSupport(store(saved), { now }, 100,
            AgentCompactionCallLimiter { controller, _, call -> call(controller) })
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider { calls++; now = 101; response("x".repeat(12_343)) },
                AgentRunController(), false, support = support).summarize(history)
        }
        assertEquals("CONTEXT_SUMMARY_TIME_BUDGET", failure.code)
        assertEquals(1, calls)
        assertTrue(saved.isEmpty())
    }

    @Test fun cancellationAfterOversizedResponseDoesNotSendRefinement() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextSummarizer(config, provider { calls++; controller.cancel(); response("x".repeat(12_343)) },
                controller, false).summarize(history)
        }
        assertEquals(1, calls)
    }

    @Test fun refinementCallsShareTheExisting128RequestBudget() {
        var calls = 0
        val source = listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(65 * 64_000)))
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider {
                response(if (++calls % 2 == 1) "x".repeat(12_343) else "short summary")
            }, AgentRunController(), false).summarize(source)
        }
        assertEquals("CONTEXT_SUMMARY_BUDGET", failure.code)
        assertEquals(128, calls)
    }

    private fun response(text: String, stop: String = "stop") =
        ProviderResponse(JSONObject().put("content", text).put("finish_reason", stop))

    private fun store(saved: MutableList<AgentCompactionCheckpoint>) = object : AgentCompactionCheckpointStore {
        override fun read(key: String): AgentCompactionCheckpoint? = null
        override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { saved += checkpoint }
        override fun clear(key: String) {}
    }

    private fun provider(call: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit) = call(request)
    }
}
