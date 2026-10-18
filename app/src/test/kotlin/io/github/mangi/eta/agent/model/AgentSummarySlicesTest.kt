package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummarySlicesTest {
    @Test fun ordinaryToolExchangeStaysInOneBatch() {
        val history = listOf(
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = "[{\"id\":\"call\",\"type\":\"function\",\"function\":{\"name\":\"fixture\",\"arguments\":\"{}\"}}]"),
            AgentModelClient.ConversationMessage("tool", "done", toolCallId = "call"),
            AgentModelClient.ConversationMessage("assistant", "confirmed"),
        )
        val slices = AgentSummarySlices(history)
        val result = slices.next(AgentSummarySlices.Cursor(), 4096)
        val records = JSONArray(result.json)
        assertEquals("batch", records.getJSONObject(0).getString("kind"))
        assertEquals(2, records.getJSONObject(0).getJSONArray("messages").length())
        assertTrue(slices.done(result.next))
    }

    @Test fun oversizeFieldsRemainLegalJsonAndReassembleEmojiControlCharactersAndNestedArguments() {
        val text = ("hello😀\n\t\u0000\\\"".repeat(5000))
        val history = listOf(AgentModelClient.ConversationMessage("assistant", text,
            toolCallsJson = JSONArray().put(JSONObject().put("id", "fixture").put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", text))).toString()))
        val slicer = AgentSummarySlices(history)
        var cursor = AgentSummarySlices.Cursor()
        val original = mutableMapOf<String, Any>()
        leaves(AgentConversationCodec.toJsonObject(history.single()), emptyList(), original)
        val restored = mutableMapOf<String, Any>()
        var pieces = 0
        while (!slicer.done(cursor)) {
            val result = slicer.next(cursor, if (pieces == 0) 4096 else 2048)
            assertTrue(result.json.length <= if (pieces == 0) 4096 else 2048)
            val records = JSONArray(result.json)
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                assertEquals("field", record.getString("kind"))
                assertEquals("assistant", record.getString("role"))
                val key = record.getJSONArray("path").toString()
                val value = record.get("value")
                if (value is String) {
                    val prior = restored[key] as? String ?: ""
                    assertEquals(prior.length, record.getInt("start"))
                    assertFalse(value.isNotEmpty() && value.last().isHighSurrogate())
                    assertFalse(value.isNotEmpty() && value.first().isLowSurrogate())
                    restored[key] = prior + value
                } else restored[key] = if (value is JSONObject || value is JSONArray) value.toString() else value
            }
            assertNotEquals(cursor, result.next)
            cursor = result.next
            pieces++
        }
        assertTrue(pieces > 3)
        assertEquals(original, restored)
    }

    @Test fun retryCanResliceUncommittedCursorWithoutRepeatingValidatedPrefix() {
        val slicer = AgentSummarySlices(listOf(AgentModelClient.ConversationMessage("assistant", "x".repeat(79_266))))
        val first = slicer.next(AgentSummarySlices.Cursor(), 32_000)
        val failed = slicer.next(first.next, 32_000)
        val recovery = slicer.next(first.next, 16_000)
        fun content(json: String) = (0 until JSONArray(json).length()).map { JSONArray(json).getJSONObject(it) }
            .first { it.getJSONArray("path").toString() == "[\"content\"]" }
        assertEquals(content(failed.json).getInt("start"), content(recovery.json).getInt("start"))
        assertEquals(content(first.json).getInt("end"), content(recovery.json).getInt("start"))
        assertTrue(content(failed.json).getString("value").startsWith(content(recovery.json).getString("value")))
    }

    private fun leaves(value: Any, path: List<Any>, target: MutableMap<String, Any>) {
        when (value) {
            is JSONObject -> if (value.length() == 0) target[JSONArray(path).toString()] = value.toString()
                else value.keys().forEach { leaves(value.get(it), path + it, target) }
            is JSONArray -> if (value.length() == 0) target[JSONArray(path).toString()] = value.toString()
                else (0 until value.length()).forEach { leaves(value.get(it), path + it, target) }
            else -> target[JSONArray(path).toString()] = value
        }
    }
}
