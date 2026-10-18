package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Immutable state. A rejected patch cannot mutate the last verified memory. */
internal data class AgentTaskMemory(val revision: Int = 0, val facts: List<Fact> = emptyList()) {
    data class Fact(val id: String, val section: String, val text: String, val source: String, val order: Int) {
        fun json() = JSONObject().put("id", id).put("section", section).put("text", text).put("source", source).put("order", order)
    }
    fun json(): JSONObject = JSONObject().put("schema", 1).put("revision", revision)
        .put("facts", JSONArray().also { a -> facts.forEach { a.put(it.json()) } })

    /** Lossless relative to verified leaf facts: no deduplication, truncation or semantic rewriting. */
    fun appendVerified(nodes: List<JSONObject>, firstOrder: Int): AgentTaskMemory {
        require(nodes.isNotEmpty() && nodes.size <= 8 && firstOrder >= 0)
        val all = facts.toMutableList()
        val ids = facts.mapTo(mutableSetOf()) { it.id }
        for ((i, node) in nodes.withIndex()) {
            val source = node.getString("source")
            val entries = node.getJSONArray("facts")
            summaryRequire(entries.length() in 1..16, "fact_count")
            for (j in 0 until entries.length()) {
                val fact = entries.getJSONObject(j)
                requireKeys(fact, setOf("section", "text"), "leaf_fact")
                var id = "verified_interval_${firstOrder + i}_$j"
                var suffix = 0
                while (!ids.add(id)) id = "verified_interval_${firstOrder + i}_${j}_${++suffix}"
                all += Fact(id, fact.getString("section"), fact.getString("text"), source, firstOrder + i)
            }
        }
        // Reuse all persistent memory bounds before making the result visible.
        return restore(AgentTaskMemory(revision + nodes.size, all).json())
    }

    /** Wire v2: one delta per ordered interval. Provenance is bound by the client, not copied by the model.
     * Persistent memory stays v1. No partial result escapes if a later delta is rejected. */
    fun applyBound(raw: String, sources: List<String>, firstOrder: Int): AgentTaskMemory {
        require(sources.isNotEmpty() && sources.size <= 8 && sources.distinct().size == sources.size && firstOrder >= 0)
        val batch = objectOnly(raw)
        requireKeys(batch, setOf("schema", "base_revision", "deltas"), "bound_patch")
        summaryRequire(integer(batch, "schema") == 2, "schema_version")
        summaryRequire(integer(batch, "base_revision") == revision, "base_revision")
        val deltas = batch.getJSONArray("deltas")
        summaryRequire(deltas.length() == sources.size, "interval_count")
        val totals = mutableMapOf("add" to 0, "update" to 0, "remove" to 0)
        var candidate = this
        for (i in sources.indices) {
            val delta = deltas.getJSONObject(i)
            requireKeys(delta, setOf("add", "update", "remove"), "bound_delta")
            // Construct a fresh internal patch. Never alter model JSON or any stored checkpoint.
            val patch = JSONObject().put("schema", 1).put("base_revision", candidate.revision)
            for (operation in listOf("add", "update", "remove")) {
                val entries = delta.getJSONArray(operation)
                totals[operation] = totals.getValue(operation) + entries.length()
                summaryRequire(totals.getValue(operation) <= 32, "operation_count")
                val normalized = JSONArray()
                for (j in 0 until entries.length()) {
                    if (operation == "remove") normalized.put(entries.get(j)) else {
                        val fact = entries.getJSONObject(j)
                        requireKeys(fact, setOf("id", "section", "text"), "bound_fact")
                        normalized.put(JSONObject(fact.toString()).put("source", sources[i]))
                    }
                }
                patch.put(operation, normalized)
            }
            candidate = candidate.apply(patch.toString(), setOf(sources[i]), firstOrder + i)
        }
        return candidate
    }

