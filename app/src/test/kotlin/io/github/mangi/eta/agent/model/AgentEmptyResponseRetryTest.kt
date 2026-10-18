package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AgentEmptyResponseRetryTest {
    @Test
    fun emptyThenSuccessDiscardsFailedReasoningAndKeepsTheSameRequest() {
        val requests = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        val result = loop(provider { request, emit ->
            requests += request.messages.toString()
            if (requests.size == 1) {
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "failed-attempt"))
                response("")
            } else {
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "successful-attempt"))
                response("done")
            }
        }, events = events).run()
        assertEquals("done", result.content)
        assertEquals("successful-attempt", result.reasoningContent)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals("MODEL_EMPTY_RESPONSE", events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().reasonCode)
    }

    @Test
    fun emptyAfterToolExecutionDoesNotExecuteTheToolAgain() {
        var executions = 0
        val requests = mutableListOf<String>()
        val toolSnapshots = mutableListOf<String>()
        val result = loop(provider { request, _ ->
            requests += request.messages.toString()
            toolSnapshots += request.tools.toString()
            when (requests.size) {
                1 -> ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(
                        JSONObject().put("id", "call-once").put("type", "function")
                            .put("function", JSONObject().put("name", "probe").put("arguments", "{}")))))
                2 -> response("")
                else -> response("done")
            }
        }, execute = { executions++; AgentModelClient.ToolResult("already-completed") }).run()
        assertEquals("done", result.content)
        assertEquals(1, executions)
        assertEquals(3, requests.size)
        assertEquals(requests[1], requests[2])
        assertEquals(toolSnapshots[1], toolSnapshots[2])
        assertTrue(requests[2].contains("already-completed"))
    }

    @Test
    fun repeatedEmptyResponsesStopAfterOneRetry() {
        var requests = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            loop(provider { _, _ -> requests++; response("") }).run()
        }
        assertEquals("MODEL_EMPTY_RESPONSE", failure.code)
        assertEquals(2, requests)
        assertFalse(failure.retryable)
    }

    @Test
    fun outputLimitFilterAndContextOverflowAreNeverReplayed() {
        for ((finish, expected) in listOf("length" to "MODEL_OUTPUT_LIMIT", "content_filter" to "MODEL_CONTENT_FILTER")) {
            var requests = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                loop(provider { _, _ -> requests++; response("", finish) }).run()
            }
            assertEquals(expected, failure.code)
            assertEquals(1, requests)
        }
        var requests = 0
        assertThrows(AgentModelFailure::class.java) {
            loop(provider { _, _ -> requests++; throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture") }).run()
        }
        assertEquals(1, requests)
    }

    @Test
    fun hostedToolThenEmptyDoesNotReplayTheProvider() {
        var requests = 0
        assertThrows(AgentModelFailure::class.java) {
            loop(provider { _, emit ->
                requests++
                emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                response("")
            }).run()
        }
        assertEquals(1, requests)
    }

    @Test
    fun cancellationAndEventCallbackFailureDoNotReplayTheProvider() {
        var requests = 0
        val controller = AgentRunController()
        assertThrows(AgentRunCancelledException::class.java) {
            loop(provider { _, _ -> requests++; controller.cancel(); response("") }, controller = controller).run()
        }
        assertEquals(1, requests)
        requests = 0
        assertThrows(IOException::class.java) {
            loop(provider { _, emit -> requests++; emit(ProviderEvent.RequestStarted); response("") },
                eventSink = { if (it is AgentEvent.ProviderRequestStarted) throw IOException("callback fixture") }).run()
        }
        assertEquals(1, requests)
    }

    private fun loop(
        provider: AgentProviderClient,
        execute: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult = { error("No tool should run") },
        events: MutableList<AgentEvent> = mutableListOf(),
        controller: AgentRunController = AgentRunController(),
        eventSink: (AgentEvent) -> Unit = events::add,
    ) = AgentLoop(
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
            model = "fixture", systemPrompt = "", contextWindow = 128_000),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")),
        tools = JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "probe").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))),
        provider = provider,
        toolExecutor = AgentModelClient.ToolExecutor(execute),
        runController = controller,
        traceFormatter = AgentTraceFormatter(),
        onEvent = eventSink,
        modelRetry = AgentModelRetry { _, _ -> },
    )

    private fun response(content: String, finish: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", content).put("finish_reason", finish),
    )
    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = action(request, onEvent)
    }
}
