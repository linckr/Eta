package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Deterministic masking of consumed old observations. Never touch tool arguments or the Journal. */
internal object AgentObservationMask {
    private val critical = Regex("(?i)error|exception|warning|failed|timeout|\\bat\\b|[/\\\\]|\\d{2}:\\d{2}|0x[0-9a-f]+")
    fun text(raw: String): String {
        if (raw.length < 8192) return raw
        val lines = raw.split('\n')
        val result = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            var end = i + 1
            while (end < lines.size && lines[end] == lines[i]) end++
            result += lines[i]
            if (end - i >= 3 && !critical.containsMatchIn(lines[i])) {
                result += "[同一行重复 ${end - i} 次；原文保留在当前会话 Journal]"
            } else repeat(end - i - 1) { result += lines[i] }
            i = end
        }
        val masked = result.joinToString("\n")
        return if (masked.length < raw.length) masked else raw
    }
    fun slice(raw: String, consumedBeforeIndex: Int = Int.MAX_VALUE): String {
        val records = JSONArray(raw)
        fun maskMessage(m: JSONObject, index: Int) {
            if (index < consumedBeforeIndex && m.optString("role") == "tool" && m.opt("content") is String) m.put("content", text(m.getString("content")))
        }
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            when (record.optString("kind")) {
                "batch" -> record.getJSONArray("messages").let { a -> for (j in 0 until a.length()) maskMessage(a.getJSONObject(j), record.getInt("batch_start") + j) }
                "message" -> maskMessage(record.getJSONObject("value"), record.getInt("message_index"))
                "field" -> if (record.getInt("message_index") < consumedBeforeIndex && record.optString("role") == "tool" && record.optJSONArray("path")?.optString(0) == "content" && record.opt("value") is String)
                    record.put("value", text(record.getString("value")))
            }
        }
        return records.toString()
    }
}
