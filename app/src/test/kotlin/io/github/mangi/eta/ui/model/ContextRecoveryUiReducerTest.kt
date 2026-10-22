package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.runtime.ContextRecoveryDecision
import org.junit.Assert.*
import org.junit.Test

class ContextRecoveryUiReducerTest {
    private fun decision(permit: Boolean = true, phase: String = "waiting", revision: Long = 1) = ContextRecoveryDecision(
        runId = "run", requestId = "request", nonce = "nonce", revision = revision, phase = phase,
        reasonCode = "CONTEXT_SEND_LIMIT", detail = "L exceeded", permitAvailable = permit,
        requestBytes = 101, sendLimitBytes = 100, estimatedTokens = 10, inputLimit = 100,
        providerLabel = "Fake", modelLabel = "fake-model",
    )
    @Test fun sendRequiresRuntimeAvailability() {
        val blocked = ContextRecoveryUi(decision(permit = false))
        assertSame(blocked, ContextRecoveryUiReducer.begin(blocked, blocked.key, "send_once"))
        assertFalse(blocked.canSend)
    }
    @Test fun repeatedClickDoesNotSubmitAgain() {
        val state = ContextRecoveryUi(decision())
        val pending = ContextRecoveryUiReducer.begin(state, state.key, "send_once")!!
        assertTrue(pending.submitting)
        assertSame(pending, ContextRecoveryUiReducer.begin(pending, pending.key, "send_once"))
    }
    @Test fun oldSurfaceCannotActOnNewDecision() {
        val first = ContextRecoveryUi(decision())
        val next = ContextRecoveryUi(decision(revision = 2))
        assertSame(next, ContextRecoveryUiReducer.begin(next, first.key, "send_once"))
        assertSame(next, ContextRecoveryUiReducer.ack(next, first.key, true, "late"))
    }
    @Test fun acceptedAckStaysDisabledUntilRuntimeEvent() {
        val state = ContextRecoveryUi(decision())
        val accepted = ContextRecoveryUiReducer.ack(state, state.key, true, "accepted")!!
        assertFalse(accepted.canSend)
        assertTrue(accepted.submitting)
    }
    @Test fun replayCannotReenableConsumedOrPendingPermission() {
        val d = decision()
        val pending = ContextRecoveryUi(d, submitting = true)
        assertSame(pending, ContextRecoveryUiReducer.receive(pending, d))
        val consumed = ContextRecoveryUi(d.copy(phase = "consumed"))
        assertSame(consumed, ContextRecoveryUiReducer.receive(consumed, d))
    }
    @Test fun expiredAndResolvedAreNotActionable() {
        listOf("expired", "resolved", "consumed", "cancelled").forEach { phase ->
            val state = ContextRecoveryUi(decision(phase = phase))
            assertFalse(state.canSend)
            assertFalse(state.canCompact)
            assertSame(state, ContextRecoveryUiReducer.begin(state, state.key, "stop"))
        }
    }
    @Test fun ordinaryResumeAndModelTextCannotGrantPermission() {
        val state = ContextRecoveryUi(decision())
        listOf("resume", "continue", "model_text", "expand_notification").forEach {
            assertSame(state, ContextRecoveryUiReducer.begin(state, state.key, it))
        }
    }
    @Test fun checkpointUnavailableDoesNotPromiseContinuation() {
        val state = ContextRecoveryUi(decision())
        assertNull(state.decision.completedSlices)
        assertTrue(state.canCompact)
    }
}
