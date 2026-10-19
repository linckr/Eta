package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Detect protocol leakage, never turn assistant text into executable tool calls. */
internal object AgentToolProtocolGuard {
    private val completeDsmlCall = Regex(
        """(?m)^\s*<\s*[|｜]\s*DSML\s*[|｜]\s*(tool_calls|function_calls)\s*>[\s\S]*?<\s*[|｜]\s*DSML\s*[|｜]\s*invoke\b[^>]*>[\s\S]*?<\s*/\s*[|｜]\s*DSML\s*[|｜]\s*invoke\s*>[\s\S]*?<\s*/\s*[|｜]\s*DSML\s*[|｜]\s*\1\s*>""",
    )

    fun validate(response: ProviderResponse) {
        if (AgentConversationCodec.parseToolCalls(response.assistantMessage).isNotEmpty()) return
        if (!containsLeak(response.assistantMessage.optString("content"))) return
        throw AgentModelFailure("MODEL_TOOL_PROTOCOL_LEAK", false,
            "模型将工具调用协议标记输出为正文，未返回有效工具调用；正文中的命令不会执行。")
    }

    fun containsLeak(content: String) = completeDsmlCall.containsMatchIn(outsideQuotedCode(content))

    /** Temporary request policy, never a history edit or a conversion of prose to commands. */
    fun recoveryRequest(request: ProviderRequest): ProviderRequest {
        if (!request.purpose.allowsTools || request.tools.length() == 0) return request
        val messages = JSONArray(request.messages.toString())
        val first = messages.optJSONObject(0)
        if (first?.optString("role") == "system" && first.opt("content") is String) {
            if (first.getString("content").contains(RECOVERY_POLICY)) return request
            first.put("content", first.getString("content") + "\n\n" + RECOVERY_POLICY)
        } else {
            val prefix = JSONObject().put("role", "system").put("content", RECOVERY_POLICY)
            val next = JSONArray().put(prefix)
            for (i in 0 until messages.length()) next.put(messages.get(i))
            return checkedRecovery(request, next)
        }
        return checkedRecovery(request, messages)
    }

    private fun checkedRecovery(request: ProviderRequest, messages: JSONArray): ProviderRequest {
        val candidate = request.copy(messages = messages)
        val fits = runCatching {
            val budget = AgentRequestBudget.evaluate(candidate.config, messages, candidate.tools, candidate.purpose)
            val window = AgentContextBudget.requirePositiveBudget(candidate.config, candidate.purpose)
            val bytes = AgentRequestBytePolicy.resolve(candidate.config, window)
            (window == null || budget.estimatedTokens < window) && (bytes <= 0 || budget.requestBytes <= bytes)
        }.getOrDefault(false)
        return if (fits) candidate else request
    }

    private const val RECOVERY_POLICY = "工具调用格式恢复约束：需要调用工具时，只能通过本次提供的工具定义返回结构化函数调用；不要把 DSML、tool_calls、invoke 或工具参数写成正文中的协议标签，也不要用代码块代替调用。此前已完成的工具结果有效，不要重复执行。若无需工具，直接回答；不要为了满足格式而虚构调用。"

    private fun outsideQuotedCode(content: String): String {
        var fence: Char? = null
        var fenceLength = 0
        return content.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val marker = trimmed.firstOrNull()
            val run = if (marker == '`' || marker == '~') trimmed.takeWhile { it == marker }.length else 0
            when {
                fence != null -> {
                    if (marker == fence && run >= fenceLength && trimmed.drop(run).isBlank()) fence = null
                    ""
                }
                run >= 3 -> { fence = marker; fenceLength = run; "" }
                trimmed.startsWith(">") || line.startsWith("    ") || line.startsWith("\t") -> ""
                else -> line
            }
        }
    }
}
