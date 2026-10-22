package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33, 36])
class ContextRecoveryBrokerTest {
    private fun decision(permit: Boolean = true) = ContextRecoveryDecision(
        runId = "run", revision = 1, reasonCode = "MODEL_REQUEST_SEND_LIMIT", detail = "limit",
        permitAvailable = permit, permitUnavailableReason = "TOKEN_PRESSURE", requestBytes = 100,
        sendLimitBytes = 99, estimatedTokens = 10, providerLabel = "provider", modelLabel = "model",
    )

    @Test fun concurrentSurfacesAndOldIdentitiesCannotGrantTwice() {
        val broker = ContextRecoveryBroker { 0L }
        val d = decision()
        val ready = CountDownLatch(1)
        val result = AtomicReference<String>()
        val worker = thread { result.set(broker.await(d) { if (it is AgentEvent.ContextRecovery && it.decision.phase == "waiting") ready.countDown() }) }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertFalse(broker.decide("old", d.requestId, d.nonce, d.revision, "send_once").first)
            assertFalse(broker.decide(d.runId, d.requestId, "old", d.revision, "send_once").first)
            val start = CountDownLatch(1)
            val accepted = AtomicInteger()
            val contenders = (1..8).map { thread { start.await(); if (broker.decide(d.runId, d.requestId, d.nonce, d.revision, "send_once").first) accepted.incrementAndGet() } }
            start.countDown(); contenders.forEach { it.join(2_000) }; worker.join(2_000)
            assertEquals(1, accepted.get())
            assertEquals("send_once", result.get())
            assertNull(broker.current)
        } finally { broker.cancel(); worker.join(2_000) }
    }

    @Test fun elapsedTimeIncludingSleepExpiresDecisionWithoutGrant() {
        val now = java.util.concurrent.atomic.AtomicLong(1)
        val broker = ContextRecoveryBroker { now.get() }
        val d = decision()
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = thread { try { broker.await(d) { ready.countDown() } } catch (e: Throwable) { failure.set(e) } }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            now.addAndGet(ContextRecoveryBroker.DECISION_TTL_MS)
            assertFalse(broker.decide(d.runId, d.requestId, d.nonce, d.revision, "send_once").first)
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertEquals("CONTEXT_DECISION_EXPIRED", (failure.get() as io.github.mangi.eta.agent.model.AgentModelFailure).code)
            assertNull(broker.current)
        } finally { broker.cancel(); worker.join(2_000) }
    }

    @Test fun liveIpcPreservesIdentityButArchiveNeverRestoresGrant() {
        val d = decision()
        val live = AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(AgentEvent.ContextRecovery(d))) as AgentEvent.ContextRecovery
        assertEquals(d, live.decision)
        val raw = AgentEventJsonCodec.encode(AgentEvent.ContextRecovery(d))
        assertFalse(raw.contains(d.nonce))
        val restored = AgentEventJsonCodec.decode(raw) as AgentEvent.ContextRecovery
        assertEquals("interrupted", restored.decision.phase)
        assertEquals("", restored.decision.nonce)
        assertFalse(restored.decision.permitAvailable)
    }

    @Test fun archiveKeepsFinalOutcomeWithoutAuthorization() {
        val d = decision().copy(phase = "failed", detail = "connection failed")
        val restored = AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(AgentEvent.ContextRecovery(d))) as AgentEvent.ContextRecovery
        assertEquals("failed", restored.decision.phase)
        assertEquals(d.detail, restored.decision.detail)
        assertEquals("", restored.decision.nonce)
        assertFalse(restored.decision.permitAvailable)
    }

    @Test fun controllerResumeAndSteeringCannotGrantDecisionAndCancelPreservesCompletedToolCounter() {
        val controller = AgentRunController()
        val d = decision(false)
        val ready = CountDownLatch(1)
        val completedTools = AtomicInteger(3)
        val failure = AtomicReference<Throwable>()
        val worker = thread {
            try { controller.contextRecovery.await(d) { ready.countDown() }; completedTools.incrementAndGet() }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            controller.resume()
            assertFalse(controller.steer("new instructions"))
            assertFalse(controller.contextRecovery.decide(d.runId, d.requestId, d.nonce, d.revision, "resume").first)
            assertFalse(controller.contextRecovery.decide(d.runId, d.requestId, d.nonce, d.revision, "send_once").first)
            assertEquals(3, completedTools.get())
            controller.cancel(); worker.join(2_000)
            assertTrue(failure.get() is AgentRunCancelledException)
            assertEquals(3, completedTools.get())
            assertFalse(ContextRecoveryBroker { 0 }.decide(d.runId, d.requestId, d.nonce, d.revision, "send_once").first)
        } finally { controller.cancel(); worker.join(2_000) }
    }

    @Test fun manualCompactionResolvesWithoutOneShotGrant() {
        val broker = ContextRecoveryBroker { 0 }
        val d = decision(false)
        val ready = CountDownLatch(1)
        val result = AtomicReference<String>()
        val worker = thread { result.set(broker.await(d) { ready.countDown() }) }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(broker.decide(d.runId, d.requestId, d.nonce, d.revision, "compact").first)
            worker.join(2_000)
            assertEquals("compact", result.get())
        } finally { broker.cancel(); worker.join(2_000) }
    }
}
