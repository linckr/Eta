package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentPortableTextCheckpointBindingTest {
    @Test fun sourceProofSurvivesAtomicFileStoreReopenAndAllowsOnlyMatchingPrefix() {
        val directory = java.nio.file.Files.createTempDirectory("eta-portable-source-proof-").toFile()
        try {
            val source = AgentSummarySlices(listOf(message("old source")))
            val cursor = source.next(AgentSummarySlices.Cursor(), 64_000).next
            val checkpoint = AgentCompactionCheckpoint("verified", cursor, 1, 64_000,
                sourceBinding = source.sourceBinding(cursor))
            val key = "a".repeat(64)
            FileAgentCompactionCheckpointStore(directory).portableTextStore().save(key, checkpoint)
            val reopened = requireNotNull(FileAgentCompactionCheckpointStore(directory).portableTextStore().read(key))
            assertEquals(checkpoint, reopened)
            val appended = AgentSummarySlices(listOf(message("old source"), message("new source")))
            val restored = appended.restoreCursor(reopened.cursor, reopened.sourceBinding!!)!!
            assertEquals(1, appended.completeMessages(restored))
            assertNull(AgentSummarySlices(listOf(message("edited source"), message("new source")))
                .restoreCursor(reopened.cursor, requireNotNull(reopened.sourceBinding)))
        } finally {
            check(directory.canonicalFile.parentFile == java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile)
            directory.deleteRecursively()
        }
    }
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "fixture", contextWindow = 960_000,
        compactionPolicyVersion = 4, requestSendLimitBytes = 2L * 1024 * 1024)
    private fun message(text: String) = AgentModelClient.ConversationMessage("assistant", text)

    @Test fun appendingHistoryReusesConsumedPrefixWithoutReplayingIt() {
        val store = MemoryStore()
        val old = listOf(message("OLD_SOURCE_SENTINEL"))
        engine(store) { response("verified prefix") }.summarize(old)
        val requests = mutableListOf<ProviderRequest>()
        val second = engine(store) { requests += it; response("continued") }
        assertEquals("continued", second.summarize(old + message("APPENDED_SOURCE")))
        assertEquals(1, requests.size)
        assertFalse(slice(requests.single()).contains("OLD_SOURCE_SENTINEL"))
        assertTrue(slice(requests.single()).contains("APPENDED_SOURCE"))
        assertTrue(requests.single().messages.toString().contains("verified prefix"))
        assertEquals(2, second.coveredMessages)
    }

    @Test fun consumedPrefixEditRefusesReuse() {
        val store = MemoryStore()
        engine(store) { response("OLD_SUMMARY") }.summarize(listOf(message("old source")))
        val requests = mutableListOf<ProviderRequest>()
        engine(store) { requests += it; response("new summary") }.summarize(listOf(message("edited source"), message("append")))
        assertTrue(slice(requests.first()).contains("edited source"))
        assertFalse(requests.first().messages.toString().contains("OLD_SUMMARY"))
    }

    @Test fun stableScopesNeverShareTheSameHistoryCheckpoint() {
        val store = MemoryStore()
        val same = listOf(message("identical text"))
        engine(store, "conversation-a") { response("summary A") }.summarize(same)
        var calls = 0
        assertEquals("summary B", engine(store, "conversation-b") { calls++; response("summary B") }.summarize(same))
        assertEquals(1, calls)
        assertEquals(2, store.entries.size)
    }

    @Test fun cancellationThenAppendResumesVerifiedPartialField() {
        val store = MemoryStore()
        val history = listOf(message("x".repeat(100_000)))
        val controller = AgentRunController()
        var calls = 0
        val first = engine(store, controller = controller) {
            if (++calls == 2) { controller.cancel(); controller.throwIfCancelled() }
            response("verified partial")
        }
        assertThrows(Exception::class.java) { first.summarize(history) }
        val saved = store.entries.values.single()
        assertTrue(saved.cursor.character > 0)
        val requests = mutableListOf<ProviderRequest>()
        val resumed = engine(store) { requests += it; response("completed") }
        resumed.summarize(history + message("new tail"))
        val records = org.json.JSONArray(slice(requests.first()))
        val resumedContent = (0 until records.length()).map(records::getJSONObject).filter {
            it.optInt("message_index", -1) == saved.cursor.message &&
                it.optJSONArray("path")?.toString() == "[\"content\"]" && it.has("start")
        }
        assertTrue(resumedContent.isNotEmpty())
        assertEquals(saved.cursor.character, resumedContent.first().getInt("start"))
        // Other, not-yet-consumed fields legitimately start at zero; this content field must not.
        assertFalse(resumedContent.any { it.getInt("start") == 0 })
        assertEquals(2, resumed.coveredMessages)
    }

    @Test fun equalLengthUnconsumedFieldSuffixCanChange() {
        val original = "a".repeat(100_000)
        val source = AgentSummarySlices(listOf(message(original)))
        val cursor = source.next(AgentSummarySlices.Cursor(), 10_000).next
        assertTrue(cursor.character > 0)
        val binding = source.sourceBinding(cursor)
        val updated = original.take(cursor.character) + "b".repeat(original.length - cursor.character)
        val appended = AgentSummarySlices(listOf(message(updated), message("append")))
        assertEquals(cursor, appended.restoreCursor(cursor, binding))
    }

    @Test fun partialFieldPrefixOrLengthChangesInvalidateProof() {
        val original = "a".repeat(100_000)
        val source = AgentSummarySlices(listOf(message(original)))
        val cursor = source.next(AgentSummarySlices.Cursor(), 10_000).next
        val binding = source.sourceBinding(cursor)
        assertNull(AgentSummarySlices(listOf(message("z" + original.drop(1)))).restoreCursor(cursor, binding))
        assertNull(AgentSummarySlices(listOf(message(original + "z"))).restoreCursor(cursor, binding))
    }

    @Test fun insertionOfPreviouslyProtectedUserInvalidatesInsteadOfSkippingIt() {
        val old = listOf(message("old answer"), message("old second answer"))
        val source = AgentSummarySlices(old)
        val cursor = source.next(AgentSummarySlices.Cursor(), 64_000).next
        val binding = source.sourceBinding(cursor)
        val changed = listOf(AgentModelClient.ConversationMessage("user", "previously protected user")) + old
        assertNull(AgentSummarySlices(changed).restoreCursor(cursor, binding))
    }

    @Test fun noScopeUsesExactHistoryFallbackAndDoesNotClaimAppendResume() {
        val store = MemoryStore()
        val old = listOf(message("old source"))
        engine(store, scope = null) { response("old summary") }.summarize(old)
        val requests = mutableListOf<ProviderRequest>()
        engine(store, scope = null) { requests += it; response("new summary") }.summarize(old + message("append"))
        assertTrue(slice(requests.first()).contains("old source"))
        assertEquals(2, store.entries.size)
    }

    @Test fun unscopedLegacyFileIsPreservedButNotImportedIntoAnotherConversation() {
        val store = MemoryStore()
        val old = listOf(message("old source"))
        engine(store, scope = null) { response("old summary") }.summarize(old)
        val originalKey = store.entries.keys.single()
        store.entries[originalKey] = store.entries.getValue(originalKey).copy(sourceBinding = null)
        var calls = 0
        engine(store) { calls++; response("scoped summary") }.summarize(old)
        assertEquals(1, calls)
        assertTrue(store.entries.containsKey(originalKey))
        assertEquals(2, store.entries.size)
        assertEquals("old summary", engine(store, scope = null) {
            fail("Compatible unscoped exact-history file remains readable"); response("")
        }.summarize(old))
    }

    @Test fun partialToolBatchHeaderOrEndChangesInvalidateProof() {
        val calls = org.json.JSONArray().put(JSONObject().put("id", "call-1").put("type", "function")
            .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))
            .put(JSONObject().put("id", "call-2").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))
        val assistant = AgentModelClient.ConversationMessage("assistant", toolCallsJson = calls.toString())
        val result = AgentModelClient.ConversationMessage("tool", "x".repeat(100_000), toolCallId = "call-1")
        val history = listOf(assistant, result)
        val source = AgentSummarySlices(history)
        val cursor = source.next(AgentSummarySlices.Cursor(), 10_000).next
        assertTrue(cursor.character > 0)
        assertEquals(1, cursor.message)
        val binding = source.sourceBinding(cursor)
        val renamed = assistant.copy(toolCallsJson = calls.toString().replace("terminal", "different-tool"))
        assertNull(AgentSummarySlices(listOf(renamed, result)).restoreCursor(cursor, binding))
        // The old fragment advertised batch_end=1. Appending the missing result changes that metadata.
        val closed = history + AgentModelClient.ConversationMessage("tool", "result2", toolCallId = "call-2")
        assertNull(AgentSummarySlices(closed).restoreCursor(cursor, binding))
        assertNull(source.completeMessages(cursor))
    }

    private fun engine(store: MemoryStore, scope: String? = "conversation-a", controller: AgentRunController = AgentRunController(),
        block: (ProviderRequest) -> ProviderResponse): AgentContextSummarizer {
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request)
        }
        return AgentContextSummarizer(config, provider, controller, false,
            support = AgentCompactionSupport(portableTextCheckpoints = store, failureScopeKey = scope))
    }
    private fun response(text: String) = ProviderResponse(JSONObject().put("content", text).put("finish_reason", "stop"))
    private fun slice(request: ProviderRequest) = request.messages.getJSONObject(1).getString("content")
        .substringAfter("按原顺序继续的历史数据片段：\n")
    private class MemoryStore : AgentCompactionCheckpointStore {
        val entries = mutableMapOf<String, AgentCompactionCheckpoint>()
        override fun read(key: String) = entries[key]
        override fun save(key: String, checkpoint: AgentCompactionCheckpoint) { entries[key] = checkpoint }
        override fun clear(key: String) { entries.remove(key) }
    }
}
