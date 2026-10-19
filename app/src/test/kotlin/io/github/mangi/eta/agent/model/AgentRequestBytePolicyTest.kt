package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentRequestBytePolicyTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "deepseek-flash", systemPrompt = "fixed", contextWindow = 1_000_000)

    @Test fun effectiveInputThresholdIncludesExactBoundaryAndUnknownStaysSmall() {
        for ((input, expected) in listOf(null to 524_288, 0 to 524_288, 128_000 to 524_288,
            255_999 to 524_288, 256_000 to 1_048_576, 1_000_000 to 1_048_576)) {
            assertEquals("input=$input", expected, AgentRequestBytePolicy.resolve(config, input))
        }
    }

    @Test fun explicitZeroAndPositiveValuesWinForEveryWindow() {
        for (window in listOf(null, 128_000, 1_000_000)) {
            for (explicit in listOf(0, 1, 524_288, 1_048_576, 2_097_152, 50_331_648)) {
                assertEquals(explicit, AgentRequestBytePolicy.resolve(config.copy(contextWindow = window,
                    requestByteBudget = explicit)))
            }
        }
    }

    @Test fun negativeLegacyValuesUseDerivedDefaultInsteadOfDisablingProtection() {
        assertEquals(1_048_576, AgentRequestBytePolicy.resolve(config.copy(requestByteBudget = -1)))
        assertEquals(524_288, AgentRequestBytePolicy.resolve(config.copy(contextWindow = null, requestByteBudget = -1)))
    }

    @Test fun inputCapAndOutputReservationTakePrecedenceOverTotalWindow() {
        assertEquals(524_288, AgentRequestBytePolicy.resolve(config.copy(inputTokenLimit = 128_000)))
        val total = config.copy(contextWindow = 260_000, contextWindowKind = "total",
            extraBodyJson = """{"max_tokens":8192}""")
        assertEquals(524_288, AgentRequestBytePolicy.resolve(total))
        assertEquals(1_048_576, AgentRequestBytePolicy.resolve(total.copy(contextWindowKind = "input")))
    }

    @Test fun switchingModelsRecomputesDefaultWithoutWritingProviderOverride() {
        val small = config.copy(model = "small", contextWindow = 128_000)
        val unknown = config.copy(model = "unknown", contextWindow = null)
        assertEquals(1_048_576, AgentRequestBytePolicy.resolve(config))
        assertEquals(524_288, AgentRequestBytePolicy.resolve(small))
        assertEquals(524_288, AgentRequestBytePolicy.resolve(unknown))
        assertEquals(1_048_576, AgentRequestBytePolicy.resolve(config))
        assertNull(config.requestByteBudget)
        assertNull(small.requestByteBudget)
    }
}
