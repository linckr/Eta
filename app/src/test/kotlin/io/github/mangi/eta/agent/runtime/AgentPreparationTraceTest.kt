package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.AgentLogger
import org.junit.Assert.*
import org.junit.Test

class AgentPreparationTraceTest {
    @Test fun boundariesUseMonotonicTimeAndNeverLogConversationContent() {
        val lines = mutableListOf<String>()
        val logger = object : AgentLogger {
            override fun debug(message: () -> String) { error("Must survive release debug removal") }
            override fun info(message: String) { lines += message }
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }
        var now = 10_000_000L
        val trace = AgentPreparationTrace("run-test", "ui", logger) { now }
        trace.mark(AgentPreparationTrace.Stage.QUEUED)
        now += 45_000_000_000L
        trace.mark(AgentPreparationTrace.Stage.PERSISTED)
        assertTrue(lines[0].endsWith("stage=queued elapsed_ms=0"))
        assertTrue(lines[1].endsWith("stage=persisted elapsed_ms=45000"))
        assertTrue(lines.all { it.startsWith("agent_request_preparation run_id=run-test component=ui ") })
    }

    @Test fun malformedCountsCannotEnterSafeDiagnostics() {
        for (value in listOf("\"secret-text\"", "-1", "24001", "1.5")) {
            val safe = ModelFailureDiagnostic.sanitize("{\"summary_fact_count\":$value}")
            assertNull(safe)
        }
        assertEquals("{\"summary_fact_count\":17}", ModelFailureDiagnostic.sanitize("{\"summary_fact_count\":17,\"body\":\"private\"}"))
    }
}
