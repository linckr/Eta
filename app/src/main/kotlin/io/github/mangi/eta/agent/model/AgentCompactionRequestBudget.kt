package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Measure the actual provider JSON, including instructions and the old summary. */
internal object AgentCompactionRequestBudget {
    data class Evaluation(val request: AgentRequestBudget.Budget, val inputBudget: Int?,
        val byteBudget: Int, val outputReserve: Int, val fits: Boolean)
    fun evaluate(config: AgentModelClient.ModelConfig, messages: JSONArray): Evaluation {
        val purpose = ProviderRequestPurpose.COMPACTION
        val window = config.knownContextWindow
        val plannedOutput = window?.let { minOf(8192, maxOf(256, it / 4)) } ?: 8192
        val output = maxOf(plannedOutput, AgentOutputLimit.effective(config, purpose) ?: 0)
        val usable = if (window != null && config.contextWindowKind == "total")
            (window.toLong() - output).coerceAtLeast(0).toInt() else window
        val input = listOfNotNull(usable, config.inputTokenLimit?.takeIf { it > 0 }).minOrNull()
        val bytes = AgentRequestBytePolicy.resolve(config, input)
        val request = AgentRequestBudget.evaluate(config, messages, JSONArray(), purpose)
        val byteFits = if (config.compactionPolicyVersion >= 4)
            AgentRequestSendPolicy.allowsOriginal(config, request.requestBytes)
        else bytes == 0 || request.requestBytes <= bytes.toLong()
        val fits = (input == null || request.estimatedTokens <= input * 0.85) && byteFits
        return Evaluation(request, input, bytes, output, fits)
    }
    fun requireFits(config: AgentModelClient.ModelConfig, messages: JSONArray) {
        val result = evaluate(config, messages)
        AgentRequestSendPolicy.requireAllowed(config, result.request.requestBytes)
        if (result.fits) return
        val diagnostic = JSONObject().put("code", "CONTEXT_SUMMARY_INPUT_BUDGET")
            .put("purpose", "compaction").put("request_bytes", result.request.requestBytes)
        throw AgentModelFailure("CONTEXT_SUMMARY_INPUT_BUDGET", false,
            "摘要请求的指令、累计摘要和新片段合计超过本地输入预算。原始历史及已验证断点会保留；请使用更大输入预算的摘要模型或检查上传预算设置。",
            contextRecoveryAllowed = false, diagnosticJson = diagnostic.toString())
    }
}
