package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class DeepSeekHttpFailureTest {
    @Test
    fun diagnosticsOnlyApplyToOfficialDeepSeekSourceAndHttpsHost() {
        val config = AgentModelClient.ModelConfig(providerId = "builtin-deepseek", providerSourceType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "fixture", model = "deepseek-flash", systemPrompt = "")
        assertTrue(DeepSeekHttpFailure.appliesTo(config))
        listOf("https://proxy.example.com", "https://api.deepseek.com.example.com",
            "https://api.deepseek.com@proxy.example.com", "http://api.deepseek.com", "https://api.deepseek.com:8443")
            .forEach { assertFalse(DeepSeekHttpFailure.appliesTo(config.copy(baseUrl = it))) }
        assertFalse(DeepSeekHttpFailure.appliesTo(config.copy(providerId = "openai", providerSourceType = "openai")))
    }

    @Test
    fun knownValidationParameterIsShownWithoutEchoingSecretResponseText() {
        val failure = DeepSeekHttpFailure.from(400,
            """{"error":{"param":"tools","message":"private fixture credential sk-test-private"}}""")
        assertEquals("HTTP_400", failure.code)
        assertFalse(failure.retryable)
        assertTrue(failure.message.orEmpty().contains("tools"))
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test
    fun unknownParametersAndMessagesAreNeverEchoed() {
        val failure = DeepSeekHttpFailure.from(400,
            """{"error":{"param":"private-user-text","message":"private fixture credential"}}""")
        assertEquals(AgentModelFailure.http(400, "{}").message, failure.message)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test
    fun reasoningRequirementUsesStaticTextAndPreservesFailureCode() {
        val failure = DeepSeekHttpFailure.from(400,
            """{"error":{"message":"The reasoning_content must be passed back. private fixture"}}""")
        assertEquals("HTTP_400", failure.code)
        assertTrue(failure.message.orEmpty().contains("reasoning_content"))
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test
    fun existingContextOverflowAndQuotaClassificationArePreserved() {
        assertEquals("CONTEXT_OVERFLOW", DeepSeekHttpFailure.from(400,
            """{"error":{"code":"context_length_exceeded","param":"messages"}}""").code)
        assertEquals(AgentModelFailure.http(429, "{}").message,
            DeepSeekHttpFailure.from(429, "{}").message)
        val quota = """{"error":{"code":"insufficient_quota","param":"input"}}"""
        assertEquals(AgentModelFailure.http(400, quota).message,
            DeepSeekHttpFailure.from(400, quota).message)
    }

    @Test
    fun knownResponsesParametersUseStaticLabelsWithoutReturningModelOrPromptContent() {
        listOf("input", "reasoning", "reasoning.effort", "max_output_tokens").forEach { parameter ->
            val failure = DeepSeekHttpFailure.from(400,
                """{"error":{"param":"$parameter","message":"private prompt and credential"}}""")
            assertTrue(failure.message.orEmpty().contains(parameter))
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }
}
