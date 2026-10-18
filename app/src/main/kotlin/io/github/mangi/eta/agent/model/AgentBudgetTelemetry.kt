package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.toSafeLogToken

/**
 * Read-only budget telemetry. Emits one stable `context_budget_decision` line per pre-request
 * pressure decision so P1 can measure non-essential compaction and blocked wait time from logcat.
 * It never changes trigger behavior and never contains message content.
 */
internal object AgentBudgetTelemetry {
    /** Token pressure is a capacity signal; byte-only pressure is a local soft preference. */
    fun essential(trigger: String): Boolean = trigger == "token" || trigger == "both"

    fun line(
        purpose: String,
        trigger: String,
        action: String,
        estimatedTokens: Int,
        inputBudget: Int?,
        requestBytes: Long?,
        byteBudget: Int,
        failureCode: String? = null,
        elapsedMs: Long? = null,
        tokensAfter: Int? = null,
        bytesAfter: Long? = null,
        source: String = "preflight",
        decisionId: String? = null,
    ): String = buildString {
        append("context_budget_decision purpose=").append(purpose.lowercase())
        append(" trigger=").append(trigger)
        append(" essential=").append(essential(trigger))
        append(" action=").append(action)
        failureCode?.let { append(" code=").append(it.toSafeLogToken()) }
        append(" estimated_tokens=").append(estimatedTokens)
        append(" input_budget=").append(inputBudget?.toString() ?: "unknown")
        append(" request_bytes=").append(requestBytes?.toString() ?: "unknown")
        append(" byte_budget=").append(byteBudget)
        elapsedMs?.let { append(" duration_ms=").append(it.coerceAtLeast(0)) }
        tokensAfter?.let { append(" tokens_after=").append(it) }
        bytesAfter?.let { append(" bytes_after=").append(it) }
        append(" source=").append(source)
        decisionId?.let { append(" decision_id=").append(it) }
    }

    fun log(line: String) {
        runCatching { AndroidAgentLogger.info(line) }
    }
}
