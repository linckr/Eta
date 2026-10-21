package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentLegacyConversationProjectionTest {
    private val json = Json { encodeDefaults = false }
    private val notice = ConversationMessage("system", "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]")

    /** Frozen reference behavior, including tool-only suffixes and single-message fallback. */
    private fun reference(messages: List<ConversationMessage>, limit: Int): String {
        val bounded = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(messages)).toMutableList()
        val all = json.encodeToString(bounded)
        if (all.length <= limit) return all
        while (bounded.size > 1) {
            bounded.removeAt(0)
            while (bounded.firstOrNull()?.role == "tool") bounded.removeAt(0)
            val encoded = json.encodeToString(listOf(notice) + bounded)
            if (encoded.length <= limit) return encoded
        }
        val last = bounded.lastOrNull() ?: return "[]"
        val compacted = last.copy(content = last.content.take(limit / 4), contentJson = "",
            reasoningContent = last.reasoningContent.take(limit / 4), toolCallsJson = "")
        return json.encodeToString(listOf(notice, compacted)).takeIf { it.length <= limit }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= limit } ?: "[]"
    }

    @Test fun linearPreviewExactlyMatchesLegacyCutoffsAndLeavesSourceIntact() {
        val cases = listOf(emptyList(), listOf(ConversationMessage("tool", "x".repeat(4000))),
            listOf(ConversationMessage("user", "x".repeat(4000)))) + (1..35).map { size ->
            (0 until size).map { i -> ConversationMessage(
                role = listOf("user", "assistant", "tool", "tool")[i % 4],
                content = "消息😀$i\n\"\\".repeat(i * 3 + 1),
                toolCallId = "call-$i", reasoningContent = "思考".repeat(i),
                contentJson = if (i % 6 == 0) "[{\"type\":\"image_url\",\"image_url\":\"private-image\"}]" else "",
            ) }
        }
        for (messages in cases) {
            val original = json.encodeToString(messages)
            for (limit in listOf(2, 90, 300, 1000, 6000, 16000, 96000)) {
                assertEquals("size=${messages.size}, limit=$limit", reference(messages, limit), AgentLegacyConversationProjection.encode(messages, limit))
            }
            assertEquals(original, json.encodeToString(messages))
        }
    }

    @Test fun largeHistoryKeepsFullDurableContentAndProducesOnlyBoundedLegacyPreview() {
        val source = (0 until 1800).map { ConversationMessage("assistant", "fixture-$it " + "字".repeat(1000), messageId = "m$it") }
        val original = AgentConversationCodec.encodeTranscriptForStorage(source)
        val started = System.nanoTime()
        val preview = AgentLegacyConversationProjection.encode(source, AgentLegacyConversationProjection.DIRECT_CHARS)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        println("legacy_preview_synthetic messages=1800 elapsed_ms=$elapsed preview_chars=${preview.length}")
        assertTrue(preview.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertEquals(source.last(), AgentConversationCodec.decodeTranscript(preview).last())
        assertEquals(original, AgentConversationCodec.encodeTranscriptForStorage(source))
        assertEquals(1800, AgentConversationCodec.decodeTranscript(original).size)
    }
}
