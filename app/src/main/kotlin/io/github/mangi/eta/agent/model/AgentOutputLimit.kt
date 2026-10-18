package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import org.json.JSONArray
import org.json.JSONObject

/** Shared option application keeps the budget consistent with the actual provider request. */
internal object AgentOutputLimit {
    fun applyChatOptions(request: JSONObject, config: AgentModelClient.ModelConfig) {
        mergeExtraBody(request, config.extraBodyJson)
        RequestBodyMerge.mergeCustomBody(request, config.customBody)
        ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
    }

    fun applyResponsesOptions(request: JSONObject, config: AgentModelClient.ModelConfig) {
        mergeExtraBody(request, config.extraBodyJson)
        RequestBodyMerge.mergeCustomBody(request, config.customBody)
    }

    fun applyAnthropicOptions(request: JSONObject, config: AgentModelClient.ModelConfig) {
        request.put("max_tokens", if (config.model.trim().lowercase() in setOf("claude-fable-5-1", "claude-opus-5-5")) 16_384 else 4096)
        RequestBodyMerge.mergeCustomBody(request, config.customBody)
        val explicit = request.opt("max_tokens").takeIf { config.customBody.any { it.key == "max_tokens" } }
        ProviderReasoning.applyAnthropicRequest(request, config)
        if (explicit != null) request.put("max_tokens", explicit)
    }

    /** Null means absent/invalid, not a guessed server default. Never alter the user's limit. */
    fun effective(config: AgentModelClient.ModelConfig, purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT): Int? {
        val effectiveConfig = ProviderRequest(config, JSONArray(), JSONArray(), purpose = purpose).effectiveConfig
        val request = JSONObject()
        val field = when {
            effectiveConfig.providerType == ProviderTypes.ANTHROPIC -> {
                applyAnthropicOptions(request, effectiveConfig)
                "max_tokens"
            }
            effectiveConfig.openAiEndpointMode == OpenAiEndpointMode.RESPONSES -> {
                applyResponsesOptions(request, effectiveConfig)
                "max_output_tokens"
            }
            else -> {
                applyChatOptions(request, effectiveConfig)
                // Both caps are accounted for conservatively if the user sends both.
                return listOf("max_completion_tokens", "max_tokens").mapNotNull { positiveInteger(request.opt(it)) }.maxOrNull()
            }
        }
        return positiveInteger(request.opt(field))
    }

    private fun positiveInteger(value: Any?): Int? {
        if (value !is Number) return null
        val number = value.toDouble()
        if (!number.isFinite() || number < 1 || number > Int.MAX_VALUE || number != number.toLong().toDouble()) return null
        return number.toInt()
    }

    private fun mergeExtraBody(request: JSONObject, raw: String) {
        if (raw.isBlank()) return
        val extra = JSONObject(raw)
        extra.keys().forEach { key -> request.put(key, extra.get(key)) }
    }
}
