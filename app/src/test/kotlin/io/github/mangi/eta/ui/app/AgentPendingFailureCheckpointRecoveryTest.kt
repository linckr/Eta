package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeResultStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentPendingFailureCheckpointRecoveryTest {
    @Test
    fun checkpointEventAndOutboxRecoverDiagnosticWithoutLosingPartialOutput() {
        val context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val runId = "checkpoint-failure-run"
        val handoff = AgentRuntimeWire.EntryHandoff(runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "test-owner")
        val request = AgentRuntimeWire.RunRequest(runId, "测试", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            emptyList(), handoff = handoff)
        assertTrue(AgentRunCheckpointStore.start(context, request, ownerInstanceId = "former-process", now = 1L))
        val diagnostic = ModelFailureDiagnostic.sanitize("""{"code":"MODEL_TIMEOUT","phase":"response_body","trace_id":"12345678-1234-1234-1234-123456789abc"}""")!!
        val events = listOf(
            AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 4, "部分回答"),
            AgentEvent.RunFailed("超时", diagnostic),
        )
        events.forEachIndexed { index, event -> AgentRunCheckpointStore.append(context, runId, index, event, now = 2L + index) }
        assertTrue(AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff,
            AgentRuntimeWire.RunResult(runId, false, "", error = "超时"), 4L)))
        // Reopen SQLite and rebuild the timeline from the durable checkpoint, then fold the outbox.
        EtaDatabase.closeForTests()
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        val projector = AgentRunMessageProjector { 1000L }
        var state = AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = true, thinkingEnabled = false)
        checkpoint.events.forEach { event -> state = state.copy(messages = projector.applyEvent(runId, event, state.messages)) }
        val result = AgentRuntimeResultStore.list(context).single().result
        val recovered = AgentPendingResultRecovery.apply(state, runId, result, supplements = emptyList())
        assertEquals("部分回答", recovered.state.messages.filterIsInstance<AgentMessageUi>().single().content)
        assertFalse(recovered.state.messages.filterIsInstance<AgentMessageUi>().single().isStreaming)
        assertEquals(diagnostic, recovered.state.messages.filterIsInstance<SystemNoticeMessageUi>().single().diagnosticJson)
        assertEquals(2, recovered.state.messages.size)
        assertTrue(AgentPendingResultRecovery.apply(recovered.state, runId, result, supplements = emptyList()).alreadyApplied)
    }
}
