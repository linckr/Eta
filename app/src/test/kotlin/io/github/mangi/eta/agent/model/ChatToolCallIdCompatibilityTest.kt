package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatToolCallIdCompatibilityTest {
    @Test
    fun duplicateIdsWithinOneAssistantWithOneResultRemainAmbiguousAndUnchanged() {
        val assistant = call("same-id")
        assistant.getJSONArray("tool_calls").put(call("same-id").getJSONArray("tool_calls").getJSONObject(0))
        val source = JSONArray().put(assistant).put(result("same-id"))
        assertEquals(source.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun duplicateCallWithResultAfterUserBoundaryIsLeftUnchanged() {
        assertCrossingPairUnchanged("user")
    }

    @Test
    fun duplicateCallWithResultAfterAssistantBoundaryIsLeftUnchanged() {
        assertCrossingPairUnchanged("assistant")
    }

    private fun assertCrossingPairUnchanged(boundaryRole: String) {
        val source = JSONArray().put(call("repeat")).put(result("repeat"))
            .put(call("repeat")).put(JSONObject().put("role", boundaryRole).put("content", "fixture"))
            .put(result("repeat"))
        assertEquals(source.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun repeatedRoundIdsAndOutputsRemainPairedWithoutChangingOriginalHistory() {
        val source = JSONArray().put(call("tool_call_0")).put(result("tool_call_0"))
            .put(call("tool_call_0")).put(result("tool_call_0"))
            .put(call("tool_call_0")).put(result("tool_call_0"))
        val original = source.toString()
        val output = OpenAiRequestMessages.forChatCompletions(source)
        val ids = mutableSetOf<String>()
        for (index in listOf(0, 2, 4)) {
            val id = output.getJSONObject(index).getJSONArray("tool_calls").getJSONObject(0).getString("id")
            assertTrue(ids.add(id))
            assertEquals(id, output.getJSONObject(index + 1).getString("tool_call_id"))
            assertEquals("fixture reasoning", output.getJSONObject(index).getString("reasoning_content"))
            assertEquals("{\"query\":\"fixture\"}", output.getJSONObject(index).getJSONArray("tool_calls")
                .getJSONObject(0).getJSONObject("function").getString("arguments"))
        }
        assertEquals(original, source.toString())
        assertEquals(output.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun generatedIdsAvoidAllOriginalIdsIncludingFutureAndOrphanRecords() {
        val source = JSONArray().put(call("reused")).put(result("reused"))
            .put(call("reused")).put(result("reused"))
            .put(call("eta_call_2_0")).put(result("eta_call_2_0"))
            .put(JSONObject().put("role", "user").put("content", "fixture"))
            .put(result("eta_call_2_0_1"))
        val output = OpenAiRequestMessages.forChatCompletions(source)
        val replacement = output.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertEquals("eta_call_2_0_2", replacement)
        assertEquals(replacement, output.getJSONObject(3).getString("tool_call_id"))
        assertEquals("eta_call_2_0", output.getJSONObject(4).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("eta_call_2_0_1", output.getJSONObject(7).getString("tool_call_id"))
    }

    @Test
    fun uniqueParallelCallsAndTheirResultOrderAreUnchanged() {
        val assistant = call("call-a")
        assistant.getJSONArray("tool_calls").put(call("call-b").getJSONArray("tool_calls").getJSONObject(0))
        val source = JSONArray().put(assistant).put(result("call-b")).put(result("call-a"))
        assertEquals(source.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun orphanResultIsNotPairedAcrossAUserBoundaryOrRemoved() {
        val source = JSONArray().put(call("repeat")).put(result("repeat"))
            .put(call("repeat")).put(result("repeat"))
            .put(JSONObject().put("role", "user").put("content", "fixture"))
            .put(result("repeat")).put(result("never-seen"))
        val output = OpenAiRequestMessages.forChatCompletions(source)
        assertEquals(source.length(), output.length())
        assertEquals("repeat", output.getJSONObject(5).getString("tool_call_id"))
        assertEquals("never-seen", output.getJSONObject(6).getString("tool_call_id"))
    }

    private fun call(id: String) = JSONObject().put("role", "assistant").put("content", "")
        .put("reasoning_content", "fixture reasoning")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "device_info").put("arguments", "{\"query\":\"fixture\"}"))))

    private fun result(id: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "fixture output")
}
