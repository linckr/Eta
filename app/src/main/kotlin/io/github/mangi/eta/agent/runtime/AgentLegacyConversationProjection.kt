package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅为不认识完整载荷字段的旧客户端提供内联预览；禁止用于存储或新模型请求。 */
internal object AgentLegacyConversationProjection {
    const val DIRECT_CHARS = 96_000
    const val DRAIN_CHARS = 16_000
    private val json = Json { encodeDefaults = false }
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    fun encode(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(messages),
        )
        val encoded = json.encodeToString(bounded)
        if (encoded.length <= maxChars) return encoded

        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        // Legacy preview only: encoding every successively shorter suffix was quadratic
        // and blocked the full FD-backed request for tens of seconds on long conversations.
        // Encode each item once; exact UTF-16 lengths preserve the previous cutoff behavior.
        val items = bounded.map { json.encodeToString(it) }
        val suffixChars = LongArray(items.size + 1)
        for (index in items.indices.reversed()) suffixChars[index] = suffixChars[index + 1] + items[index].length
        val noticeJson = json.encodeToString(notice)
        var start = 0
        while (bounded.size - start > 1) {
            start++
            while (start < bounded.size && bounded[start].role == "tool") start++
            val remaining = bounded.size - start
            val length = 2L + noticeJson.length + suffixChars[start] + remaining
            if (length <= maxChars) return buildString(length.toInt()) {
                append('[').append(noticeJson)
                for (index in start until items.size) append(',').append(items[index])
                append(']')
            }
        }

        val last = bounded.getOrNull(start) ?: return "[]"
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

}
