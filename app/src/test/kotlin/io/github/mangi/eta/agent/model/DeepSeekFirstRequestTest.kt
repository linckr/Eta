package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Captures first-turn requests locally; never calls a model backend. */
class DeepSeekFirstRequestTest {
    @Test
    fun flashFirstTurnUsesSupportedModelEffortsAndAutoToolChoice() {
        val captured = AtomicReference<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
            val body = """{"error":{"type":"invalid_request_error","message":"local fixture rejection"}}""".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val tools = AgentToolCatalog.build(terminalTools = true, browserTools = true)
            listOf(ReasoningEffort.DEFAULT, ReasoningEffort.OFF, ReasoningEffort.LOW,
                ReasoningEffort.HIGH, ReasoningEffort.MAX).forEach { effort ->
                val config = AgentModelClient.ModelConfig(
                    providerId = "builtin-deepseek", providerSourceType = "deepseek",
                    baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "fixture",
                    model = "deepseek-flash", systemPrompt = "", reasoningEffort = effort,
                )
                val failure = assertThrows(AgentModelFailure::class.java) {
                    OpenAiChatCompletionsProvider.complete(
                        ProviderRequest(config,
                            JSONArray().put(AgentConversationCodec.userTextMessage("第一条消息")), tools),
                        AgentRunController(),
                    )
                }
                assertEquals("HTTP_400", failure.code)
                val body = captured.get()
                assertEquals("deepseek-flash", body.getString("model"))
                assertEquals("auto", body.getString("tool_choice"))
                assertTrue(body.getBoolean("stream"))
                assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
                assertEquals(1, body.getJSONArray("messages").length())
                assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
                assertEquals(tools.length(), body.getJSONArray("tools").length())
                if (effort == ReasoningEffort.DEFAULT) {
                    assertFalse(body.has("thinking"))
                    assertFalse(body.has("reasoning_effort"))
                } else {
                    assertEquals(if (effort == ReasoningEffort.OFF) "disabled" else "enabled",
                        body.getJSONObject("thinking").getString("type"))
                    if (effort == ReasoningEffort.OFF) assertFalse(body.has("reasoning_effort"))
                    else assertEquals(effort.wireValue, body.getString("reasoning_effort"))
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
