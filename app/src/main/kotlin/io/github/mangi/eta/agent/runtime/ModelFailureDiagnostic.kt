package io.github.mangi.eta.agent.runtime

import org.json.JSONObject

/** Persist a bounded projection, never provider messages, arbitrary headers or body text. */
internal object ModelFailureDiagnostic {
    val summaryStructureReasons = setOf("json", "schema", "fields", "field_type", "schema_version", "base_revision",
        "source", "fact_id", "operation_conflict", "unknown_fact", "constraint_removal", "section", "fact_length",
        "fact_count", "operation_count", "total_capacity", "response_bounds", "interval_count")
    val summaryFieldNames = setOf("schema", "source", "facts", "section", "text", "id", "base_revision", "add", "update", "remove", "revision", "order", "deltas")
    val serverTypes = setOf("read_error", "invalid_json", "request_body_timeout", "request_body_incomplete", "authentication_error", "rate_limit_error", "rate_limit_exceeded", "overloaded_error", "server_error", "context_length_exceeded", "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached", "api_error", "internal_error", "provider_unavailable", "service_unavailable", "context_window_exceeded", "prompt_too_long", "input_too_long")
    val phases = setOf("connect", "request_body", "response_headers", "response_body", "complete", "failure", "unknown")
    private val codes = setOf("CONTEXT_SUMMARY_INPUT_BUDGET", "CONTEXT_SUMMARY_TIME_BUDGET", "CONTEXT_SUMMARY_CHECKPOINT_FAILED", "CONTEXT_COMPACTION_MODEL_UNAVAILABLE", "CONTEXT_SUMMARY_INVALID", "CONTEXT_SUMMARY_BUDGET", "CONTEXT_SUMMARY_TIMEOUT", "CONTEXT_SUMMARY_TRANSPORT_FAILED", "CONTEXT_OVERFLOW", "CONTEXT_OUTPUT_BUDGET_INVALID", "MODEL_TIMEOUT", "MODEL_TLS_FAILED", "MODEL_PROTOCOL_FAILED", "MODEL_CONNECTION_FAILED", "STREAM_INCOMPLETE", "PROVIDER_STREAM_ERROR", "MODEL_EMPTY_RESPONSE", "MODEL_TOOL_PROTOCOL_LEAK", "GATEWAY_REQUEST_READ_FAILED", "GATEWAY_REQUEST_BODY_TIMEOUT", "GATEWAY_REQUEST_BODY_INCOMPLETE", "GATEWAY_INVALID_JSON")
    val streamNumberFields = setOf("response_read_bytes", "first_read_ms", "last_read_ms", "sse_events", "usage_events", "usable_input_usage_events",
        "responses_stream_function_items", "responses_terminal_function_items", "responses_terminal_message_items", "responses_terminal_other_items",
        "content_chars", "reasoning_chars", "tool_chars", "content_events", "reasoning_events", "tool_events",
        "first_content_ms", "last_content_ms", "first_reasoning_ms", "last_reasoning_ms")
    fun validCode(value: String): Boolean = value in codes || value.matches(Regex("HTTP_[1-5][0-9]{2}"))
    fun validTrace(value: String): Boolean = value.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 4096) return null
        val source = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val safe = JSONObject()
        source.optString("code").takeIf(::validCode)?.let { safe.put("code", it) }
        source.optString("server_type").takeIf { it in serverTypes }?.let { safe.put("server_type", it) }
        source.optString("phase").takeIf { it in phases }?.let { safe.put("phase", it) }
        source.optString("purpose").takeIf { it in setOf("chat", "compaction", "reply_rewrite", "transcript_refine") }
            ?.let { safe.put("purpose", it) }
        source.optString("summary_validation").takeIf { it in setOf("response", "leaf", "merge") }
            ?.let { safe.put("summary_validation", it) }
        source.optString("summary_structure_reason").takeIf { it in summaryStructureReasons }
            ?.let { safe.put("summary_structure_reason", it) }
        source.optString("summary_field_scope").takeIf { it in setOf("patch", "fact", "leaf", "leaf_fact", "object", "bound_patch", "bound_delta", "bound_fact") }
            ?.let { safe.put("summary_field_scope", it) }
        source.optJSONArray("summary_missing_fields")?.let { fields ->
            if (fields.length() <= summaryFieldNames.size && (0 until fields.length()).all { fields.opt(it) is String && fields.getString(it) in summaryFieldNames })
                safe.put("summary_missing_fields", fields)
        }
        (source.opt("summary_extra_field_count") as? Number)?.toInt()?.takeIf { it in 0..24_000 }
            ?.let { safe.put("summary_extra_field_count", it) }
        (source.opt("summary_fact_count") as? Number)?.let { number ->
            number.toInt().takeIf { it in 0..24_000 && number.toDouble() == it.toDouble() }
                ?.let { safe.put("summary_fact_count", it) }
        }
        source.optString("client_phase").takeIf { it in phases }?.let { safe.put("client_phase", it) }
        (source.opt("trusted_gateway_ingress") as? Boolean)?.let { safe.put("trusted_gateway_ingress", it) }
        source.optString("trace_id").takeIf(::validTrace)?.let { safe.put("trace_id", it) }
        val status = source.opt("http_status") as? Number
        status?.toInt()?.takeIf { it in 100..599 }?.let { safe.put("http_status", it) }
        (source.opt("upstream_started") as? Boolean)?.let { safe.put("upstream_started", it) }
        for (key in listOf("round", "attempt", "max_attempts", "delay_ms")) {
            (source.opt(key) as? Number)?.toInt()?.takeIf { it in 0..1_000_000 }?.let { safe.put(key, it) }
        }
        (source.opt("write_completed") as? Boolean)?.let { safe.put("write_completed", it) }
        for (key in listOf("request_bytes", "written_bytes", "elapsed_ms")) {
            (source.opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }?.let { safe.put(key, it) }
        }
        for (key in streamNumberFields) {
            (source.opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }?.let { safe.put(key, it) }
        }
        (source.opt("summary_invalid_reason") as? Number)?.toLong()?.takeIf { it in 1L..4L }
            ?.let { safe.put("summary_invalid_reason", it) }
        (source.opt("summary_chars") as? Number)?.toLong()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.let { safe.put("summary_chars", it) }
        (source.opt("occurred_at_ms") as? Number)?.toLong()?.takeIf { it > 0 }?.let { safe.put("occurred_at_ms", it) }
        return safe.takeIf { it.length() > 0 }?.toString()
    }
}
