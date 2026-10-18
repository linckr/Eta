package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** AIMD applies to capacity; a slow model is not evidence of oversized input. */
internal class AgentCompactionSlicePolicy(private val ceiling: Int, initial: Int, streak: Int = 0) {
    var limit: Int = initial.coerceIn(MIN, ceiling.coerceAtLeast(MIN))
        private set
    var successStreak: Int = streak.coerceIn(0, 1)
        private set
    fun success(elapsedMs: Long, fastLimitMs: Long) {
        if (elapsedMs < 0 || elapsedMs > fastLimitMs) { successStreak = 0; return }
        if (++successStreak >= 2) {
            limit = (limit.toLong() + 4096).coerceAtMost(ceiling.toLong()).toInt()
            successStreak = 0
        }
    }
    fun failure(failure: AgentModelFailure): Boolean {
        successStreak = 0
        val diagnostic = runCatching { JSONObject(failure.diagnosticJson ?: "{}") }.getOrNull()
        val capacity = failure.code in setOf("CONTEXT_OVERFLOW", "CONTEXT_SUMMARY_INPUT_BUDGET")
        val largeIncompleteUpload = diagnostic?.let {
            it.optBoolean("trusted_gateway_ingress") &&
                it.optString("phase") == "request_body" &&
                it.has("upstream_started") && !it.optBoolean("upstream_started") &&
                failure.code in setOf("GATEWAY_REQUEST_BODY_TIMEOUT", "GATEWAY_REQUEST_BODY_INCOMPLETE") &&
                it.optLong("request_bytes", 0) > 128 * 1024 && !it.optBoolean("write_completed") &&
                it.optLong("written_bytes", 0) < it.optLong("request_bytes", 0)
        } == true
        if ((!capacity && !largeIncompleteUpload) || limit <= MIN) return false
        limit = (limit / 2).coerceAtLeast(MIN)
        return true
    }
    companion object { const val MIN = 1024 }
}
