package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiReasoningHistoryProjectionTest {
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "unknown",
        systemPrompt = "", providerSourceType = "custom")
    private fun history() = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "previous"))
        .put(JSONObject().put("role", "assistant").put("content", "previous answer").put("reasoning_content", "old reasoning"))
        .put(JSONObject().put("role", "user").put("content", "current"))
        .put(JSONObject().put("role", "assistant").put("content", "checking").put("reasoning_content", "tool reasoning")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1").put("type", "function").put("function",
                JSONObject().put("name", "fixture").put("arguments", "{\"reasoning_content\":\"keep argument\"}")))))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "completed"))
    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "fixture").put("parameters", JSONObject().put("type", "object"))))

    @Test fun finalExplicitOffDropsOnlyAssistantReasoningAndNeverChangesDurableHistoryOrTools() {
        val source = history()
        val original = source.toString()
        val config = config().copy(customBody = listOf(CustomBody("reasoning_effort", JsonPrimitive("none"))))
        val projected = AgentProviderRequestJson.build(ProviderRequest(config, source, tools)).getJSONArray("messages")
        assertFalse(projected.getJSONObject(1).has("reasoning_content"))
        assertFalse(projected.getJSONObject(3).has("reasoning_content"))
        assertEquals("call-1", projected.getJSONObject(4).getString("tool_call_id"))
        assertEquals("{\"reasoning_content\":\"keep argument\"}", projected.getJSONObject(3).getJSONArray("tool_calls")
            .getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals(original, source.toString())
    }

    @Test fun deepSeekThinkingToolsPreservesAllPriorTurnsAndOffToOnRestoresFromDurableHistory() {
        val source = history()
        val original = source.toString()
        val base = config().copy(providerSourceType = "deepseek", model = "deepseek-flash", reasoningEffort = ReasoningEffort.HIGH)
        val on = AgentProviderRequestJson.build(ProviderRequest(base, source, tools)).getJSONArray("messages")
        assertEquals("old reasoning", on.getJSONObject(1).getString("reasoning_content"))
        assertEquals("tool reasoning", on.getJSONObject(3).getString("reasoning_content"))
        val off = AgentProviderRequestJson.build(ProviderRequest(base.copy(reasoningEffort = ReasoningEffort.OFF), source, tools)).getJSONArray("messages")
        assertFalse(off.getJSONObject(1).has("reasoning_content"))
        val backOn = AgentProviderRequestJson.build(ProviderRequest(base, source, tools)).getJSONArray("messages")
        assertEquals(on.toString(), backOn.toString())
        assertEquals(original, source.toString())
        val noTools = AgentProviderRequestJson.build(ProviderRequest(base.copy(reasoningEffort = ReasoningEffort.DEFAULT), source, tools,
            purpose = ProviderRequestPurpose.COMPACTION))
        assertFalse(noTools.has("tools"))
        assertFalse(noTools.getJSONArray("messages").getJSONObject(1).has("reasoning_content"))
    }

    @Test fun customReenableUnknownThinkingAndAmbiguousTargetsRemainConservative() {
        val source = history()
        val reenabled = config().copy(reasoningEffort = ReasoningEffort.OFF, extraBodyJson = """{"thinking":{"type":"disabled"}}""",
            customBody = listOf(CustomBody("thinking", buildJsonObject { put("type", "enabled") })))
        val request = AgentProviderRequestJson.build(ProviderRequest(reenabled, source, tools))
        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("old reasoning", request.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
        for (config in listOf(config(), config().copy(model = "dsv4-flash"), config().copy(reasoningEffort = ReasoningEffort.HIGH,
            reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.HIGH), defaultEnabled = true)))) {
            assertEquals("tool reasoning", AgentProviderRequestJson.build(ProviderRequest(config, source, tools))
                .getJSONArray("messages").getJSONObject(3).getString("reasoning_content"))
        }
    }

    @Test fun kimiKeepAllIsPreservedAndCapabilityDoesNotMeanHistoryCanBeDiscarded() {
        val config = config().copy(providerSourceType = "moonshot", model = "kimi-k2.6", reasoningEffort = ReasoningEffort.DEFAULT)
        val request = AgentProviderRequestJson.build(ProviderRequest(config, history(), tools))
        assertEquals("all", request.getJSONObject("thinking").getString("keep"))
        assertEquals("old reasoning", request.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
    }
}
