package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Conservative request estimate, not a substitute for provider-reported usage. */
internal object AgentContextBudget {
    /** Only a documented total window shares its budget with requested generation. */
    fun inputBudget(config: AgentModelClient.ModelConfig, purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT): Int? {
        val window = config.knownContextWindow
        val inputLimit = config.inputTokenLimit?.takeIf { it > 0 }
        val usableWindow = if (window != null && config.contextWindowKind == "total") {
            (window.toLong() - (AgentOutputLimit.effective(config, purpose) ?: 0).toLong()).coerceAtLeast(0).toInt()
        } else window
        return listOfNotNull(usableWindow, inputLimit).minOrNull()
    }

    fun requirePositiveBudget(config: AgentModelClient.ModelConfig, purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT): Int? =
        inputBudget(config, purpose)?.also { budget ->
            if (budget <= 0) throw AgentModelFailure("CONTEXT_OUTPUT_BUDGET_INVALID", false,
                "请求的输出上限已占满模型总窗口，无法容纳输入。请降低输出上限或使用更大窗口的模型；原始上下文已保留。",
                contextRecoveryAllowed = false)
        }

    fun estimate(messages: JSONArray, tools: JSONArray): Int {
        fun size(value: Any?): Long = when (value) {
            is JSONObject -> value.keys().asSequence().sumOf { key -> key.length.toLong() + size(value.opt(key)) + 8 }
            is JSONArray -> (0 until value.length()).sumOf { size(value.opt(it)) + 4 }
            is String -> if (value.startsWith("data:image/")) 24_576L else value.toByteArray(Charsets.UTF_8).size.toLong()
            else -> 8L
        }
        return ((size(messages) + size(tools) + 2) / 3 + 256).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
