package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AgentNativeCompactionTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://api.openai.com/v1", apiKey = "fixture",
        model = "gpt-5.3-codex", systemPrompt = "fixture", contextWindow = 400_000, openAiEndpointMode = OpenAiEndpointMode.RESPONSES)
    private fun body() = JSONObject().put("context_management", JSONArray().put(JSONObject().put("type", "compaction").put("compact_threshold", 200_000)))
    @Test fun optInRequiresOfficialHostDocumentedModelAndKnownCapacity() {
        assertFalse(AgentNativeCompaction.validate(config, JSONObject(), EndpointKind.RESPONSES))
        assertTrue(AgentNativeCompaction.validate(config, body(), EndpointKind.RESPONSES))
        listOf(config.copy(baseUrl = "https://workbuddy.invalid/v1"), config.copy(model = "space-bunny"),
            config.copy(baseUrl = "https://api.openai.com.evil.invalid/v1"), config.copy(contextWindow = null), config.copy(contextWindow = 128_000)).forEach {
            assertThrows(AgentModelFailure::class.java) { AgentNativeCompaction.validate(it, body(), EndpointKind.RESPONSES) }
        }
        assertThrows(AgentModelFailure::class.java) { AgentNativeCompaction.validate(config, body(), EndpointKind.CHAT_COMPLETIONS) }
    }
    @Test fun opaqueResponsesItemsAreForwardedAsCanonicalItems() {
        val message = JSONObject().put("role", "assistant").put("content", "result")
        val items = JSONArray().put(JSONObject().put("type", "compaction").put("encrypted_content", "opaque-fixture"))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "result"))))
        ResponsesEphemeralState.attachOutputItems(message, items)
        val body = ResponsesRequestBuilder.build(config, JSONArray().put(message), JSONArray())
        assertEquals(items.toString(), body.getJSONArray("input").toString())
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(AgentConversationCodec.transcript(JSONArray().put(message), 0)).contains("opaque-fixture"))
    }
    @Test fun anthropicNativePolicyRejectsPauseAndUnknownModels() {
        val cfg = config.copy(baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-6", contextWindow = 200_000)
        val edit = JSONObject().put("type", "compact_20260112")
        val body = JSONObject().put("context_management", JSONObject().put("edits", JSONArray().put(edit)))
        assertTrue(AgentNativeCompaction.validate(cfg, body, EndpointKind.ANTHROPIC_MESSAGES))
        edit.put("pause_after_compaction", true)
        assertThrows(AgentModelFailure::class.java) { AgentNativeCompaction.validate(cfg, body, EndpointKind.ANTHROPIC_MESSAGES) }
        edit.remove("pause_after_compaction")
        assertThrows(AgentModelFailure::class.java) { AgentNativeCompaction.validate(cfg.copy(model = "unknown"), body, EndpointKind.ANTHROPIC_MESSAGES) }
    }
    @Test fun anthropicStreamingCompactionSurvivesNextTurnWithoutVisibleOrDurableLeak() {
        val events = listOf(
            JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", JSONObject().put("type", "compaction").put("content", "")),
            JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", JSONObject().put("type", "compaction_delta").put("content", "native summary fixture")),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "content_block_start").put("index", 1).put("content_block", JSONObject().put("type", "text").put("text", "回答")),
            JSONObject().put("type", "content_block_stop").put("index", 1),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")),
            JSONObject().put("type", "message_stop"),
        )
        val stream = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
        val parser = AnthropicMessagesProvider.javaClass.declaredMethods.single { it.name == "readStreamingAssistantMessage" }
        parser.isAccessible = true
        val message = parser.invoke(AnthropicMessagesProvider, ByteArrayInputStream(stream.toByteArray()), AgentRunController(), { _: ProviderEvent -> }) as JSONObject
        assertEquals("回答", message.getString("content"))
        assertTrue(AgentNativeCompaction.hasCompaction(message))
        assertFalse(message.has("tool_calls"))
        val next = AnthropicMessagesProvider.buildRequestJson(config, JSONArray().put(message).put(AgentConversationCodec.userTextMessage("下一轮")), JSONArray())
        assertTrue(next.toString().contains("native summary fixture"))
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(AgentConversationCodec.transcript(JSONArray().put(message), 0)).contains("native summary fixture"))
    }
}
