package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionRequestBudgetTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "fixture")
    private fun messages(text: String) = JSONArray().put(AgentConversationCodec.userTextMessage(text))

    @Test fun actualProtocolBytesIncludeOldSummaryAndNewSlice() {
        val request = messages("old summary\n" + "新".repeat(5000))
        val result = AgentCompactionRequestBudget.evaluate(config, request)
        val body = AgentProviderRequestJson.build(ProviderRequest(config, request, JSONArray(), purpose = ProviderRequestPurpose.COMPACTION))
        assertEquals(body.toString().toByteArray(Charsets.UTF_8).size.toLong(), result.request.requestBytes)
        assertTrue(result.request.requestBytes > 15_000)
        assertTrue(result.fits)
    }

    @Test fun outputReserveOnlyDeductsFromDocumentedTotalWindow() {
        val total = AgentCompactionRequestBudget.evaluate(config.copy(contextWindow = 16_000,
            contextWindowKind = "total", inputTokenLimit = 15_000), messages("small"))
        assertEquals(4000, total.outputReserve)
        assertEquals(12_000, total.inputBudget)
        val input = AgentCompactionRequestBudget.evaluate(config.copy(contextWindow = 16_000,
            contextWindowKind = "input", inputTokenLimit = 15_000), messages("small"))
        assertEquals(15_000, input.inputBudget)
    }

    @Test fun fixedPayloadOverBudgetFailsBeforeProviderCallAndZeroOnlyDisablesByteLimit() {
        val huge = messages("x".repeat(20_000))
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentCompactionRequestBudget.requireFits(config.copy(requestByteBudget = 4096), huge)
        }
        assertEquals("CONTEXT_SUMMARY_INPUT_BUDGET", failure.code)
        assertTrue(AgentCompactionRequestBudget.evaluate(config.copy(requestByteBudget = 0), huge).fits)
        assertFalse(AgentCompactionRequestBudget.evaluate(config.copy(requestByteBudget = 0,
            contextWindow = 10_000, contextWindowKind = "total"), huge).fits)
    }
}
