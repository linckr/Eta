package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AgentRequestSendPolicyTest {
    private val anchor = 1_782_914L
    private val growth = 445_729L
    private val limit = 2_228_643L
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "fixed", contextWindow = 1_000_000,
        compactionPolicyVersion = 4, requestSendLimitBytes = limit, requestObservedBytes = anchor,
        requestGrowthAllowanceBytes = growth)

    @Test fun frozenGrowthIsBoundedAndOverflowSafe() {
        assertEquals(growth, AgentRequestSendPolicy.growthAllowance(anchor))
        assertEquals(limit, AgentRequestSendPolicy.deriveSendLimit(anchor))
        assertEquals(262_144L, AgentRequestSendPolicy.growthAllowance(1))
        assertEquals(524_288L, AgentRequestSendPolicy.growthAllowance(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, AgentRequestSendPolicy.deriveSendLimit(Long.MAX_VALUE))
        assertEquals(2_000_000L, AgentRequestSendPolicy.deriveSendLimit(anchor, 2_000_000))
    }

    @Test fun observedPlusOneDoesNotTriggerButPolicyPlusOneDoes() {
        val session = session(config, history(), provider { error("No summary within frozen growth allowance") })
        assertFalse(session.prepareForRequest(100, anchor + 1))
        assertFalse(session.prepareForRequest(100, limit))
        assertFalse(AgentRequestSendPolicy.guardExceeded(config, anchor + 1))
        assertTrue(AgentRequestSendPolicy.guardExceeded(config, limit + 1))
        assertFalse(AgentRequestSendPolicy.guardExceeded(config.copy(requestObservedBytes = Long.MAX_VALUE), limit))
        assertTrue(AgentRequestSendPolicy.guardExceeded(config.copy(requestObservedBytes = Long.MAX_VALUE), limit + 1))
    }

    @Test fun nullAndZeroDisableOnlySoftTriggerAndPositiveBudgetStillTriggersWithinLimit() {
        for (soft in listOf(null, 0)) assertEquals(0, AgentRequestBytePolicy.resolve(config.copy(requestByteBudget = soft)))
        assertEquals(100, AgentRequestBytePolicy.resolve(config.copy(requestByteBudget = 100)))
        var summaries = 0
        val active = session(config.copy(requestByteBudget = 100), history(), provider {
            summaries++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture")
        })
        assertFalse(active.prepareForRequest(100, 100))
        assertEquals(0, summaries)
        assertFalse(active.prepareForRequest(100, 101))
        assertTrue(summaries > 0)
    }

    @Test fun finalGuardIncludesEqualityAndNeverTreatsObservedAsSendLimit() {
        AgentRequestSendPolicy.requireAllowed(config, limit)
        assertEquals("MODEL_REQUEST_SEND_LIMIT", assertThrows(AgentModelFailure::class.java) {
            AgentRequestSendPolicy.requireAllowed(config, limit + 1)
        }.code)
        assertEquals("MODEL_REQUEST_HARD_LIMIT", assertThrows(AgentModelFailure::class.java) {
            AgentRequestSendPolicy.requireAllowed(config.copy(requestHardLimitBytes = anchor), anchor + 1)
        }.code)
        assertFalse(AgentRequestSendPolicy.allowsOriginal(config, null))
        assertEquals("MODEL_REQUEST_SEND_POLICY_INVALID", assertThrows(AgentModelFailure::class.java) {
            AgentRequestSendPolicy.requireAllowed(config.copy(requestSendLimitBytes = null), 1)
        }.code)
    }

    @Test fun legacyPolicyPreservesOldDerivedBudgetAndDoesNotAddTransportGuard() {
        val legacy = config.copy(compactionPolicyVersion = 0, requestSendLimitBytes = 1, requestHardLimitBytes = 1)
        assertEquals(1_048_576, AgentRequestBytePolicy.resolve(legacy))
        AgentRequestSendPolicy.requireAllowed(legacy, Long.MAX_VALUE)
        assertTrue(AgentRequestSendPolicy.allowsOriginal(legacy, null))
    }

    @Test fun goalReleasesFullFrozenGrowthAndTenRoundsWithinGrowthDoNotRetrigger() {
        val goal = AgentByteCompactionGoal.forPolicy("bytes", 0, config)
        val target = goal.targetBytes!!
        assertTrue(limit - target >= growth)
        repeat(10) { index ->
            val size = target + growth * (index + 1) / 10
            assertFalse(AgentRequestSendPolicy.guardExceeded(config, size))
        }
        assertEquals(target, AgentByteCompactionGoal.forPolicy("bytes", 0,
            config.copy(requestObservedBytes = Long.MAX_VALUE)).targetBytes)
    }

    @Test fun failureAboveGuardCannotFallBackToOriginalOrUseKnownFailureSuppression() {
        val store = MemoryStore()
        var calls = 0
        val bad = provider { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        val active = session(config.copy(requestByteBudget = 100), history(), bad, store)
        assertFalse(active.prepareForRequest(100, 1_000))
        assertEquals(1, store.records.size)
        val first = calls
        val blocked = assertThrows(AgentModelFailure::class.java) { active.prepareForRequest(100, limit + 1) }
        assertEquals("MODEL_REQUEST_SEND_LIMIT", blocked.code)
        assertEquals(first, calls)
        assertThrows(AgentModelFailure::class.java) { active.compact(force = true) }
        assertTrue(calls > first)
    }

    @Test fun knownSummaryCaptureAndOutputFailuresFallBackWithinPolicyButNeverAboveIt() {
        for (code in listOf("CONTEXT_SUMMARY_CAPTURE_LIMIT", "CONTEXT_SUMMARY_OUTPUT_BUDGET")) {
            val store = MemoryStore()
            val body = history()
            val original = body.toString()
            var calls = 0
            val bad = provider { calls++; throw AgentModelFailure(code, false, "fixture") }
            val current = config.copy(requestByteBudget = 100)
            val active = session(current, body, bad, store)
            assertFalse("$code falls back inside L", active.prepareForRequest(100, 1_000))
            assertEquals(original, body.toString())
            assertEquals(code, store.records.values.single().failureCode)
            val first = calls
            active.userAppended()
            assertFalse(active.prepareForRequest(100, 1_001))
            assertEquals("$code remains suppressed after new user turn", first, calls)
            val resumed = session(current, body, bad, store)
            assertFalse(resumed.prepareForRequest(100, 1_002))
            assertEquals("$code persists across runs", first, calls)
            assertEquals("MODEL_REQUEST_SEND_LIMIT", assertThrows(AgentModelFailure::class.java) {
                resumed.prepareForRequest(100, limit + 1)
            }.code)
            assertEquals("$code never retries automatically above L", first, calls)
            val separate = session(current, history(), bad)
            assertEquals(code, assertThrows(AgentModelFailure::class.java) {
                separate.prepareForRequest(100, limit + 1)
            }.code)
            assertTrue("$code above L propagates without fallback", calls > first)
        }
    }

    @Test fun persistentFailureSurvivesNewRunAndUserAppendButNotSourceEditOrAlgorithmVersion() {
        val store = MemoryStore()
        var calls = 0
        val bad = provider { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        val body = history()
        val current = config.copy(requestByteBudget = 100)
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val first = calls
        body.put(JSONObject().put("role", "user").put("content", "appended"))
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_001))
        assertEquals(first, calls)
        body.getJSONObject(1).put("content", "edited " + "x".repeat(8_000))
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_002))
        assertTrue(calls > first)
        val afterEdit = calls
        val entry = store.records.entries.first()
        entry.setValue(entry.value.copy(algorithmVersion = -1))
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_003))
        assertTrue(calls > afterEdit)
    }

    @Test fun wakeScreenMetadataAndTransientObservationDoNotBreakDurablePrefixIdentity() {
        val store = MemoryStore()
        var calls = 0
        val bad = provider { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        val current = config.copy(requestByteBudget = 100)
        val body = history()
        AssistantScreenContextProjection.attach(body.getJSONObject(2), "run-only screen content")
        body.put(JSONObject().put("role", "assistant").put("content", "transient screen observation")
            .put("_eta_observation", true))
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val first = calls
        val durable = JSONArray()
        for (i in 0 until body.length()) {
            val message = body.getJSONObject(i)
            if (!message.optBoolean("_eta_observation")) {
                durable.put(AgentConversationCodec.toJsonObject(AgentConversationCodec.durableMessage(message)))
            }
        }
        durable.put(JSONObject().put("role", "user").put("content", "new turn"))
        assertFalse(session(current, durable, bad, store).prepareForRequest(100, 1_001))
        assertEquals(first, calls)
        durable.getJSONObject(1).put("content", "changed actual durable result " + "x".repeat(8_000))
        assertFalse(session(current, durable, bad, store).prepareForRequest(100, 1_002))
        assertTrue(calls > first)
    }

    @Test fun persistedByteFailureNeverSuppressesTokenPressureOrManualRetry() {
        val store = MemoryStore()
        var calls = 0
        val bad = provider { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        val current = config.copy(requestByteBudget = 100)
        val body = history()
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val count = calls
        val active = session(current, body, bad, store)
        assertThrows(AgentModelFailure::class.java) { active.prepareForRequest(900_000, 1_000) }
        assertTrue(calls > count)
        val afterToken = calls
        assertThrows(AgentModelFailure::class.java) { active.compact(force = true) }
        assertTrue(calls > afterToken)
    }

    @Test fun failureStoreErrorsPropagateAndDoNotMutateOriginalHistory() {
        val store = object : AgentCompactionFailureStore {
            override fun fingerprint(parts: Sequence<String>) = "fixture"
            override fun load(key: String): AgentCompactionFailureRecord? = error("storage unavailable")
            override fun save(record: AgentCompactionFailureRecord) = error("storage unavailable")
            override fun clear(key: String) = Unit
        }
        val body = history()
        val original = body.toString()
        assertThrows(IllegalStateException::class.java) {
            session(config.copy(requestByteBudget = 100), body, provider { error("No request") }, store)
                .prepareForRequest(100, 1_000)
        }
        assertEquals(original, body.toString())
    }

    @Test fun changingRouteBudgetOrSelectedSummaryModelInvalidatesFailureButObservedDoesNot() {
        val store = MemoryStore()
        var calls = 0
        val bad = provider { calls++; throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        val body = history()
        val current = config.copy(requestByteBudget = 100)
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val first = calls
        assertFalse(session(current.copy(requestObservedBytes = limit), body, bad, store).prepareForRequest(100, 1_001))
        assertEquals(first, calls)
        for (changed in listOf(current.copy(providerId = "other"), current.copy(requestByteBudget = 101),
            current.copy(compactionModelId = "other", compactionModelConfig = current.copy(model = "summary-other")))) {
            val before = calls
            assertFalse(session(changed, body, bad, store).prepareForRequest(100, 1_001))
            assertTrue(calls > before)
        }
    }

    @Test fun sourceFingerprintIsNotRecomputedAfterEveryToolRound() {
        val store = MemoryStore()
        val body = history()
        repeat(5_000) { body.put(JSONObject().put("role", "assistant").put("content", "verified $it")) }
        val current = config.copy(requestByteBudget = 100)
        val bad = provider { throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val active = session(current, body, bad, store)
        assertFalse(active.prepareForRequest(100, 1_001))
        val fingerprints = store.fingerprintCalls
        repeat(10) {
            body.put(JSONObject().put("role", "assistant").put("content", "new completed tool $it"))
            assertFalse(active.prepareForRequest(100, 1_002L + it))
        }
        assertEquals(fingerprints, store.fingerprintCalls)
    }

    @Test fun failedSnapshotPublicationKeepsOriginalHistoryAndFailureRecord() {
        val store = MemoryStore()
        val body = history()
        val current = config.copy(requestByteBudget = 100)
        val bad = provider { throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val original = body.toString()
        val records = store.records.toMap()
        val active = AgentContextSession(current, body, 1, "new-run", provider { response("short verified summary") },
            AgentRunController(), { emptySet() }, onContextSnapshot = { error("snapshot IO failure") },
            failureStore = store, failureScopeKey = "stable-conversation")
        assertThrows(IllegalStateException::class.java) { active.compact(force = true) }
        assertEquals(original, body.toString())
        assertEquals(records, store.records)
    }

    @Test fun cleanupFailureAfterCommitDoesNotRollBackNewContextOrReportSummaryFailure() {
        val delegate = MemoryStore()
        val store = object : AgentCompactionFailureStore by delegate {
            override fun clear(key: String) = error("cleanup IO failure")
        }
        val body = history()
        val current = config.copy(requestByteBudget = 100)
        val bad = provider { throw AgentModelFailure("CONTEXT_SUMMARY_INVALID", false, "fixture") }
        assertFalse(session(current, body, bad, store).prepareForRequest(100, 1_000))
        val originalLength = body.toString().length
        val logs = mutableListOf<String>()
        val active = AgentContextSession(current, body, 1, "new-run", provider { response("short verified summary") },
            AgentRunController(), { emptySet() }, onContextSnapshot = {}, onBudgetLog = logs::add,
            failureStore = store, failureScopeKey = "stable-conversation")
        active.compact(force = true)
        assertNotNull(active.snapshot())
        assertTrue(body.toString().length < originalLength)
        assertTrue(logs.any { it.contains("CONTEXT_FAILURE_RECORD_CLEANUP_FAILED") })
    }

    private fun history() = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
        .put(JSONObject().put("role", "assistant").put("content", "x".repeat(8_000)))
        .put(JSONObject().put("role", "user").put("content", "continue"))

    private fun session(current: AgentModelClient.ModelConfig, body: JSONArray, server: AgentProviderClient,
        store: AgentCompactionFailureStore? = null) = AgentContextSession(current, body, 1, "new-run",
        server, AgentRunController(), { emptySet() }, onContextSnapshot = {},
        failureStore = store, failureScopeKey = "stable-conversation", providerResolver = { server })

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse = block(request)
    }

    private fun response(text: String) = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))

    private class MemoryStore : AgentCompactionFailureStore {
        val records = mutableMapOf<String, AgentCompactionFailureRecord>()
        var fingerprintCalls = 0
        override fun fingerprint(parts: Sequence<String>): String {
            fingerprintCalls++
            val digest = MessageDigest.getInstance("SHA-256") // Test fixture only; production uses installation HMAC.
            parts.forEach { digest.update(it.toByteArray()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        override fun load(key: String) = records[key]
        override fun save(record: AgentCompactionFailureRecord) { records[record.key] = record }
        override fun clear(key: String) { records.remove(key) }
    }
}