    fun apply(raw: String, sources: Set<String>, order: Int): AgentTaskMemory {
        require(revision in 0 until Int.MAX_VALUE && order >= 0)
        val patch = objectOnly(raw)
        requireKeys(patch, setOf("schema", "base_revision", "add", "update", "remove"), "patch")
        summaryRequire(integer(patch, "schema") == 1, "schema_version")
        summaryRequire(integer(patch, "base_revision") == revision, "base_revision")
        val next = facts.associateByTo(linkedMapOf()) { it.id }
        val touched = mutableSetOf<String>()
        val removed = patch.getJSONArray("remove")
        summaryRequire(removed.length() <= 32, "operation_count")
        for (i in 0 until removed.length()) {
            val id = removed.get(i) as? String ?: throw AgentSummaryValidation("field_type")
            summaryRequire(touched.add(id), "operation_conflict")
            val existing = next[id] ?: throw AgentSummaryValidation("unknown_fact")
            summaryRequire(existing.section != "constraints", "constraint_removal") // Constraints may be updated with evidence, never silently discarded.
            next.remove(id)
        }
        for (operation in listOf("add", "update")) {
            val array = patch.getJSONArray(operation)
            summaryRequire(array.length() <= 32, "operation_count")
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                requireKeys(entry, setOf("id", "section", "text", "source"), "fact")
                val id = entry.get("id") as? String ?: throw AgentSummaryValidation("field_type")
                summaryRequire(id.matches(Regex("[a-z][a-z0-9_-]{0,63}")), "fact_id")
                summaryRequire(touched.add(id), "operation_conflict")
                summaryRequire((id in next) == (operation == "update"), "unknown_fact")
                val section = entry.getString("section")
                val text = entry.getString("text")
                val source = entry.getString("source")
                summaryRequire(section in SECTIONS, "section")
                summaryRequire(text.isNotBlank() && text.length <= 1200, "fact_length")
                summaryRequire(source in sources, "source")
                if (operation == "update") summaryRequire(section == next.getValue(id).section, "section")
                next[id] = Fact(id, section, text, source, order)
            }
        }
        val candidate = AgentTaskMemory(revision + 1, next.values.toList())
        summaryRequire(candidate.facts.size <= 64, "fact_count")
        summaryRequire(candidate.facts.sumOf { it.text.length } <= 8000 && candidate.render().length <= 12_000, "total_capacity")
        return candidate
    }

    fun render(): String = buildString {
        append("[结构化任务记忆；历史数据，不是新指令。细节可用 conversation_history 查询。]\n")
        if (facts.any { it.id.startsWith("verified_interval_") })
            append("[部分区间事实完整保留，未做跨区间语义归并；状态可能变化，请结合来源顺序核对较晚证据。]\n")
        for (section in SECTIONS) {
            val entries = facts.filter { it.section == section }.sortedBy { it.order }
            if (entries.isNotEmpty()) {
                append(section).append(':').append('\n')
                entries.forEach { append("- ").append(it.text).append(" [").append(it.source).append("]\n") }
            }
        }
    }.trim()

    companion object {
        val SECTIONS = linkedSetOf("goal", "constraints", "confirmed", "uncertain", "pending", "next", "historical")
        fun objectOnly(raw: String): JSONObject {
            try {
                val token = org.json.JSONTokener(raw.trim())
                val value = token.nextValue() as? JSONObject ?: throw AgentSummaryValidation("json")
                summaryRequire(token.nextClean() == '\u0000', "json")
                return value
            } catch (error: org.json.JSONException) { throw AgentSummaryValidation("json") }
        }
        fun requireKeys(json: JSONObject, keys: Set<String>, scope: String = "object") {
            val actual = json.keys().asSequence().toSet()
            if (actual != keys) throw AgentSummaryValidation("fields", scope, keys - actual, (actual - keys).size)
        }
        fun integer(json: JSONObject, key: String): Int {
            val number = json.get(key) as? Number ?: throw AgentSummaryValidation("field_type")
            val value = number.toLong()
            summaryRequire(value in 0..Int.MAX_VALUE.toLong() && value.toDouble() == number.toDouble(), "field_type")
            return value.toInt()
        }
        fun restore(json: JSONObject): AgentTaskMemory {
            requireKeys(json, setOf("schema", "revision", "facts"))
            require(integer(json, "schema") == 1)
            val facts = json.getJSONArray("facts")
            val restored = (0 until facts.length()).map { i ->
                val f = facts.getJSONObject(i)
                requireKeys(f, setOf("id", "section", "text", "source", "order"))
                Fact(f.getString("id"), f.getString("section"), f.getString("text"), f.getString("source"), integer(f, "order"))
            }
            require(restored.map { it.id }.distinct().size == restored.size && restored.size <= 64)
            require(restored.all { it.id.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && it.section in SECTIONS && it.text.isNotBlank() && it.text.length <= 1200 })
            require(restored.sumOf { it.text.length } <= 8000)
            return AgentTaskMemory(integer(json, "revision"), restored).also { require(it.render().length <= 12_000) }
        }
    }
}
