package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AgentHierarchicalCompactionTest {
    @Test fun leafFactCountRepairIsSpecificAndRetainsSameSourceWithoutTruncation() {
        for (count in listOf(0, 17)) {
            val fake = Fake().apply {
                transform = { body, leaf -> if (leaf && leaves == 1) body.put("facts", JSONArray().also { facts ->
                    repeat(count) { facts.put(JSONObject().put("section", "confirmed").put("text", "fixture-$it")) }
                }) }
            }
            val events = mutableListOf<AgentEvent>()
            AgentContextSummarizer(config, fake, AgentRunController(), false, events::add,
                support = AgentCompactionSupport(hierarchical = true)).summarize(history(1))
            assertEquals(2, fake.leaves)
            val leaves = fake.requests.filter { JSONObject(it.messages.getJSONObject(1).getString("content")).has("history_data") }
            assertEquals(leaves[0].messages.getJSONObject(1).toString(), leaves[1].messages.getJSONObject(1).toString())
            assertTrue(leaves[1].messages.getJSONObject(0).getString("content").contains("返回${count}条"))
            assertTrue(leaves[1].messages.getJSONObject(0).getString("content").contains("不要机械截断"))
            val diagnostic = JSONObject(events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().diagnosticJson!!)
            assertEquals(count, diagnostic.getInt("summary_fact_count"))
            assertEquals("fact_count", diagnostic.getString("summary_structure_reason"))
        }
    }

    @Test fun repeatedExcessFactsStillFailAndNeverPublishUnverifiedCheckpoint() {
        val fake = Fake().apply { transform = { body, leaf -> if (leaf) body.put("facts", JSONArray().also { a ->
            repeat(17) { a.put(JSONObject().put("section", "confirmed").put("text", "fixture")) }
        }) } }
        val store = Store()
        assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals(2, fake.leaves)
        assertTrue(store.values.isEmpty())
    }

    @Test fun verifiedCheckpointCanCommitWithoutModelWhenFinalRequestFits() {
        val source = history(12)
        val store = Store()
        val support = AgentCompactionSupport(hierarchical = true, treeStore = store)
        assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, Fake().apply { failMerge = true }, AgentRunController(), false, support = support).summarize(source)
        }
        val before = JSONObject(store.values.values.single())
        val completed = before.getInt("completed")
        val resumed = Fake().apply { failMerge = true }
        val summarizer = AgentContextSummarizer(config, resumed, AgentRunController(), false, support = support)
        val rendered = summarizer.summarize(source) { _, _ -> true }
        assertTrue(rendered.contains("未做跨区间语义归并"))
        assertEquals(0, resumed.leaves + resumed.merges)
        val after = JSONObject(store.values.values.single())
        assertEquals(completed, after.getInt("merged"))
        assertEquals(0, after.getJSONArray("pending").length())
        assertEquals(completed, after.getJSONObject("memory").getJSONArray("facts").length())
        assertEquals(source.size, summarizer.coveredMessages)
        assertTrue(source.first().content.contains("合成旧观察"))
    }

    @Test fun rejectedPackedCandidateStillUsesBoundedModelPath() {
        val fake = Fake()
        AgentContextSummarizer(config, fake, AgentRunController(), false,
            support = AgentCompactionSupport(hierarchical = true)).summarize(history(1)) { _, _ -> false }
        assertEquals(1, fake.merges)
    }
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture",
        systemPrompt = "fixture", contextWindow = 128_000)
    private class Store : AgentCompactionTreeStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]?.let(::JSONObject)
        override fun save(key: String, state: JSONObject) { values[key] = state.toString() }
        override fun clear(key: String) { values.remove(key) }
    }
    private class Fake : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        var leaves = 0
        var merges = 0
        var failMerge = false
        var oversized = false
        var invalidLeaves = 0
        var invalidMerges = 0
        var mergeTimeouts = 0
        var timeoutCause: Exception? = null
        var trace: ModelHttpTrace? = null
        var finishReason = "stop"
        var transform: (JSONObject, Boolean) -> Unit = { _, _ -> }
        val requests = mutableListOf<ProviderRequest>()
        val groups = mutableListOf<List<String>>()
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            requests += request
            assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
            assertEquals(0, request.effectiveTools.length())
            val data = JSONObject(request.messages.getJSONObject(1).getString("content"))
            val body = if (data.has("history_data")) {
                leaves++
                JSONObject().put("schema", 1).put("source", data.getString("source"))
                    .put("facts", JSONArray().put(JSONObject().put("section", "confirmed").put("text", if (oversized) "x".repeat(4001) else "已验证事实$leaves")))
            } else {
                merges++
                if (failMerge) throw AgentModelFailure("HTTP_503", false, "fixture")
                val intervals = data.getJSONArray("intervals")
                val ids = (0 until intervals.length()).map { intervals.getJSONObject(it).getString("source") }
                groups += ids
                if (mergeTimeouts-- > 0) {
                    request.onTransportPhase?.invoke("response_body")
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "abc"))
                    throw AgentCompactionDeadlineException(timeoutCause)
                }
                val memory = data.getJSONObject("memory")
                val facts = memory.getJSONArray("facts")
                val deltas = JSONArray()
                for (i in ids.indices) {
                    val item = JSONObject().put("id", "state").put("section", "confirmed").put("text", "按时间合并的状态")
                    val adding = facts.length() == 0 && i == 0
                    deltas.put(JSONObject().put("add", JSONArray().also { if (adding) it.put(item) })
                        .put("update", JSONArray().also { if (!adding) it.put(item) }).put("remove", JSONArray()))
                }
                JSONObject().put("schema", 2).put("base_revision", memory.getInt("revision")).put("deltas", deltas)
            }
            transform(body, data.has("history_data"))
            val invalid = if (data.has("history_data")) invalidLeaves-- > 0 else invalidMerges-- > 0
            return ProviderResponse(JSONObject().put("content", if (invalid) "sensitive-invalid-fixture" else body.toString()).put("finish_reason", finishReason), trace)
        }
    }
    private fun history(count: Int = 30) = (0 until count).map {
        AgentModelClient.ConversationMessage("assistant", "合成旧观察$it " + "x".repeat(20_000), messageId = "m$it")
    }

    @Test fun independentLeavesAndOrderedReductionCanStopAtCompletePrefix() {
        val fake = Fake()
        val store = Store()
        val source = history()
        val summary = AgentContextSummarizer(config, fake, AgentRunController(), false,
            support = AgentCompactionSupport(hierarchical = true, treeStore = store))
        val rendered = summary.summarize(source) { _, count -> count >= 10 }
        assertTrue(summary.coveredMessages in 10 until source.size)
        assertTrue(fake.leaves < 15)
        assertTrue(rendered.contains("journal#"))
        assertTrue(fake.groups.flatten().zipWithNext().all { (a, b) -> a.substringAfter('s').substringBefore(':').toInt() < b.substringAfter('s').substringBefore(':').toInt() })
        assertEquals(1, store.values.size)
        summary.clearCompletedCheckpoint()
        assertTrue(store.values.isEmpty())
    }

    @Test fun mergeFailureResumesCachedLeavesEvenAfterChangingModel() {
        val store = Store()
        val fake = Fake().apply { failMerge = true }
        val source = history(12)
        val support = AgentCompactionSupport(hierarchical = true, treeStore = store)
        assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false, support = support).summarize(source)
        }
        val completed = JSONObject(store.values.values.single()).getInt("completed")
        assertEquals(fake.leaves, completed)
        val resumed = Fake()
        val summarizer = AgentContextSummarizer(config.copy(model = "other"), resumed, AgentRunController(), false, support = support)
        assertTrue(summarizer.summarize(source).contains("按时间"))
        assertEquals(source.size, summarizer.coveredMessages)
        assertTrue(resumed.groups.first().first().startsWith("s1:"))
        assertEquals(1, store.values.size)
        val baseline = Fake()
        AgentContextSummarizer(config, baseline, AgentRunController(), false,
            support = AgentCompactionSupport(hierarchical = true)).summarize(source)
        assertEquals(baseline.leaves, fake.leaves + resumed.leaves)
    }

    @Test fun boundMergeResumesVersionTwoCheckpointWithLegacyMemoryRevision() {
        val source = history(30)
        val store = Store()
        val support = AgentCompactionSupport(hierarchical = true, treeStore = store)
        val first = Fake()
        AgentContextSummarizer(config, first, AgentRunController(), false, support = support).summarize(source) { text, count -> count >= 10 && text.contains("按时间") }
        val key = store.values.keys.single()
        val cached = JSONObject(store.values.getValue(key))
        assertTrue(cached.getInt("merged") > 1)
        cached.getJSONObject("memory").put("revision", 1) // Legacy grouped reducer increments once per batch.
        store.values[key] = cached.toString()
        val next = Fake()
        AgentContextSummarizer(config.copy(model = "changed"), next, AgentRunController(), false, support = support).summarize(source)
        val result = JSONObject(store.values.getValue(key))
        assertEquals(2, result.getInt("version"))
        assertEquals(1, result.getJSONObject("memory").getInt("schema"))
        assertTrue(result.getJSONObject("memory").getInt("revision") <= result.getInt("merged"))
        assertTrue(next.groups.first().first().substringAfter("s").substringBefore(":").toInt() > 1)
    }

    @Test fun invalidLeafNeverAdvancesCheckpointOrInput() {
        val store = Store()
        val fake = Fake().apply { oversized = true }
        val source = history(2)
        assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(source)
        }
        assertTrue(store.values.isEmpty())
        assertEquals(history(2)[0].content, source[0].content)
    }

    @Test fun finalSnapshotFailureKeepsJournalAndCheckpoint() {
        val store = Store()
        val source = JSONArray(history(12).map(AgentConversationCodec::toJsonObject))
        source.put(AgentConversationCodec.userTextMessage("保留当前请求"))
        val before = source.toString()
        val session = AgentContextSession(config, source, 0, "fixture", Fake(), AgentRunController(), { emptySet() },
            onContextSnapshot = { error("storage failure") },
            compactionSupport = AgentCompactionSupport(hierarchical = true, treeStore = store))
        assertThrows(IllegalStateException::class.java) { session.compact(force = true) }
        assertEquals(before, source.toString())
        assertTrue(store.values.isNotEmpty())
    }

    @Test fun fileTreeRoundTripAndCorruptionIsNotSilentlyDiscarded() {
        val dir = Files.createTempDirectory("eta-tree").toFile()
        try {
            val key = "a".repeat(64)
            val store = FileAgentCompactionTreeStore(dir)
            store.save(key, JSONObject().put("version", 2))
            assertEquals(2, store.read(key)!!.getInt("version"))
            java.io.File(dir, "$key.json").writeText("broken")
            assertThrows(Exception::class.java) { store.read(key) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun thousandsOfSyntheticMessagesFitWithoutReplayingJournalOrTools() {
        val source = (0 until 3106).map { AgentModelClient.ConversationMessage("assistant", "x".repeat(1500), messageId = "m$it") }
        val messages = JSONArray(source.map(AgentConversationCodec::toJsonObject)).put(AgentConversationCodec.userTextMessage("继续当前任务"))
        val original = messages.toString()
        val fake = Fake()
        val compacted = AgentContextCompactor(config, fake, AgentRunController(),
            support = AgentCompactionSupport(hierarchical = true), candidateFits = {
                val budget = AgentRequestBudget.evaluate(config, it, JSONArray())
                budget.requestBytes <= 512 * 1024 && budget.estimatedTokens <= 128_000 * 0.80
            }).compact(messages, 0, emptySet())
        val finalBudget = AgentRequestBudget.evaluate(config, compacted, JSONArray())
        assertTrue(finalBudget.requestBytes <= 512 * 1024)
        assertTrue(finalBudget.estimatedTokens <= 128_000 * 0.80)
        assertTrue(fake.leaves + fake.merges <= 128)
        assertEquals(original, messages.toString())
        assertTrue(compacted.getJSONObject(0).optBoolean("_eta_context_summary"))
        assertEquals("继续当前任务", compacted.getJSONObject(compacted.length() - 1).getString("content"))
    }

    @Test fun cancellationLeavesUnchangedSourceAndVerifiedNodes() {
        val controller = AgentRunController()
        val store = Store()
        val source = history(20)
        val fake = Fake()
        assertThrows(Exception::class.java) {
            AgentContextSummarizer(config, fake, controller, false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store),
                onProgress = { count, _ -> if (count == 2) controller.cancel() }).summarize(source)
        }
        assertEquals(2, JSONObject(store.values.values.single()).getInt("completed"))
        assertEquals(2, fake.leaves)
    }

    @Test fun cachedRangeDiscontinuityFailsBeforeAnyRequestAndIsPreserved() {
        val store = Store()
        val support = AgentCompactionSupport(hierarchical = true, treeStore = store)
        val source = history(20)
        assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, Fake().apply { failMerge = true }, AgentRunController(), false, support = support).summarize(source)
        }
        val key = store.values.keys.single()
        val corrupt = JSONObject(store.values.getValue(key))
        corrupt.getJSONArray("pending").getJSONObject(0).put("source", "s999:journal#invented")
        store.values[key] = corrupt.toString()
        val provider = Fake()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, provider, AgentRunController(), false, support = support).summarize(source)
        }
        assertEquals("CONTEXT_SUMMARY_CHECKPOINT_INVALID", failure.code)
        assertEquals(0, provider.leaves + provider.merges)
        assertEquals(corrupt.toString(), store.values.getValue(key))
    }

    @Test fun invalidLeafGetsOneStrictRegenerationWithOriginalInputAndSafeDiagnostic() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.COMPACTION, log = {}).also { it.terminalReceived() }
        val fake = Fake().apply { invalidLeaves = 1; this.trace = trace }
        val events = mutableListOf<AgentEvent>()
        AgentContextSummarizer(config, fake, AgentRunController(), false, events::add,
            support = AgentCompactionSupport(hierarchical = true)).summarize(history(1))
        assertEquals(fake.requests[0].messages.getJSONObject(1).toString(), fake.requests[1].messages.getJSONObject(1).toString())
        assertTrue(fake.requests[1].messages.getJSONObject(0).getString("content").contains("上次结构校验未通过"))
        val retry = events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single()
        assertTrue(retry.displayMessage.startsWith("上下文压缩：摘要结构校验未通过"))
        assertEquals("leaf", JSONObject(retry.diagnosticJson!!).getString("summary_validation"))
        assertEquals("json", JSONObject(retry.diagnosticJson!!).getString("summary_structure_reason"))
        assertEquals(trace.traceId, JSONObject(retry.diagnosticJson!!).getString("trace_id"))
        assertFalse(retry.diagnosticJson!!.contains("sensitive-invalid-fixture"))
    }

    @Test fun repeatedInvalidOutputStopsAfterOneRecoveryWithoutSavingUnverifiedNode() {
        val fake = Fake().apply { invalidLeaves = 99 }
        val store = Store()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals(2, fake.leaves)
        assertTrue(store.values.isEmpty())
        assertEquals("leaf", JSONObject(failure.diagnostic()!!).getString("summary_validation"))
        val unsafe = ModelFailureDiagnostic.sanitize("{\"summary_validation\":\"secret\",\"raw\":\"secret\"}")
        assertNull(unsafe)
    }

    @Test fun invalidMergeRegeneratesPatchWithoutExtractingLeavesAgain() {
        val fake = Fake().apply { invalidMerges = 1 }
        AgentContextSummarizer(config, fake, AgentRunController(), false,
            support = AgentCompactionSupport(hierarchical = true)).summarize(history(1))
        assertEquals(1, fake.leaves)
        assertEquals(2, fake.merges)
        assertEquals(fake.groups[0], fake.groups[1])
    }

    @Test fun fieldRecoveryTargetsPatchAndPreservesOriginalData() {
        val fake = Fake().apply { transform = { body, leaf -> if (!leaf && merges == 1) { body.remove("deltas"); body.put("private-extra", "private-text") } } }
        val events = mutableListOf<AgentEvent>()
        AgentContextSummarizer(config, fake, AgentRunController(), false, events::add,
            support = AgentCompactionSupport(hierarchical = true)).summarize(history(1))
        val retry = events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single()
        val diagnostic = JSONObject(retry.diagnosticJson!!)
        assertEquals("bound_patch", diagnostic.getString("summary_field_scope"))
        assertEquals("deltas", diagnostic.getJSONArray("summary_missing_fields").getString(0))
        assertEquals(1, diagnostic.getInt("summary_extra_field_count"))
        val requests = fake.requests.filter { JSONObject(it.messages.getJSONObject(1).getString("content")).has("memory") }
        assertEquals(2, requests.size)
        assertEquals(requests[0].messages.getJSONObject(1).toString(), requests[1].messages.getJSONObject(1).toString())
        val instruction = requests[1].messages.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("不得返回memory"))
        assertTrue(instruction.contains("上次缺少的字段：deltas"))
        assertFalse(instruction.contains("private"))
        assertEquals(1, fake.leaves)
    }

    @Test fun factFieldFailureKeepsCheckpointAndOmitsUnknownKeysFromUi() {
        val fake = Fake().apply { transform = { body, leaf -> if (!leaf) body.getJSONArray("deltas").getJSONObject(0).getJSONArray("add").getJSONObject(0).put("private-extra-name", "private-text") } }
        val store = Store()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals("bound_fact", JSONObject(failure.diagnostic()!!).getString("summary_field_scope"))
        assertTrue(failure.message!!.contains("增量事实"))
        assertFalse(failure.message!!.contains("private"))
        assertEquals(2, fake.merges)
        assertEquals(0, JSONObject(store.values.values.single()).getInt("merged"))
        assertTrue(fake.requests.last().messages.getJSONObject(0).getString("content").contains("来源由客户端"))
    }

    @Test fun leafReasonIsPreciseAndNoRejectedNodeIsSaved() {
        val fake = Fake().apply { transform = { body, leaf -> if (leaf) body.put("source", "private-source") } }
        val store = Store()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals("source", JSONObject(failure.diagnostic()!!).getString("summary_structure_reason"))
        assertTrue(failure.message!!.contains("来源标识"))
        assertFalse(failure.message!!.contains("private-source"))
        assertEquals(2, fake.leaves)
        assertTrue(store.values.isEmpty())
        assertTrue(fake.requests.first().messages.getJSONObject(0).getString("content").contains("逐字复制"))
    }

    @Test fun mergeReasonPreservesLeavesAndRecoveryBudgetRemainsShared() {
        val fake = Fake().apply {
            invalidLeaves = 1
            transform = { body, leaf -> if (!leaf) body.put("base_revision", 99) }
        }
        val store = Store()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals("base_revision", JSONObject(failure.diagnostic()!!).getString("summary_structure_reason"))
        assertEquals(1, fake.merges)
        val checkpoint = JSONObject(store.values.values.single())
        assertEquals(1, checkpoint.getInt("completed"))
        assertEquals(0, checkpoint.getInt("merged"))
        assertEquals(0, checkpoint.getJSONObject("memory").getInt("revision"))
        val instruction = fake.requests.last().messages.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("分别最多32项"))
        assertTrue(instruction.contains("不能加1"))
    }

    @Test fun wholeDeadlineIsNotReportedAsSingleRequestTimeout() {
        var now = 0L
        var calls = 0
        val store = Store()
        val events = mutableListOf<AgentEvent>()
        val support = AgentCompactionSupport(hierarchical = true, treeStore = store, clock = { now }, budgetMs = 100,
            limiter = AgentCompactionCallLimiter { parent, _, call ->
                if (++calls == 2) { now = 100; throw AgentCompactionDeadlineException() }
                call(parent)
            })
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, Fake(), AgentRunController(), false, events::add, support = support).summarize(history(1))
        }
        assertEquals("CONTEXT_SUMMARY_TIME_BUDGET", failure.code)
        assertEquals(1, JSONObject(store.values.values.single()).getInt("completed"))
        assertEquals(0, JSONObject(store.values.values.single()).getInt("merged"))
        assertTrue(events.filterIsInstance<AgentEvent.ModelRetryScheduled>().isEmpty())
    }

    @Test fun formatAndTransportRecoveryShareOneBudget() {
        val fake = Fake().apply { invalidLeaves = 1; mergeTimeouts = 1 }
        val store = Store()
        val events = mutableListOf<AgentEvent>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextSummarizer(config, fake, AgentRunController(), false, events::add,
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals("CONTEXT_SUMMARY_TIMEOUT", failure.code)
        assertTrue(failure.message!!.contains("合并请求超时"))
        assertEquals(1, fake.merges)
        assertEquals(1, JSONObject(store.values.values.single()).getInt("completed"))
        assertEquals(1, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().size)
    }

    @Test fun deadlineKeepsSafeTransportEvidenceOrCurrentAttemptCounters() {
        for (withTrace in listOf(false, true)) {
            val traceId = "12345678-1234-1234-1234-123456789abc"
            val fake = Fake().apply {
                invalidLeaves = 1
                mergeTimeouts = 1
                if (withTrace) timeoutCause = AgentModelFailure("MODEL_CONNECTION_FAILED", true, "private-message")
                    .withRequestTrace(traceId, "response_body", streamMetrics = mapOf("reasoning_chars" to 42))
            }
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentContextSummarizer(config, fake, AgentRunController(), false,
                    support = AgentCompactionSupport(hierarchical = true, treeStore = Store())).summarize(history(1))
            }
            assertEquals("CONTEXT_SUMMARY_TIMEOUT", failure.code)
            val diagnostic = JSONObject(failure.diagnostic()!!)
            assertEquals("response_body", diagnostic.getString("client_phase"))
            assertEquals(if (withTrace) 42L else 3L, diagnostic.getLong("reasoning_chars"))
            if (withTrace) assertEquals(traceId, diagnostic.getString("trace_id"))
            assertFalse(diagnostic.toString().contains("private-message"))
        }
    }

    @Test fun toolOrNonTerminalOutputDoesNotGetStructureRegeneration() {
        for (reason in listOf("tool_calls", "length", "content_filter", "unknown")) {
            val fake = Fake().apply { finishReason = reason }
            val store = Store()
            val events = mutableListOf<AgentEvent>()
            assertThrows(AgentModelFailure::class.java) {
                AgentContextSummarizer(config, fake, AgentRunController(), false, events::add,
                    support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
            }
            assertEquals(reason, 1, fake.leaves)
            assertTrue(store.values.isEmpty())
            assertTrue(events.filterIsInstance<AgentEvent.ModelRetryScheduled>().isEmpty())
        }
    }

    @Test fun cancelBeforeRegenerationMakesNoFurtherRequest() {
        val controller = AgentRunController()
        val fake = Fake().apply { invalidLeaves = 1 }
        val store = Store()
        assertThrows(Exception::class.java) {
            AgentContextSummarizer(config, fake, controller, false,
                onEvent = { if (it is AgentEvent.ModelRetryScheduled) controller.cancel() },
                support = AgentCompactionSupport(hierarchical = true, treeStore = store)).summarize(history(1))
        }
        assertEquals(1, fake.leaves)
        assertTrue(store.values.isEmpty())
    }
}
