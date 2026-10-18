package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentCompactionCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "synthetic",
        model = "fixture", systemPrompt = "fixture", contextWindow = 960_000)
    private fun history(length: Int = 180_000) = listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(length)))

    @Test fun stalledResumedSliceHasItsOwnDeadlineAndPreservesTheVerifiedPrefix() {
        val cache = MemoryStore()
        val source = history(70_000)
        var calls = 0
        val initial = AgentContextSummarizer(config, provider { _, _ ->
            if (++calls > 1) throw AgentModelFailure("MODEL_HTTP_400", false, "fixture")
            response("verified prefix")
        }, AgentRunController(), false, support = AgentCompactionSupport(checkpoints = cache))
        assertThrows(AgentModelFailure::class.java) { initial.summarize(source) }
        val key = initial.checkpointKey!!
        val saved = cache.entries.getValue(key).copy(completedSlices = 88, sliceLimit = 2_000,
            summary = "v".repeat(11_638))
        cache.entries[key] = saved
        var now = 0L
        val limits = mutableListOf<Long>()
        val support = AgentCompactionSupport(cache, { now }, 100,
            AgentCompactionCallLimiter { parent, limit, call ->
                limits += limit
                val started = now
                val result = call(parent)
                if (now - started >= limit) throw AgentCompactionDeadlineException()
                result
            }, callBudgetMs = 20)
        calls = 0
        val resumed = AgentContextSummarizer(config, provider { request, _ ->
            if (++calls == 1) { now += 20; response("unvalidated") }
            else {
                if (calls == 2) {
                    assertEquals(saved.cursor, cache.entries.getValue(key).cursor)
                    assertEquals(88, cache.entries.getValue(key).completedSlices)
                    assertEquals(2_000, cache.entries.getValue(key).sliceLimit)
                    assertTrue(request.messages.toString().contains(saved.summary))
                }
                now++; response("verified continuation")
            }
        }, AgentRunController(), false, support = support.copy(hierarchical = true))
        assertEquals("verified continuation", resumed.summarize(source))
        assertTrue(limits.all { it <= 20 })
        assertTrue(cache.entries.getValue(key).completedSlices > 88)
    }

    @Test fun deadlineDuringPendingCallKeepsVerifiedCursorAndNextRunDoesNotRepeatPrefix() {
        val cache = MemoryStore()
        var now = 0L
        val requests = mutableListOf<ProviderRequest>()
        val support = AgentCompactionSupport(cache, { now }, 100,
            AgentCompactionCallLimiter { parent, remaining, call ->
                val before = now
                val response = call(parent)
                if (now - before >= remaining) throw AgentCompactionDeadlineException()
                response
            })
        val provider = provider { request, _ -> requests += request; now += 40; response("summary-${requests.size}") }
        val first = AgentContextSummarizer(config, provider, AgentRunController(), false, support = support)
        assertEquals("CONTEXT_SUMMARY_TIME_BUDGET", assertThrows(AgentModelFailure::class.java) { first.summarize(history()) }.code)
        val saved = cache.entries.values.single()
        assertEquals(2, saved.completedSlices)
        assertEquals(64_000, saved.sliceLimit)
        val nextSlice = AgentSummarySlices(history()).next(saved.cursor, saved.sliceLimit).json
        val resumed = mutableListOf<Pair<Int, Boolean>>()
        val second = AgentContextSummarizer(config, provider, AgentRunController(), false, support = support,
            onProgress = { count, isResume -> resumed += count to isResume })
        second.summarize(history())
        assertEquals(nextSlice, slice(requests[3]))
        assertTrue(requests[3].messages.getJSONObject(1).getString("content").contains("summary-2"))
        assertEquals(2 to true, resumed.first())
        val calls = requests.size
        // A complete cached cursor awaits formal commit; it must not send an empty history request.
        assertEquals("summary-${requests.size}", AgentContextSummarizer(config, provider, AgentRunController(), false, support = support).summarize(history()))
        assertEquals(calls, requests.size)
    }

    @Test fun sourceModelProtocolAndRoleplayChangesNeverReuseCachedSummary() {
        val cache = MemoryStore()
        val support = AgentCompactionSupport(checkpoints = cache)
        val original = history(8_000)
        AgentContextSummarizer(config, provider { _, _ -> response("old-private-summary") }, AgentRunController(), false, support = support).summarize(original)
        val variants = listOf(Triple(config, history(8_001), false), Triple(config.copy(model = "other"), original, false),
            Triple(config.copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES), original, false), Triple(config, original, true))
        variants.forEach { (model, source, roleplay) ->
            var calls = 0
            AgentContextSummarizer(model, provider { request, _ ->
                calls++
                assertFalse(request.messages.toString().contains("old-private-summary"))
                response("new-summary")
            }, AgentRunController(), roleplay, support = support).summarize(source)
            assertEquals(1, calls)
        }
    }

    @Test fun userCancellationPreservesLastValidatedCheckpointAndOriginalHistory() {
        val cache = MemoryStore()
        val support = AgentCompactionSupport(checkpoints = cache)
        val controller = AgentRunController()
        val messages = JSONArray().put(AgentConversationCodec.toJsonObject(history().single()))
        val original = messages.toString()
        var calls = 0
        val session = AgentContextSession(config, messages, 0, "synthetic", provider { _, child ->
            if (++calls == 2) { controller.cancel(); child.throwIfCancelled() }
            response("verified")
        }, controller, { emptySet() }, onContextSnapshot = { fail("Cannot commit cancellation") }, compactionSupport = support)
        assertThrows(AgentRunCancelledException::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
        assertEquals(1, cache.entries.values.single().completedSlices)
    }

    @Test fun successfulFormalCommitClearsCacheButFailedPublicationKeepsIt() {
        val cache = MemoryStore()
        val support = AgentCompactionSupport(checkpoints = cache)
        val messages = JSONArray().put(AgentConversationCodec.toJsonObject(history(8_000).single()))
        val original = messages.toString()
        val failed = AgentContextSession(config, messages, 0, "synthetic", provider { _, _ -> response("verified") },
            AgentRunController(), { emptySet() }, onContextSnapshot = { error("synthetic persistence failure") }, compactionSupport = support)
        assertThrows(Exception::class.java) { failed.compact(force = true) }
        assertEquals(original, messages.toString())
        assertEquals(1, cache.entries.size)
        val resumed = AgentContextSession(config, messages, 0, "synthetic", provider { _, _ -> fail("Completed checkpoint must not resend"); response("unused") },
            AgentRunController(), { emptySet() }, onContextSnapshot = {}, compactionSupport = support)
        resumed.compact(force = true)
        assertTrue(cache.entries.isEmpty())
        assertNotNull(resumed.snapshot())
    }

    @Test fun actualDeadlineCancelsOnlyTheCurrentProviderCallAndRetainsNoPartialText() {
        val cache = MemoryStore()
        val parent = AgentRunController()
        var childCancelled = false
        val support = AgentCompactionSupport(checkpoints = cache, budgetMs = 250)
        val provider = provider { _, child ->
            val cancelled = CountDownLatch(1)
            val binding = child.register { childCancelled = true; cancelled.countDown() }
            try {
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                child.throwIfCancelled()
                response("unvalidated-partial")
            } finally { binding.close() }
        }
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider, parent, false, support = support).summarize(history(8_000))
        }
        assertEquals("CONTEXT_SUMMARY_TIME_BUDGET", failure.code)
        assertTrue(childCancelled)
        assertFalse(parent.isCancelled)
        assertTrue(cache.entries.isEmpty())
    }

    @Test fun privateAtomicFileRoundTripRejectsCorruptionOversizeAndInvalidVersion() {
        val directory = temporary.newFolder("private")
        val store = FileAgentCompactionCheckpointStore(directory)
        val key = "a".repeat(64)
        val checkpoint = AgentCompactionCheckpoint("verified😀", AgentSummarySlices.Cursor(1), 1, 64_000)
        store.save(key, checkpoint)
        assertEquals(checkpoint, store.read(key))
        val updated = checkpoint.copy(summary = "verified update")
        store.save(key, updated)
        assertEquals(updated, store.read(key))
        val compatibleFile = File(directory, "$key.json")
        val legacyJson = JSONObject(compatibleFile.readText()).also { it.remove("success_streak") }
        compatibleFile.writeText(legacyJson.toString())
        assertEquals(updated.copy(successStreak = 0), store.read(key))
        assertFalse(File(directory, "$key.json").readText().contains("fixture.invalid"))
        File(directory, "$key.json").writeText("{broken")
        assertNull(store.read(key))
        assertFalse(File(directory, "$key.json").exists())
        File(directory, "$key.json").writeText("x".repeat(256 * 1024 + 1))
        assertNull(store.read(key))
        store.save(key, checkpoint)
        val file = File(directory, "$key.json")
        file.writeText(JSONObject(file.readText()).put("version", 999).toString())
        assertNull(store.read(key))
        assertThrows(IllegalArgumentException::class.java) { store.save("../escape", checkpoint) }
        store.save(key, checkpoint); store.clear(key); assertNull(store.read(key))
    }

    @Test fun invalidCursorIsDiscardedAndRequestBudgetResetsForEachResume() {
        val cache = MemoryStore()
        val support = AgentCompactionSupport(checkpoints = cache)
        val first = AgentContextSummarizer(config, provider { _, _ -> response("old") }, AgentRunController(), false, support = support)
        first.summarize(history(8_000))
        val key = first.checkpointKey!!
        cache.entries[key] = cache.entries.getValue(key).copy(cursor = AgentSummarySlices.Cursor(999_999), completedSlices = 999)
        var calls = 0
        AgentContextSummarizer(config, provider { request, _ ->
            calls++; assertFalse(request.messages.toString().contains("old")); response("verified")
        }, AgentRunController(), false, support = support).summarize(history(8_000))
        assertEquals(1, calls)
        assertEquals(1, cache.entries.getValue(key).completedSlices)
    }

    @Test fun verifiedSlicesFromEarlierRunsDoNotConsumeThisRunsRequestAllowance() {
        val cache = MemoryStore()
        val support = AgentCompactionSupport(checkpoints = cache)
        var initialCalls = 0
        val first = AgentContextSummarizer(config, provider { _, _ ->
            if (++initialCalls > 1) throw AgentModelFailure("MODEL_HTTP_400", false, "synthetic")
            response("verified prefix")
        }, AgentRunController(), false, support = support)
        assertThrows(AgentModelFailure::class.java) { first.summarize(history()) }
        val key = first.checkpointKey!!
        cache.entries[key] = cache.entries.getValue(key).copy(completedSlices = 200)
        var calls = 0
        AgentContextSummarizer(config, provider { _, _ -> calls++; response("complete") }, AgentRunController(), false,
            support = support).summarize(history())
        assertTrue(calls in 1..3)
        assertEquals(200 + calls, cache.entries.getValue(key).completedSlices)
    }

    private class MemoryStore : AgentCompactionCheckpointStore {
        val entries = mutableMapOf<String, AgentCompactionCheckpoint>()
        override fun read(key: String) = entries[key]
        override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { entries[key] = checkpoint }
        override fun clear(key: String) { entries.remove(key) }
    }
    private fun slice(request: ProviderRequest) = request.messages.getJSONObject(1).getString("content").substringAfter("按原顺序继续的历史数据片段：\n")
    private fun response(content: String) = ProviderResponse(JSONObject().put("content", content).put("finish_reason", "stop"))
    private fun provider(block: (ProviderRequest, AgentRunController) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "synthetic"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, runController)
    }
}
