package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.runtime.ContextRecoveryDecision

/** Memory-only UI projection. A stale ACK never changes another request or any composer. */
internal data class ContextRecoveryUi(
    val decision: ContextRecoveryDecision,
    val submitting: Boolean = false,
    val ackMessage: String? = null,
) {
    val key: String get() = "${decision.runId}/${decision.requestId}/${decision.nonce}/${decision.revision}"
    val waiting: Boolean get() = decision.phase == "waiting"
    val canSend: Boolean get() = waiting && decision.permitAvailable && !submitting
    val canCompact: Boolean get() = waiting && !submitting
}

internal object ContextRecoveryUiReducer {
    fun receive(current: ContextRecoveryUi?, next: ContextRecoveryDecision): ContextRecoveryUi {
        if (current != null && current.decision.runId == next.runId &&
            current.decision.revision > next.revision) return current
        if (current != null && current.decision.runId == next.runId && current.decision.requestId == next.requestId && current.decision.revision == next.revision) {
            if (current.decision == next) return current
            if (current.decision.phase != "waiting" && next.phase == "waiting") return current
        }
        return ContextRecoveryUi(next)
    }
    fun begin(current: ContextRecoveryUi?, key: String, action: String): ContextRecoveryUi? {
        if (current == null || current.key != key || current.submitting || !current.waiting) return current
        if (action !in setOf("send_once", "compact", "stop")) return current
        if (action == "send_once" && !current.canSend) return current
        return current.copy(submitting = true, ackMessage = null)
    }
    fun ack(current: ContextRecoveryUi?, key: String, accepted: Boolean, message: String): ContextRecoveryUi? {
        if (current == null || current.key != key) return current
        // Successful ACK does not grant an optimistic second click; wait for authoritative event.
        return current.copy(submitting = accepted, ackMessage = message)
    }
}
