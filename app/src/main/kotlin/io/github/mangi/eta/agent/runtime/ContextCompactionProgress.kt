package io.github.mangi.eta.agent.runtime

import org.json.JSONObject

/** Bounded progress metadata in the existing notice column; no source text or model output. */
internal object ContextCompactionProgress {
    fun encode(completedSlices: Int?, totalSlices: Int?, elapsedMs: Long?, processedPercent: Int?, activity: ContextCompactionActivity? = null): String? {
        val json = JSONObject()
        completedSlices?.takeIf { it >= 0 }?.let { json.put("completed_slices", it) }
        totalSlices?.takeIf { it > 0 && (completedSlices == null || it >= completedSlices) }?.let { json.put("total_slices", it) }
        elapsedMs?.takeIf { it >= 0 }?.let { json.put("elapsed_ms", it) }
        processedPercent?.takeIf { it in 0..100 }?.let { json.put("processed_percent", it) }
        activity?.let { ContextCompactionActivity.decode(it.encode()) }?.let { json.put("activity", it.encode()) }
        return json.takeIf { it.length() > 0 }?.toString()
    }

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 1024) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        fun int(key: String): Int? = (json.opt(key) as? Number)?.let {
            val value = it.toLong()
            value.takeIf { v -> v in 0..Int.MAX_VALUE.toLong() && it.toDouble() == v.toDouble() }?.toInt()
        }
        val elapsed = (json.opt("elapsed_ms") as? Number)?.let {
            it.toLong().takeIf { value -> value >= 0 && it.toDouble() == value.toDouble() }
        }
        return encode(int("completed_slices"), int("total_slices"), elapsed, int("processed_percent"),
            ContextCompactionActivity.decode(json.optJSONObject("activity")))
    }
}
