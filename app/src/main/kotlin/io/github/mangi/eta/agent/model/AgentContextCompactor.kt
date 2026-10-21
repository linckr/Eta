package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    private val onEvent: (AgentEvent) -> Unit = {},
    private val providerResolver: (AgentModelClient.ModelConfig) -> AgentProviderClient = ProviderClientFactory::getClient,
    private val support: AgentCompactionSupport = AgentCompactionSupport(),
    private val onProgress: (completedSlices: Int, resumed: Boolean) -> Unit = { _, _ -> },
    private val onActivity: (io.github.mangi.eta.agent.runtime.ContextCompactionActivity) -> Unit = {},
    private val candidateVerdict: (JSONArray) -> AgentCandidateVerdict = { AgentCandidateVerdict.reject() },
) {
    private data class RetainedCandidate(val candidate: JSONArray, val requestBytes: Long)
    private var completedSummarizer: AgentContextSummarizer? = null
    fun clearCompletedCheckpoint() { runCatching { completedSummarizer?.clearCompletedCheckpoint() } }
    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): JSONArray {
        controller.throwIfCancelled()
        onActivity(io.github.mangi.eta.agent.runtime.ContextCompactionActivity("preparing"))
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            throw signedAnthropicToolRoundFailure()
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
        val end = (history.size downTo 1).firstOrNull { canSplit(history, it) }
            ?: throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val sourceMessages = history.take(end).filterNot { it === protectedUser }
        val source = JSONArray(sourceMessages)
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可压缩的历史内容。")
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[图片观察已省略]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }
        val summarizer = AgentContextSummarizer(config, provider, controller, roleplay, onEvent, providerResolver, support, onProgress, onActivity)
        var retained: RetainedCandidate? = null
        val summary = summarizer.summarize(safe) { text, count ->
            // Only complete tool boundaries can form an early candidate. Index mapping is
            // deliberately checked: a codec that removes records must not shorten coverage.
            if (safe.size != sourceMessages.size || count <= 0) false else {
                val originalEnd = history.indexOf(sourceMessages[count - 1]) + 1
                if (!canSplit(history, originalEnd)) false else {
                    val candidate = buildCandidate(messages, systemCount, history, safe, text,
                        count, originalEnd, protectedUser)
                    if (candidate.toString().length >= messages.toString().length) false else {
                        val verdict = candidateVerdict(candidate)
                        when (verdict.action) {
                            AgentCandidateAction.TAKE_AND_STOP -> true
                            AgentCandidateAction.RETAIN_AND_CONTINUE -> {
                                // Keep the smallest valid budget-passing candidate while the
                                // summarizer continues toward the headroom target. It is only
                                // used after normal completion, never to hide a summary error.
                                if (retained == null || verdict.requestBytes < retained!!.requestBytes) {
                                    retained = RetainedCandidate(candidate, verdict.requestBytes)
                                }
                                false
                            }
                            AgentCandidateAction.REJECT -> false
                        }
                    }
                }
            }
        }
        completedSummarizer = summarizer
        val count = summarizer.coveredMessages
        val actualEnd = if (count == safe.size) end else history.indexOf(sourceMessages[count - 1]) + 1
        val result = buildCandidate(messages, systemCount, history, safe, summary, count, actualEnd, protectedUser)
        if (result.toString().length >= messages.toString().length) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        // Do not let the final candidate silently exceed the budget when a valid retained
        // candidate was already built. Never use it to mask a failure; this is the normal end.
        if (candidateVerdict(result).action == AgentCandidateAction.REJECT) {
            retained?.let { return it.candidate }
        }
        return result
    }

    private fun buildCandidate(messages: JSONArray, systemCount: Int, history: List<JSONObject>,
        safe: List<AgentModelClient.ConversationMessage>, summary: String, count: Int, end: Int, protectedUser: JSONObject?): JSONArray {
        val covered = safe.take(count).sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val userCovered = protectedUser?.takeIf { history.indexOf(it) < end }
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n$summary",
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = covered + if (userCovered != null) 1 else 0,
        )))
        userCovered?.let(result::put)
        history.drop(end).forEach { message ->
            // 新摘要改变了前缀；旧 opaque items 不再代表同一份 Provider 上下文。
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems))
        }
        return result
    }

    companion object {
        fun signedAnthropicToolRoundFailure() = failure(
            "ANTHROPIC_THINKING_CONTEXT_LOCKED",
            "当前 Anthropic 工具回合的思考签名绑定原始上下文，提交工具结果前无法压缩上下文。",
        )

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
