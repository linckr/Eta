package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AgentPortableTextCompactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "fixture", contextWindow = 960_000, compactionPolicyVersion = 4, requestSendLimitBytes = 2L * 1024 * 1024)
    private val source = listOf(AgentModelClient.ConversationMessage("assistant", "synthetic source"))

    @Test fun complete20300CharactersAnd17FactsDoNotRequireRepair() {
        val text = (1..17).joinToString("\n") { "fact $it " } + "x".repeat(20_300)
        var calls = 0
        val summarizer = summarizer { calls++; response(text) }
        assertEquals(text, summarizer.summarize(source, candidateFits = { _, _ -> true }))
        assertEquals(1, calls)
        assertEquals(1, summarizer.coveredMessages)
    }

    @Test fun legalCandidateWithoutTargetHeadroomIsNotNeedlesslyCondensed() {
        var calls = 0
        val summary = summarizer { calls++; response("x".repeat(20_300)) }
            .summarize(source, accepts = { _, _ -> false }, candidateFits = { _, _ -> true })
        assertEquals(20_300, summary.length)
        assertEquals(1, calls)
    }

    @Test fun illegalFinalCandidateGetsOneCondensation() {
        var calls = 0
        val result = summarizer { if (++calls == 1) response("x".repeat(20_300)) else response("short") }
            .summarize(source, candidateFits = { text, _ -> text.length < 100 })
        assertEquals("short", result)
        assertEquals(2, calls)
    }

    @Test fun stillOversizeAfterCondensationFailsWithoutTruncating() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            summarizer { calls++; response("x".repeat(20_300)) }.summarize(source, candidateFits = { _, _ -> false })
        }
        assertEquals("CONTEXT_SUMMARY_OUTPUT_BUDGET", failure.code)
        assertEquals(2, calls)
    }

    @Test fun transportRecoveryAndCondensationShareOneAllowance() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            summarizer {
                if (++calls == 1) throw AgentModelFailure("MODEL_TIMEOUT", false, "fixture")
                response("large")
            }.summarize(source, candidateFits = { _, _ -> false })
        }
        assertEquals("CONTEXT_SUMMARY_OUTPUT_BUDGET", failure.code)
        assertEquals(2, calls)
    }

    @Test fun unfinishedOutputCannotAdvanceCheckpoint() {
        val store = MemoryStore()
        val engine = AgentContextSummarizer(config, provider { response("partial", "length") }, AgentRunController(), false,
            support = AgentCompactionSupport(portableTextCheckpoints = store))
        assertThrows(AgentModelFailure::class.java) { engine.summarize(source) }
        assertTrue(store.entries.isEmpty())
    }

    @Test fun streamedUtf8CaptureHasBound() {
        val capture = PortableSummaryCapture()
        val failure = assertThrows(AgentModelFailure::class.java) {
            capture.accept(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "中".repeat(100_000)))
        }
        assertEquals("CONTEXT_SUMMARY_CAPTURE_LIMIT", failure.code)
    }

    @Test fun finalOnlyOutputIncludesWhitespaceInCaptureBound() {
        assertThrows(AgentModelFailure::class.java) {
            PortableSummaryCapture().finish(response(" ".repeat(256 * 1024) + "x"))
        }
    }

    @Test fun toolCallIsNeverIgnoredEvenWhenTextExists() {
        val message = JSONObject().put("content", "looks valid").put("tool_calls", org.json.JSONArray().put(
            JSONObject().put("id", "tool-1").put("type", "function").put("function", JSONObject().put("name", "terminal").put("arguments", "{}"))))
        assertThrows(AgentModelFailure::class.java) { PortableSummaryCapture().finish(ProviderResponse(message)) }
        assertThrows(AgentModelFailure::class.java) {
            PortableSummaryCapture().accept(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 0))
        }
    }

    @Test fun portableCheckpointDoesNotRelaxLegacyRecordValidation() {
        val old = FileAgentCompactionCheckpointStore(temporary.newFolder())
        val portable = old.portableTextStore()
        val key = "a".repeat(64)
        val record = AgentCompactionCheckpoint("x".repeat(20_300), AgentSummarySlices.Cursor(1), 1, 64_000)
        assertFalse(record.valid())
        assertThrows(IllegalArgumentException::class.java) { old.save(key, record) }
        portable.save(key, record)
        assertEquals(record, portable.read(key))
        assertNull(old.read(key))
        old.clear(key)
        assertEquals(record, portable.read(key))
    }

    @Test fun verifiedPartialCursorDoesNotBecomeEarlyCandidate() {
        val huge = listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(100_000)))
        var calls = 0
        val summarizer = summarizer { calls++; response("verified") }
        assertEquals("verified", summarizer.summarize(huge, accepts = { _, _ -> true }))
        assertTrue(calls > 1)
        assertEquals(1, summarizer.coveredMessages)
    }

    @Test fun compatibleModelSwitchReusesPortableCheckpointAndDoesNotTouchOldStore() {
        val store = MemoryStore()
        var calls = 0
        val support = AgentCompactionSupport(portableTextCheckpoints = store)
        val first = AgentContextSummarizer(config, provider { calls++; response("verified") }, AgentRunController(), false, support = support)
        assertEquals("verified", first.summarize(source))
        val switched = AgentContextSummarizer(config.copy(model = "another", apiKey = "another"), provider { fail("No source replay"); response("") },
            AgentRunController(), false, support = support)
        assertEquals("verified", switched.summarize(source))
        assertEquals(1, calls)
    }

    @Test fun checkpointReadIoFailureIsNotSwallowedOrSentToModel() {
        val store = object : AgentCompactionCheckpointStore {
            override fun read(key: String): AgentCompactionCheckpoint? = throw java.io.IOException("fixture")
            override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { fail("No overwrite") }
            override fun clear(key: String) { fail("No destructive recovery") }
        }
        val engine = AgentContextSummarizer(config, provider { fail("No model request"); response("") }, AgentRunController(), false,
            support = AgentCompactionSupport(portableTextCheckpoints = store))
        val error = assertThrows(AgentModelFailure::class.java) { engine.summarize(source) }
        assertEquals("CONTEXT_SUMMARY_CHECKPOINT_FAILED", error.code)
    }

    @Test fun storageFailureAtDeadlineRemainsHardCheckpointFailure() {
        var now = 0L
        val store = object : AgentCompactionCheckpointStore {
            override fun read(key: String): AgentCompactionCheckpoint? = null
            override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { now = 100; throw java.io.IOException("fixture") }
            override fun clear(key: String) { fail("No destructive recovery") }
        }
        val engine = AgentContextSummarizer(config, provider { response("verified") }, AgentRunController(), false,
            support = AgentCompactionSupport(clock = { now }, budgetMs = 100,
                limiter = AgentCompactionCallLimiter { parent, _, call -> call(parent) }, portableTextCheckpoints = store))
        val error = assertThrows(AgentModelFailure::class.java) { engine.summarize(source) }
        assertEquals("CONTEXT_SUMMARY_CHECKPOINT_FAILED", error.code)
    }

    @Test fun mentioningProtocolBugIsValidProseButActualLeakIsNot() {
        assertEquals("已定位 <DSML> 显示问题", PortableSummaryCapture().finish(response("已定位 <DSML> 显示问题")))
        val leaked = "<|DSML|tool_calls><|DSML|invoke name=\"terminal\">{}<|/DSML|invoke><|/DSML|tool_calls>"
        // Match the canonical spelling with the slash before the protocol separator.
        val canonical = leaked.replace("<|/DSML|", "</|DSML|")
        assertThrows(AgentModelFailure::class.java) { PortableSummaryCapture().finish(response(canonical)) }
    }

    @Test fun localSendBoundaryShrinksPendingSliceBeforeHttp() {
        val bounded = config.copy(requestSendLimitBytes = 8_192)
        var calls = 0
        val backend = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                calls++
                assertTrue(AgentCompactionRequestBudget.evaluate(bounded, request.messages).request.requestBytes <= 8_192)
                return response("verified")
            }
        }
        val engine = AgentContextSummarizer(bounded, backend, AgentRunController(), false)
        assertEquals("verified", engine.summarize(listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(20_000)))))
        assertTrue(calls > 1)
        assertEquals(1, engine.coveredMessages)
    }

    @Test fun cumulativeSummaryThatCannotFitEvenMinimumSliceFailsBoundedly() {
        var calls = 0
        val bounded = config.copy(requestSendLimitBytes = 4_096)
        val engine = AgentContextSummarizer(bounded, provider { calls++; response("s".repeat(5_000)) }, AgentRunController(), false)
        val error = assertThrows(AgentModelFailure::class.java) {
            engine.summarize(listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(40_000))))
        }
        assertEquals("CONTEXT_SUMMARY_INPUT_BUDGET", error.code)
        assertEquals(1, calls)
    }

    private fun summarizer(block: () -> ProviderResponse) = AgentContextSummarizer(config, provider(block), AgentRunController(), false)
    private fun response(text: String, stop: String = "stop") = ProviderResponse(JSONObject().put("content", text).put("finish_reason", stop))
    private fun provider(block: () -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block()
    }
    private class MemoryStore : AgentCompactionCheckpointStore {
        val entries = mutableMapOf<String, AgentCompactionCheckpoint>()
        override fun read(key: String) = entries[key]
        override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { entries[key] = checkpoint }
        override fun clear(key: String) { entries.remove(key) }
    }
}
