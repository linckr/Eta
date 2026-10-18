package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import org.json.JSONArray
import org.json.JSONObject

/** 将 Eta 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。 */
internal object OpenAiRequestMessages {
    fun forChatCompletions(source: JSONArray): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        val copied = JSONArray().also { messages ->
            for (index in 0 until source.length()) {
                val item = source.opt(index)
                messages.put(if (item is JSONObject) JSONObject(item.toString()) else item)
            }
        }
        // system 合并前保留原始边界，不能把跨消息的工具结果误配为完整批次。
        ChatToolCallHistory.repairCompleteBatches(copied)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until copied.length()) {
                val message = copied.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) messages.put(message.apply {
                    remove("_eta_context_summary")
                    remove("_eta_compacted_users")
                    remove("_eta_summary_through_user")
                    remove("_eta_observation")
                    remove("_eta_message_id")
                    remove("_eta_character_profile")
                })
            }
        }
    }

    /** Final request controls, not the UI switch, decide whether thought history is unnecessary. */
    fun forChatCompletions(source: JSONArray, config: AgentModelClient.ModelConfig, finalRequest: JSONObject): JSONArray {
        val projected = forChatCompletions(source)
        if (!canOmitReasoning(config, finalRequest)) return projected
        for (index in 0 until projected.length()) {
            val message = projected.optJSONObject(index) ?: continue
            if (message.optString("role") == "assistant") {
                message.remove("reasoning_content")
                message.remove("reasoning")
            }
        }
        return projected
    }

    private fun canOmitReasoning(config: AgentModelClient.ModelConfig, request: JSONObject): Boolean {
        val thinking = request.optJSONObject("thinking")
        val effort = request.optString("reasoning_effort").lowercase()
        val reasoning = request.optJSONObject("reasoning")
        val thinkingType = thinking?.optString("type").orEmpty().lowercase()
        val nestedEffort = reasoning?.optString("effort").orEmpty().lowercase()
        val enabled = thinkingType in setOf("enabled", "adaptive") || request.opt("enable_thinking") == true ||
            effort.isNotBlank() && effort !in setOf("none", "off") ||
            nestedEffort.isNotBlank() && nestedEffort !in setOf("none", "off") || reasoning?.opt("enabled") == true
        // Conflicting/unknown controls are conservative: do not damage a thinking tool continuation.
        if (enabled) return false
        val disabled = thinkingType == "disabled" || request.opt("enable_thinking") == false ||
            effort in setOf("none", "off") || nestedEffort in setOf("none", "off") || reasoning?.opt("enabled") == false
        if (disabled) return true
        val source = ProviderSourceRegistry.resolve(config.providerId, config.providerSourceType, config.baseUrl, config.providerType)
        val model = config.model.trim().lowercase().substringAfterLast('/')
        val deepSeek = source == ProviderSourceTypes.DEEPSEEK || model.startsWith("deepseek-") || model.startsWith("dsv4-")
        // DeepSeek thinking defaults on and requires ALL historical reasoning when tools is carried.
        // Protocol reference: https://api-docs.deepseek.com/guides/thinking_mode/
        // Without tools the official API ignores that field. Never remove it from tool requests.
        if (deepSeek) return !request.has("tools")
        val capability = config.reasoningCapabilities
        val explicitlyNonThinking = capability?.defaultEnabled == false && !capability.mandatory &&
            capability.supportedEfforts.isEmpty() && !capability.supportsBudget
        return explicitlyNonThinking
    }

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
