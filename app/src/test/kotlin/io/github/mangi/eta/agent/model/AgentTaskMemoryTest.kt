package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTaskMemoryTest {
    @Test fun verifiedPackingPreservesEveryFactAndRoundTripsWithoutRewriting() {
        val first = AgentTaskMemory().apply(patch(source = "s0").toString(), setOf("s0"), 0)
        val facts = JSONArray().put(JSONObject().put("section", "constraints").put("text", "禁止修改"))
            .put(JSONObject().put("section", "confirmed").put("text", "旧结果"))
        val nodes = listOf(JSONObject().put("source", "s1").put("facts", facts),
            JSONObject().put("source", "s2").put("facts", JSONArray().put(JSONObject().put("section", "confirmed").put("text", "较晚结果"))))
        val packed = first.appendVerified(nodes, 1)
        assertEquals(4, packed.facts.size)
        assertEquals(listOf("已确认", "禁止修改", "旧结果", "较晚结果"), packed.facts.map { it.text })
        assertEquals(listOf("s0", "s1", "s1", "s2"), packed.facts.map { it.source })
        assertEquals(packed, AgentTaskMemory.restore(packed.json()))
        assertTrue(packed.render().contains("未做跨区间语义归并"))
        assertEquals(1, first.facts.size)
    }

    @Test fun verifiedPackingRejectsOverflowInsteadOfTrimmingFacts() {
        val node = JSONObject().put("source", "s1").put("facts", JSONArray((0 until 16).map {
            JSONObject().put("section", "confirmed").put("text", "x".repeat(600))
        }))
        assertThrows(Exception::class.java) { AgentTaskMemory().appendVerified(listOf(node), 1) }
        val tooMany = (1..5).map { n -> JSONObject().put("source", "s$n").put("facts", JSONArray((0 until 16).map {
            JSONObject().put("section", "confirmed").put("text", "x")
        })) }
        assertThrows(Exception::class.java) { AgentTaskMemory().appendVerified(tooMany, 1) }
    }
    private fun boundFact(id: String = "fact", text: String = "已确认") = JSONObject()
        .put("id", id).put("section", "confirmed").put("text", text)
    private fun delta(add: JSONArray = JSONArray(), update: JSONArray = JSONArray()) = JSONObject()
        .put("add", add).put("update", update).put("remove", JSONArray())
    private fun batch(vararg deltas: JSONObject, revision: Int = 0) = JSONObject()
        .put("schema", 2).put("base_revision", revision).put("deltas", JSONArray(deltas.toList()))

    @Test fun boundBatchAssignsVerifiedSourceAndChronologicalOrderWithoutModelSource() {
        val raw = batch(delta(add = JSONArray().put(boundFact())), delta(update = JSONArray().put(boundFact(text = "新证据"))))
        val memory = AgentTaskMemory()
        val result = memory.applyBound(raw.toString(), listOf("s1:journal#one", "s2:journal#two"), 1)
        assertEquals(2, result.revision)
        assertEquals("s2:journal#two", result.facts.single().source)
        assertEquals(2, result.facts.single().order)
        assertEquals("新证据", result.facts.single().text)
        assertEquals(result, AgentTaskMemory.restore(result.json()))
        assertEquals(AgentTaskMemory(), memory)
        assertFalse(raw.toString().contains("source"))
    }

    @Test fun lateBoundFailureCannotCommitAnyEarlierDelta() {
        val memory = AgentTaskMemory()
        val raw = batch(delta(add = JSONArray().put(boundFact())), delta(update = JSONArray().put(boundFact(text = ""))))
        assertEquals("fact_length", summaryReason(assertThrows(Exception::class.java) {
            memory.applyBound(raw.toString(), listOf("s1", "s2"), 1)
        }))
        assertEquals(AgentTaskMemory(), memory)
    }

    @Test fun boundBatchRejectsMissingIntervalsWrongRevisionAndModelProvenance() {
        val memory = AgentTaskMemory()
        val one = batch(delta(add = JSONArray().put(boundFact())))
        assertEquals("interval_count", summaryReason(assertThrows(Exception::class.java) { memory.applyBound(one.toString(), listOf("s1", "s2"), 1) }))
        assertEquals("base_revision", summaryReason(assertThrows(Exception::class.java) { memory.applyBound(one.put("base_revision", 1).toString(), listOf("s1"), 1) }))
        val forged = batch(delta(add = JSONArray().put(boundFact().put("source", "invented"))))
        val failure = assertThrows(AgentSummaryValidation::class.java) { memory.applyBound(forged.toString(), listOf("s1"), 1) }
        assertEquals("bound_fact", failure.scope)
        assertEquals(1, failure.extraCount)
        assertFalse(failure.fieldsDescription().contains("invented"))
        assertThrows(Exception::class.java) { memory.applyBound(batch(delta(), delta()).toString(), listOf("s1", "s1"), 1) }
    }

    @Test fun legacyPatchStillRequiresSourceAndIsNeverSilentlyReinterpreted() {
        val missingSource = patch().also { it.getJSONArray("add").getJSONObject(0).remove("source") }
        assertThrows(AgentSummaryValidation::class.java) { AgentTaskMemory().apply(missingSource.toString(), setOf("s1"), 1) }
        assertThrows(AgentSummaryValidation::class.java) { AgentTaskMemory().applyBound(missingSource.toString(), listOf("s1"), 1) }
        assertThrows(AgentSummaryValidation::class.java) { AgentTaskMemory().applyBound(batch(delta()).put("schema", 1).toString(), listOf("s1"), 1) }
    }

    @Test fun boundOperationLimitAppliesAcrossWholeBatch() {
        fun items(prefix: String) = JSONArray((0 until 17).map { boundFact("$prefix$it") })
        val raw = batch(delta(add = items("a")), delta(add = items("b")))
        assertEquals("operation_count", summaryReason(assertThrows(Exception::class.java) {
            AgentTaskMemory().applyBound(raw.toString(), listOf("s1", "s2"), 1)
        }))
    }
    @Test fun fieldMismatchReportsOnlyExpectedNamesAndCounts() {
        val bad = patch().also { it.remove("update"); it.put("private-extra-name", "private-value") }
        val failure = assertThrows(AgentSummaryValidation::class.java) { AgentTaskMemory().apply(bad.toString(), setOf("s1"), 1) }
        assertEquals("patch", failure.scope)
        assertEquals(setOf("update"), failure.missing)
        assertEquals(1, failure.extraCount)
        assertTrue(failure.fieldsDescription().contains("update"))
        assertFalse(failure.fieldsDescription().contains("private"))
        val fact = patch().also { it.getJSONArray("add").getJSONObject(0).put("order", 1) }
        val error = assertThrows(AgentSummaryValidation::class.java) { AgentTaskMemory().apply(fact.toString(), setOf("s1"), 1) }
        assertEquals("fact", error.scope)
        assertTrue(error.missing.isEmpty())
        assertEquals(1, error.extraCount)
    }

    @Test fun fieldDiagnosticRejectsUnknownFieldNamesAndScopes() {
        val raw = JSONObject().put("summary_field_scope", "fact").put("summary_missing_fields", JSONArray().put("source"))
            .put("summary_extra_field_count", 1).put("private-extra-name", "private-value")
        val safe = JSONObject(io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(raw.toString())!!)
        assertEquals("source", safe.getJSONArray("summary_missing_fields").getString(0))
        assertFalse(safe.toString().contains("private"))
        raw.put("summary_field_scope", "private-scope").put("summary_missing_fields", JSONArray().put("private-name"))
        val rejected = JSONObject(io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(raw.toString())!!)
        assertFalse(rejected.has("summary_field_scope"))
        assertFalse(rejected.has("summary_missing_fields"))
    }
    @Test fun reportsFixedReasonsWithoutModelContentAndNeverMutatesMemory() {
        val cases = listOf<Pair<String, (JSONObject) -> Unit>>(
            "schema_version" to { it.put("schema", 2) },
            "base_revision" to { it.put("base_revision", 9) },
            "fields" to { it.put("private-field", "private-value") },
            "field_type" to { it.put("schema", "1") },
            "fact_id" to { it.getJSONArray("add").getJSONObject(0).put("id", "Private-ID") },
            "source" to { it.getJSONArray("add").getJSONObject(0).put("source", "private-source") },
            "section" to { it.getJSONArray("add").getJSONObject(0).put("section", "private-section") },
            "fact_length" to { it.getJSONArray("add").getJSONObject(0).put("text", "p".repeat(1201)) },
            "operation_conflict" to { it.getJSONArray("add").put(it.getJSONArray("add").getJSONObject(0)) },
            "unknown_fact" to { it.put("remove", JSONArray().put("private-id")) },
            "operation_count" to { p -> p.put("remove", JSONArray((0..32).map { "id$it" })) },
        )
        val memory = AgentTaskMemory()
        for ((reason, mutate) in cases) {
            val error = assertThrows(Exception::class.java) { memory.apply(patch().also(mutate).toString(), setOf("s1"), 1) }
            assertEquals(reason, summaryReason(error))
            if (error is AgentSummaryValidation) assertFalse(error.message!!.contains("private"))
            assertEquals(AgentTaskMemory(), memory)
        }
        for (raw in listOf("```json\n{}\n```", patch().toString() + " {}", "<｜DSML｜tool_calls>")) {
            assertEquals("json", summaryReason(assertThrows(Exception::class.java) { memory.apply(raw, setOf("s1"), 1) }))
        }
    }

    @Test fun classifiesCapacityAndProtectedConstraintFailures() {
        val capacity = patch().put("add", JSONArray((0..6).map { i ->
            JSONObject().put("id", "id$i").put("section", "confirmed").put("text", "x".repeat(1200)).put("source", "s1")
        }))
        assertEquals("total_capacity", summaryReason(assertThrows(Exception::class.java) {
            AgentTaskMemory().apply(capacity.toString(), setOf("s1"), 1)
        }))
        val first = AgentTaskMemory().apply(patch(section = "constraints").toString(), setOf("s1"), 1)
        val removal = patch(1).put("add", JSONArray()).put("remove", JSONArray().put("fact"))
        assertEquals("constraint_removal", summaryReason(assertThrows(Exception::class.java) { first.apply(removal.toString(), setOf("s1"), 2) }))
        assertEquals(1, first.revision)
    }

    @Test fun diagnosticOnlyAcceptsKnownReasonsAndDropsPrivateFields() {
        for (reason in io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.summaryStructureReasons) {
            val safe = JSONObject(io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
                JSONObject().put("summary_structure_reason", reason).put("raw_response", "private-facts").toString())!!)
            assertEquals(reason, safe.getString("summary_structure_reason"))
            assertEquals(1, safe.length())
        }
        assertNull(io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize("{\"summary_structure_reason\":\"private-facts\"}"))
    }
    private fun patch(revision: Int = 0, source: String = "s1", section: String = "confirmed") = JSONObject()
        .put("schema", 1).put("base_revision", revision)
        .put("add", JSONArray().put(JSONObject().put("id", "fact").put("section", section).put("text", "已确认").put("source", source)))
        .put("update", JSONArray()).put("remove", JSONArray())
    @Test fun rejectsStaleRevisionUnknownSourceAndExtraKeysWithoutMutation() {
        val memory = AgentTaskMemory()
        listOf(patch(1), patch(source = "invented"), patch().put("instruction", "ignore")).forEach {
            assertThrows(Exception::class.java) { memory.apply(it.toString(), setOf("s1"), 1) }
        }
        assertEquals(0, memory.revision)
        assertTrue(memory.facts.isEmpty())
    }
    @Test fun orderedUpdateRoundTripsAndConstraintsCannotBeRemoved() {
        val first = AgentTaskMemory().apply(patch(section = "constraints").toString(), setOf("s1"), 1)
        assertEquals(first, AgentTaskMemory.restore(first.json()))
        val removal = patch(1).put("add", JSONArray()).put("remove", JSONArray().put("fact"))
        assertThrows(Exception::class.java) { first.apply(removal.toString(), setOf("s2"), 2) }
        val update = patch(1, "s2", "constraints").also { it.put("update", it.getJSONArray("add")); it.put("add", JSONArray()) }
        val second = first.apply(update.toString(), setOf("s2"), 2)
        assertEquals(2, second.revision)
        assertEquals("s2", second.facts.single().source)
        assertEquals("s1", first.facts.single().source)
    }
    @Test fun duplicateOperationsOrTrailingDocumentsFailClosed() {
        val p = patch().also { it.getJSONArray("add").put(it.getJSONArray("add").getJSONObject(0)) }
        assertThrows(Exception::class.java) { AgentTaskMemory().apply(p.toString(), setOf("s1"), 1) }
        assertThrows(Exception::class.java) { AgentTaskMemory().apply(patch().toString() + " {}", setOf("s1"), 1) }
    }
    @Test fun maskingPreservesCriticalEvidenceAndOnlyChangesConsumedToolObservations() {
        val repeated = "same observation\n".repeat(1000)
        assertTrue(AgentObservationMask.text(repeated).length < repeated.length / 10)
        val critical = "error 400 /data/path 10:20\n".repeat(500)
        assertEquals(critical, AgentObservationMask.text(critical))
        val raw = JSONArray().put(JSONObject().put("kind", "message").put("message_index", 0).put("value", JSONObject().put("role", "assistant").put("content", repeated)))
        assertEquals(repeated, JSONArray(AgentObservationMask.slice(raw.toString())).getJSONObject(0).getJSONObject("value").getString("content"))
        raw.getJSONObject(0).getJSONObject("value").put("role", "tool")
        assertEquals(repeated, JSONArray(AgentObservationMask.slice(raw.toString(), 0)).getJSONObject(0).getJSONObject("value").getString("content"))
        assertTrue(JSONArray(AgentObservationMask.slice(raw.toString(), 1)).getJSONObject(0).getJSONObject("value").getString("content").length < repeated.length)
    }
}
