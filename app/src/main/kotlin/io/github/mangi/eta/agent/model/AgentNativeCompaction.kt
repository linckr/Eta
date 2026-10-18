package io.github.mangi.eta.agent.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in through context_management. Compatibility alone never grants this capability. */
internal object AgentNativeCompaction {
    const val ANTHROPIC_BETA = "compact-2026-01-12"
    fun validate(config: AgentModelClient.ModelConfig, body: JSONObject, endpoint: EndpointKind): Boolean {
        if (!body.has("context_management")) return false
        fun reject(): Nothing = throw AgentModelFailure("NATIVE_COMPACTION_UNSUPPORTED", false,
            "原生压缩配置未通过官方接口、模型或参数能力校验；请移除 context_management，使用 Eta 本地压缩。原历史未修改。")
        val url = config.baseUrl.toHttpUrlOrNull() ?: reject()
        if (url.scheme != "https" || url.port != 443) reject()
        val window = AgentContextBudget.inputBudget(config, ProviderRequestPurpose.CHAT) ?: reject()
        when (endpoint) {
            EndpointKind.RESPONSES -> {
                // Explicitly documented model, conservatively bounded rather than guessed aliases.
                if (url.host != "api.openai.com" || config.model != "gpt-5.3-codex") reject()
                val edits = body.optJSONArray("context_management") ?: reject()
                if (edits.length() != 1) reject()
                val edit = edits.optJSONObject(0) ?: reject()
                if (edit.keys().asSequence().toSet() != setOf("type", "compact_threshold") || edit.optString("type") != "compaction") reject()
                val threshold = runCatching { AgentTaskMemory.integer(edit, "compact_threshold") }.getOrElse { reject() }
                if (threshold <= 0 || threshold >= window) reject()
            }
            EndpointKind.ANTHROPIC_MESSAGES -> {
                val model = config.model.removePrefix("claude-")
                val supported = listOf("opus-4-6", "opus-4-7", "opus-4-8", "opus-5", "opus-5-5", "sonnet-4-6", "sonnet-5", "sonnet-5-5", "haiku-5-5",
                    "fable-5", "fable-5-1", "mythos-5", "mythos-5-1", "mythos-preview")
                if (url.host != "api.anthropic.com" || supported.none { model == it || model.matches(Regex("${Regex.escape(it)}-[0-9]{8}")) }) reject()
                val management = body.optJSONObject("context_management") ?: reject()
                if (management.keys().asSequence().toSet() != setOf("edits")) reject()
                val edits = management.optJSONArray("edits") ?: reject()
                if (edits.length() != 1) reject()
                val edit = edits.optJSONObject(0) ?: reject()
                if (edit.keys().asSequence().any { it !in setOf("type", "trigger", "pause_after_compaction") } || edit.optString("type") != "compact_20260112") reject()
                if (edit.has("pause_after_compaction") && edit.opt("pause_after_compaction") != false) reject()
                val trigger = edit.optJSONObject("trigger")
                val threshold = if (trigger == null) 150_000 else {
                    if (trigger.keys().asSequence().toSet() != setOf("type", "value") || trigger.optString("type") != "input_tokens") reject()
                    runCatching { AgentTaskMemory.integer(trigger, "value") }.getOrElse { reject() }
                }
                if (threshold < 50_000 || threshold >= window) reject()
            }
            else -> reject()
        }
        return true
    }

    fun hasCompaction(message: JSONObject): Boolean = AnthropicEphemeralState.contentBlocks(message)?.let { blocks ->
        (0 until blocks.length()).any { blocks.optJSONObject(it)?.optString("type") == "compaction" }
    } == true
}
