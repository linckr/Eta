package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRequestBudgetTest {
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture-secret", model = "fixture", systemPrompt = "instructions")
    private val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
        .put(JSONObject().put("role", "user").put("content", "你好"))
    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "fixture")
        .put("description", "工具").put("parameters", JSONObject().put("type", "object"))))

    @Test fun oauthProfilesAndLegacyAliasAreMeasuredAfterFinalRewriting() {
        for (mode in listOf(ProviderAuthModes.OPENAI_CHATGPT, ProviderAuthModes.XAI_GROK, CodexCompatibilityProfile.AUTH_MODE)) {
            val configured = config().copy(providerId = "fixture-account", authMode = mode, apiKey = "",
                extraBodyJson = """{"model":"wrong","stream":false,"tools":[]}""")
            for (purpose in listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION)) {
                val request = ProviderRequest(configured, messages, tools, purpose = purpose)
                val profile = ProviderConnectionProfile.forAuthMode(configured.effectiveAuthMode)!!
                assertEquals(profile.baseUrl, request.effectiveConfig.baseUrl)
                assertEquals("", request.effectiveConfig.apiKey)
                val body = AgentProviderRequestJson.build(request)
                assertEquals(configured.model, body.getString("model"))
                assertTrue(body.getBoolean("stream"))
                assertEquals(body.toString().toByteArray(Charsets.UTF_8).size.toLong(),
                    AgentRequestBudget.evaluate(configured, messages, tools, purpose).requestBytes)
                if (!purpose.allowsTools) assertFalse(body.has("tools"))
            }
        }
    }

    @Test fun allProtocolsAndPurposesUseExactlyTheActualFinalBodyIncludingWrapperAndDefaults() {
        val originalMessages = messages.toString()
        val originalTools = tools.toString()
        for (config in listOf(config(), config().copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES), config().copy(providerType = ProviderTypes.ANTHROPIC))) {
            for (purpose in ProviderRequestPurpose.entries) {
                val actual = AgentProviderRequestJson.build(ProviderRequest(config, messages, tools, purpose = purpose))
                val budget = AgentRequestBudget.evaluate(config, messages, tools, purpose)
                assertEquals(actual.toString().toByteArray(Charsets.UTF_8).size.toLong(), budget.requestBytes)
                assertEquals(AgentContextBudget.estimate(JSONArray().put(actual), JSONArray()), budget.estimatedTokens)
                assertTrue(budget.requestBytes > 0)
                assertFalse(actual.toString().contains("fixture-secret"))
                if (!purpose.allowsTools) assertFalse(actual.has("tools"))
            }
        }
        assertEquals(originalMessages, messages.toString())
        assertEquals(originalTools, tools.toString())
    }

    @Test fun protocolMetadataCustomPayloadAndRealProjectionAffectBytesButWindowOverridesDoNot() {
        val base = config().copy(extraBodyJson = JSONObject().put("metadata", "x".repeat(100_000)).toString())
        val plain = AgentRequestBudget.evaluate(config(), messages, tools)
        val large = AgentRequestBudget.evaluate(base, messages, tools)
        assertTrue(large.requestBytes > plain.requestBytes + 90_000)
        assertTrue(large.estimatedTokens > plain.estimatedTokens)
        assertEquals(large, AgentRequestBudget.evaluate(base.copy(contextWindow = 2_000_000), messages, tools))
        assertTrue(AgentRequestBudget.evaluate(base, messages, tools, ProviderRequestPurpose.COMPACTION).requestBytes < large.requestBytes)
        val source = JSONArray().put(JSONObject().put("role", "assistant").put("content", "answer").put("reasoning_content", "r".repeat(50_000)))
            .put(JSONObject().put("role", "user").put("content", "next"))
        val enabled = AgentRequestBudget.evaluate(config(), source, tools)
        val disabled = AgentRequestBudget.evaluate(config().copy(customBody = listOf(CustomBody("reasoning_effort", JsonPrimitive("none")))), source, tools)
        assertTrue(disabled.requestBytes + 40_000 < enabled.requestBytes)
        assertEquals(50_000, source.getJSONObject(0).getString("reasoning_content").length)
    }
}
