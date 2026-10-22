package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRecoveryLoopTest {
    @Test fun permitContinuesBlockedModelStepWithoutReexecutingCompletedTool() = exercise("send_once")
    @Test fun stoppingBlockedStepPreservesCompletedToolAndSnapshot() = exercise("stop")

    private fun exercise(action: String) {
        val controller = AgentRunController().apply { recoveryRunId = "fixture-run" }
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("start"))
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val base = AgentModelClient.ModelConfig(baseUrl = "http://127.0.0.1:1", apiKey = "fixture",
            model = "fixture", contextWindow = 1_000_000, systemPrompt = "", autoCompactionEnabled = false,
            compactionPolicyVersion = 4)
        val initialBytes = AgentRequestBudget.evaluate(base, messages, tools, ProviderRequestPurpose.CHAT).requestBytes
        val config = base.copy(requestSendLimitBytes = initialBytes + 1024)
        var executions = 0
        var requests = 0
        var snapshotHasTool = false
        var transcriptHasTool = false
        var decisions = 0
        val events = mutableListOf<AgentEvent>()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests++
                if (requests == 1) return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject().put("id", "observe")
                        .put("type", "function").put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}")))))
                assertNotNull(request.oneShotAttempt)
                assertEquals(1, executions)
                assertTrue(request.messages.toString().contains("preserved-observation"))
                AgentOneShotHttp.prepare(request, Request.Builder().url(ProviderUrls.openAiChatCompletionsUrl(config.baseUrl))
                    .post(AgentProviderRequestJson.build(request).toString().toRequestBody()).build())
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
            }
        }
        val loop = AgentLoop(config, messages, tools, provider,
            AgentModelClient.ToolExecutor { executions++; AgentModelClient.ToolResult("preserved-observation" + "x".repeat(8192)) },
            controller, AgentTraceFormatter(), onEvent = { event ->
                events += event
                if (event is AgentEvent.ContextRecovery && event.decision.phase == "waiting") {
                    decisions++
                    assertTrue(snapshotHasTool)
                    assertTrue(transcriptHasTool)
                    assertEquals(1, executions)
                    assertTrue(event.decision.permitAvailable)
                    val d = event.decision
                    assertTrue(controller.contextRecovery.decide(d.runId, d.requestId, d.nonce, d.revision, action).first)
                }
            }, onContextSnapshot = { snapshotHasTool = it.encode().contains("preserved-observation") },
            onTranscript = { transcriptHasTool = it.toString().contains("preserved-observation") })
        if (action == "stop") assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        else {
            assertEquals("done", loop.run().content)
            assertEquals("completed", events.filterIsInstance<AgentEvent.ContextRecovery>().last().decision.phase)
        }
        assertEquals(1, executions)
        assertEquals(1, decisions)
        assertEquals(if (action == "stop") 1 else 2, requests)
        assertTrue(messages.toString().contains("preserved-observation"))
    }
}
