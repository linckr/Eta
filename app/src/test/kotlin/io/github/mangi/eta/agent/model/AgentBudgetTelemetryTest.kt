package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBudgetTelemetryTest {
    @Test fun byteOnlyPressureIsNotEssentialAndCapacityPressureIsEssential() {
        assertFalse(AgentBudgetTelemetry.essential("bytes"))
        assertFalse(AgentBudgetTelemetry.essential("none"))
        assertTrue(AgentBudgetTelemetry.essential("token"))
        assertTrue(AgentBudgetTelemetry.essential("both"))
    }

    @Test fun decisionLineHasStableParsableFields() {
        val line = AgentBudgetTelemetry.line(
            purpose = "CHAT", trigger = "bytes", action = "fallback",
            estimatedTokens = 62812, inputBudget = 1_000_000, requestBytes = 188516, byteBudget = 1024,
            failureCode = "CONTEXT_SUMMARY_INPUT_BUDGET", elapsedMs = 431,
        )
        assertTrue(line.startsWith("context_budget_decision purpose=chat trigger=bytes essential=false action=fallback"))
        assertTrue(line.contains("code=CONTEXT_SUMMARY_INPUT_BUDGET"))
        assertTrue(line.contains("estimated_tokens=62812"))
        assertTrue(line.contains("input_budget=1000000"))
        assertTrue(line.contains("request_bytes=188516"))
        assertTrue(line.contains("byte_budget=1024"))
        assertTrue(line.contains("duration_ms=431"))
        assertParsableWithoutContent(line)
    }

    @Test fun compactDoneLineCarriesAfterMetrics() {
        val line = AgentBudgetTelemetry.line(
            purpose = "chat", trigger = "both", action = "compact_done",
            estimatedTokens = 10, inputBudget = null, requestBytes = null, byteBudget = 0,
            elapsedMs = 1500, tokensAfter = 7, bytesAfter = 900,
        )
        assertTrue(line.contains("essential=true"))
        assertTrue(line.contains("action=compact_done"))
        assertTrue(line.contains("input_budget=unknown"))
        assertTrue(line.contains("request_bytes=unknown"))
        assertTrue(line.contains("tokens_after=7"))
        assertTrue(line.contains("bytes_after=900"))
        assertTrue(line.contains("duration_ms=1500"))
        assertParsableWithoutContent(line)
    }

    @Test fun failureCodeStaysOnTheDecisionLineWithoutMessageText() {
        val line = AgentBudgetTelemetry.line(
            purpose = "chat", trigger = "token", action = "hard_failure",
            estimatedTokens = 1, inputBudget = 2, requestBytes = 3, byteBudget = 4,
            failureCode = "HTTP_400", elapsedMs = 5,
        )
        assertTrue(line.contains("code=HTTP_400"))
        assertParsableWithoutContent(line)
    }

    private fun assertParsableWithoutContent(line: String) {
        assertFalse(line.contains("content="))
        assertFalse(line.contains("\n"))
        val fields = line.substringAfter(' ', missingDelimiterValue = "")
        assertTrue(fields.split(' ').all { it.contains('=') })
    }
}
