package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Transactional, lossless JSON slices. Only commit a cursor after a validated summary. */
internal class AgentSummarySlices(history: List<AgentModelClient.ConversationMessage>) {
    private val messages = history.map(AgentConversationCodec::toJsonObject)
    private val batches = buildList {
        var start = 0
        val open = linkedSetOf<String>()
        messages.forEachIndexed { index, message ->
            AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
            if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            if (open.isEmpty()) { add(start until index + 1); start = index + 1 }
        }
        if (start < messages.size) add(start until messages.size)
    }
    private data class Field(val path: List<Any>, val value: Any)
    private val fields = messages.map { message -> buildList { flatten(message, emptyList(), this) } }
    data class Cursor(val batch: Int = 0, val message: Int = -1, val field: Int = 0, val character: Int = 0)
    data class Slice(val json: String, val next: Cursor)
    fun done(cursor: Cursor) = cursor.batch >= batches.size
    fun completeMessages(cursor: Cursor): Int? = if (cursor.message == -1) batches.getOrNull(cursor.batch)?.first ?: messages.size else null
    fun messagePosition(cursor: Cursor): Int = if (cursor.message >= 0) cursor.message else batches.getOrNull(cursor.batch)?.first ?: messages.size
    fun accepts(cursor: Cursor): Boolean {
        if (cursor.batch == batches.size) return cursor.message == -1 && cursor.field == 0 && cursor.character == 0
        val batch = batches.getOrNull(cursor.batch) ?: return false
        if (cursor.message == -1) return cursor.field == 0 && cursor.character == 0
        if (cursor.message !in batch) return false
        val value = fields[cursor.message].getOrNull(cursor.field)?.value ?: return false
        return if (value is String) cursor.character in 0..value.length &&
            !(cursor.character > 0 && cursor.character < value.length && value[cursor.character - 1].isHighSurrogate() && value[cursor.character].isLowSurrogate())
        else cursor.character == 0
    }

    fun next(cursor: Cursor, limit: Int): Slice {
        var state = cursor
        val records = JSONArray()
        fun fits(record: JSONObject) = records.toString().length + record.toString().length + 2 <= limit
        while (!done(state)) {
            val batch = batches[state.batch]
            val index = if (state.message < 0) batch.first else state.message
            val base = JSONObject().put("batch_start", batch.first).put("batch_end", batch.last)
            val messageBase = JSONObject(base.toString()).put("role", messages[index].optString("role"))
                .put("tool_call_id", messages[index].optString("tool_call_id"))
            if (state.message < 0) {
                val whole = JSONObject(base.toString()).put("kind", "batch")
                    .put("messages", JSONArray(batch.map { messages[it] }))
                if (fits(whole)) { records.put(whole); state = Cursor(state.batch + 1); continue }
                // Keep a small complete batch for the next request rather than splitting it to fill this one.
                if (records.length() > 0 && whole.toString().length + 2 <= limit) break
            }
            val wholeMessage = JSONObject(messageBase.toString()).put("kind", "message")
                .put("message_index", index).put("batch_continuation", true).put("value", messages[index])
            fun afterMessage() = if (index == batch.last) Cursor(state.batch + 1)
                else Cursor(state.batch, index + 1)
            if (state.field == 0 && state.character == 0 && fits(wholeMessage)) {
                records.put(wholeMessage); state = afterMessage(); continue
            }
            if (records.length() > 0 && state.field == 0 && state.character == 0 && wholeMessage.toString().length + 2 <= limit) break
            val field = fields[index][state.field]
            fun record(value: Any, end: Int? = null) = JSONObject(messageBase.toString()).put("kind", "field")
                .put("message_index", index).put("path", JSONArray(field.path)).put("value", value)
                .put("batch_continuation", true).also {
                    if (end != null) it.put("start", state.character).put("end", end)
                        .put("length", (field.value as String).length).put("continuation", end < (field.value as String).length)
                }
            var end = 0
            val fragment = if (field.value is String) {
                val text = field.value
                var low = state.character
                var high = text.length
                // Escaping can expand control characters sixfold; measure the serialized record itself.
                while (low < high) {
                    val mid = low + (high - low + 1) / 2
                    if (fits(record(text.substring(state.character, mid), mid))) low = mid else high = mid - 1
                }
                end = low
                if (end < text.length && end > state.character && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                if (end == state.character && text.isNotEmpty()) {
                    if (records.length() > 0) break
                    error("Summary field metadata exceeds slice budget")
                }
                record(text.substring(state.character, end), end)
            } else record(field.value)
            if (!fits(fragment)) { if (records.length() > 0) break else error("Summary field metadata exceeds slice budget") }
            records.put(fragment)
            state = if (field.value is String && end < field.value.length) state.copy(message = index, character = end)
                else if (state.field + 1 < fields[index].size) state.copy(message = index, field = state.field + 1, character = 0)
                else afterMessage()
        }
        return Slice(records.toString(), state)
    }

    private fun flatten(value: Any, path: List<Any>, target: MutableList<Field>) {
        when (value) {
            is JSONObject -> if (value.length() == 0) target += Field(path, value) else value.keys().asSequence().sorted().forEach { flatten(value.get(it), path + it, target) }
            is JSONArray -> if (value.length() == 0) target += Field(path, value) else (0 until value.length()).forEach { flatten(value.get(it), path + it, target) }
            else -> target += Field(path, value)
        }
    }
}
