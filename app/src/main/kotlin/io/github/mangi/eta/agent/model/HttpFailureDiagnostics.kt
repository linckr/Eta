package io.github.mangi.eta.agent.model

import org.json.JSONObject
import io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic

/** Only fixed fields, numeric codes and allowlisted type/phase values leave the boundary. */
internal object HttpFailureDiagnostics {
    private val fields = listOf("tool_call_id", "tool_calls", "reasoning_content", "max_input_tokens", "max_tokens", "max_output_tokens", "context_length", "image_url", "messages", "tools", "model")

    data class Parsed(val type: String?, val phase: String?, val upstreamStarted: Boolean?, val sourceIsGateway: Boolean, val traceId: String?)

    fun parse(body: String): Parsed {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        return Parsed(
            error?.optString("type")?.takeIf { it in ModelFailureDiagnostic.serverTypes },
            error?.optString("phase")?.takeIf { it in ModelFailureDiagnostic.phases },
            error?.opt("upstream_started") as? Boolean,
            error?.optString("source") == "workbuddy_gateway",
            error?.optString("trace_id")?.takeIf(ModelFailureDiagnostic::validTrace),
        )
    }

    fun describe(body: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val outer = root.optJSONObject("error") ?: root
        val message = outer.optString("message")
        val start = message.indexOf('{')
        val end = message.lastIndexOf('}')
        val nested = if (start >= 0 && end > start) runCatching { JSONObject(message.substring(start, end + 1)) }.getOrNull() else null
        val inner = nested?.optJSONObject("error") ?: nested ?: outer
        val code = inner.opt("code")?.toString()?.takeIf { it.matches(Regex("[0-9]{1,6}")) }
        val text = (message + " " + inner.optString("message")).lowercase()
        val mentioned = fields.filter { field ->
            inner.optString("param") == field || Regex("(?<![a-z0-9_])${Regex.escape(field)}(?![a-z0-9_])").containsMatchIn(text)
        }
        val details = buildList {
            code?.let { add("服务端编号 $it") }
            if (mentioned.isNotEmpty()) add("涉及字段 ${mentioned.joinToString(", ")}")
        }
        return if (details.isEmpty()) "" else "（${details.joinToString("；")}）"
    }
}