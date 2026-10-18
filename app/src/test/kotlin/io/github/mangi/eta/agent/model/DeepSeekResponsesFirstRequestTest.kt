package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Documents the official Responses compatibility contract without outbound model traffic. */
class DeepSeekResponsesFirstRequestTest {
    @Test
    fun flashResponsesFirstTurnKeepsProtocolAndValidEffortAndFunctionNames() {
        val tools = AgentToolCatalog.build(terminalTools = true, browserTools = true,
            deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true)
        listOf(ReasoningEffort.DEFAULT, ReasoningEffort.OFF, ReasoningEffort.LOW,
            ReasoningEffort.HIGH, ReasoningEffort.MAX).forEach { effort ->
            val config = AgentModelClient.ModelConfig(providerId = "builtin-deepseek", providerSourceType = "deepseek",
                baseUrl = "https://api.deepseek.com", apiKey = "fixture", model = "deepseek-flash", systemPrompt = "",
                openAiEndpointMode = OpenAiEndpointMode.RESPONSES, reasoningEffort = effort,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                    canDisable = true))
            assertSame(OpenAiResponsesProvider, ProviderClientFactory.getClient(config))
            val body = OpenAiResponsesProvider.buildRequestJson(config,
                JSONArray().put(AgentConversationCodec.userTextMessage("第一条消息")), tools)
            assertEquals("deepseek-flash", body.getString("model"))
            assertEquals("auto", body.getString("tool_choice"))
            assertTrue(body.getBoolean("stream"))
            assertFalse(body.has("messages"))
            assertEquals(1, body.getJSONArray("input").length())
            val reasoning = body.getJSONObject("reasoning")
            if (effort == ReasoningEffort.DEFAULT) assertFalse(reasoning.has("effort"))
            else assertEquals(if (effort == ReasoningEffort.OFF) "none" else effort.wireValue,
                reasoning.getString("effort"))
            // DeepSeek accepts summary but does not generate a summary; this is not a 400 cause.
            if (effort != ReasoningEffort.OFF) assertEquals("auto", reasoning.getString("summary"))
            val names = mutableSetOf<String>()
            val responseTools = body.getJSONArray("tools")
            for (index in 0 until responseTools.length()) {
                val tool = responseTools.getJSONObject(index)
                assertEquals("function", tool.getString("type"))
                val name = tool.getString("name")
                assertTrue("invalid function name: $name", name.matches(Regex("^[a-zA-Z0-9_-]{1,128}$")))
                assertTrue("duplicate function name: $name", names.add(name))
                assertTrue(tool.has("parameters"))
            }
        }
    }
}
