package io.github.mangi.eta.agent.runtime

import android.os.SystemClock
import java.util.UUID

/** Content-free live decision. nonce is never archived or restored from disk. */
internal data class ContextRecoveryDecision(
    val runId: String, val requestId: String = UUID.randomUUID().toString(),
    val nonce: String = UUID.randomUUID().toString(), val revision: Long,
    val phase: String = "waiting", val reasonCode: String, val detail: String,
    val stage: String = "pre_send", val permitAvailable: Boolean,
    val permitUnavailableReason: String = "", val requestBytes: Long,
    val sendLimitBytes: Long, val hardLimitBytes: Long? = null,
    val estimatedTokens: Int, val inputLimit: Int? = null,
    val providerLabel: String, val modelLabel: String, val completedSlices: Int? = null,
)

/** One live worker owns the blocked model step; resume() cannot authorize it. */
internal class ContextRecoveryBroker(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val monitor = Object()
    private var pending: ContextRecoveryDecision? = null
    private var selected: String? = null
    private var deadline = 0L
    private var cancelled = false
    val current: ContextRecoveryDecision? get() = synchronized(monitor) { pending }

    fun decide(runId: String, requestId: String, nonce: String, revision: Long, action: String): Pair<Boolean, String> = synchronized(monitor) {
        val decision = pending ?: return false to "当前没有等待中的请求"
        if (cancelled || decision.runId != runId || decision.requestId != requestId ||
            decision.nonce != nonce || decision.revision != revision || selected != null)
            return false to "决定已失效或已由另一入口处理"
        if (clock() >= deadline) return false to "决定已过期，请重新确认"
        if (action !in setOf("send_once", "compact", "stop")) return false to "未知恢复操作"
        if (action == "send_once" && !decision.permitAvailable) return false to decision.permitUnavailableReason
        selected = action
        monitor.notifyAll()
        true to "已接收"
    }

    fun await(decision: ContextRecoveryDecision, emit: (AgentEvent) -> Unit): String {
        synchronized(monitor) {
            if (cancelled) throw AgentRunCancelledException()
            check(pending == null) { "A recovery decision already owns this run" }
            pending = decision
            selected = null
            deadline = clock() + DECISION_TTL_MS
        }
        try {
            emit(AgentEvent.ContextRecovery(decision))
            return synchronized(monitor) {
                while (!cancelled && selected == null && clock() < deadline) {
                    try { monitor.wait((deadline - clock()).coerceIn(1L, 1_000L)) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw AgentRunCancelledException() }
                }
                if (cancelled || selected == "stop") throw AgentRunCancelledException()
                selected ?: throw io.github.mangi.eta.agent.model.AgentModelFailure(
                    "CONTEXT_DECISION_EXPIRED", false, "恢复决定已过期，原历史、工具结果和有效断点已保留。")
            }
        } finally {
            val phase = synchronized(monitor) {
                val result = if (cancelled || selected == "stop") "cancelled" else if (selected == null) "expired" else "resolved"
                pending = null; selected = null
                result
            }
            emit(AgentEvent.ContextRecovery(decision.copy(phase = phase, permitAvailable = false, nonce = "")))
        }
    }

    fun cancel() = synchronized(monitor) { cancelled = true; monitor.notifyAll() }
    companion object { const val DECISION_TTL_MS = 15 * 60 * 1000L }
}
