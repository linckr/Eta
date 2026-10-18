package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentToolProtocolGuardTest {
    @Test fun detectsBothCompleteWrappersAndSpacedMarkers() {
        for (wrapper in listOf("tool_calls", "function_calls")) {
            for (bar in listOf("|", "｜")) {
                val text = "< $bar DSML $bar $wrapper>\n< $bar DSML $bar invoke name=\"probe\">{}< / $bar DSML $bar invoke>\n< / $bar DSML $bar $wrapper>"
                assertEquals("MODEL_TOOL_PROTOCOL_LEAK", assertThrows(AgentModelFailure::class.java) {
                    AgentToolProtocolGuard.validate(response(text))
                }.code)
            }
        }
    }

    @Test fun permitsQuotedExamplesIncompleteTagsAndStructuredCalls() {
        for (text in listOf("```xml\n$leak\n```", "~~~xml\n$leak\n~~~",
                leak.lines().joinToString("\n") { "> $it" }, leak.lines().joinToString("\n") { "    $it" },
                "Explain <|DSML|tool_calls> as a token", "<|DSML|tool_calls>\n<|DSML|invoke name=\"probe\">{}")) {
            AgentToolProtocolGuard.validate(response(text))
        }
        AgentToolProtocolGuard.validate(toolResponse(leak))
    }

    @Test fun chatAndResponsesRetrySameUncommittedRequestAndDiscardFailedReasoning() {
        for (endpoint in listOf(EndpointKind.CHAT_COMPLETIONS, EndpointKind.RESPONSES)) {
            val requests = mutableListOf<String>()
            val events = mutableListOf<AgentEvent>()
            val result = loop(endpoint, events = events, action = { request, emit ->
                requests += request.messages.toString() + request.tools.toString()
                if (requests.size == 1) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "discarded"))
                    response(leak)
                } else {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "retained"))
                    response("done")
                }
            }).run()
            assertEquals("done", result.content)
            assertEquals("retained", result.reasoningContent)
            assertEquals(2, requests.size)
            assertFalse(requests[0].contains("工具调用格式恢复约束"))
            assertTrue(requests[1].contains("工具调用格式恢复约束"))
            assertFalse(requests[1].contains("<|DSML|"))
            assertEquals("MODEL_TOOL_PROTOCOL_LEAK", events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().reasonCode)
        }
    }

    @Test fun repeatedLeakFailsAfterOneRetryWithoutExecutingProse() {
        var requests = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            loop(action = { _, _ -> requests++; response(leak) }).run()
        }
        assertEquals(2, requests)
        assertEquals("MODEL_TOOL_PROTOCOL_LEAK", failure.code)
        assertFalse(failure.retryable)
    }

    @Test fun retryPreservesPriorResultsWithoutRunningLocalToolTwice() {
        var requests = 0
        var executions = 0
        val requestTexts = mutableListOf<String>()
        val result = loop(action = { request, _ ->
            requestTexts += request.messages.toString()
            when (++requests) { 1 -> toolResponse(); 2 -> response(leak); else -> response("done") }
        }, execute = { executions++; AgentModelClient.ToolResult("completed-before-leak") }).run()
        assertEquals("done", result.content)
        assertEquals(1, executions)
        assertTrue(requestTexts[2].contains("工具调用格式恢复约束"))
        assertTrue(requestTexts[2].contains("completed-before-leak"))
    }

    @Test fun outputLimitAndContentFilterDoNotRetry() {
        for (finish in listOf("length", "content_filter")) {
            var requests = 0
            assertThrows(AgentModelFailure::class.java) {
                loop(action = { _, _ -> requests++; response(leak, finish) }).run()
            }
            assertEquals(1, requests)
        }
    }

    @Test fun hostedToolAndCancellationDoNotReplay() {
        var requests = 0
        assertThrows(AgentModelFailure::class.java) {
            loop(action = { _, emit -> requests++; emit(ProviderEvent.HostedToolStarted("hosted", "web_search")); response(leak) }).run()
        }
        assertEquals(1, requests)
        requests = 0
        val controller = AgentRunController()
        assertThrows(AgentRunCancelledException::class.java) {
            loop(controller = controller, action = { _, _ -> requests++; controller.cancel(); response(leak) }).run()
        }
        assertEquals(1, requests)
    }

    @Test fun disabledToolsDoNotTreatProtocolDocumentationAsExecution() {
        var requests = 0
        val result = loop(tools = JSONArray(), action = { _, _ -> requests++; response(leak) }).run()
        assertEquals(leak, result.content)
        assertEquals(1, requests)
    }

    @Test fun recoveryHintIsTemporaryBudgetedAndKeepsOriginalToolResults() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixture")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "original"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "completed").put("content", "verified result"))
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "probe")))
        val original = messages.toString()
        val request = ProviderRequest(config, messages, tools)
        val hinted = AgentToolProtocolGuard.recoveryRequest(request)
        assertTrue(hinted.messages.getJSONObject(0).getString("content").contains("工具调用格式恢复约束"))
        assertEquals(original, messages.toString())
        assertEquals(messages.getJSONObject(1).toString(), hinted.messages.getJSONObject(1).toString())
        assertSame(tools, hinted.tools)
        assertEquals(hinted.messages.toString(), AgentToolProtocolGuard.recoveryRequest(hinted).messages.toString())
        val limited = request.copy(config = config.copy(requestByteBudget = 1))
        assertSame(limited, AgentToolProtocolGuard.recoveryRequest(limited))
        val summary = request.copy(purpose = ProviderRequestPurpose.COMPACTION)
        assertSame(summary, AgentToolProtocolGuard.recoveryRequest(summary))
    }

    @Test fun recoveredRunKeepsHintForLaterRoundsWithoutRepeatingCompletedTools() {
        val requests = mutableListOf<String>()
        var executions = 0
        val result = loop(action = { request, _ ->
            requests += request.messages.toString()
            when (requests.size) { 1 -> response(leak); 2 -> toolResponse(); else -> response("done") }
        }, execute = { executions++; AgentModelClient.ToolResult("verified-result") }).run()
        assertEquals("done", result.content)
        assertEquals(1, executions)
        assertFalse(requests[0].contains("工具调用格式恢复约束"))
        assertTrue(requests.drop(1).all { it.contains("工具调用格式恢复约束") && !it.contains("<|DSML|") })
        assertTrue(requests.last().contains("verified-result"))
    }

    private fun loop(
        endpoint: EndpointKind = EndpointKind.CHAT_COMPLETIONS,
        tools: JSONArray = JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "probe").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))),
        events: MutableList<AgentEvent> = mutableListOf(),
        controller: AgentRunController = AgentRunController(),
        execute: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult = { error("Text must never execute a tool") },
        action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse,
    ) = AgentLoop(
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "", contextWindow = 128_000),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")),
        tools = tools,
        provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(endpoint, true, true, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = action(request, onEvent)
        },
        toolExecutor = AgentModelClient.ToolExecutor(execute), runController = controller,
        traceFormatter = AgentTraceFormatter(), onEvent = events::add, modelRetry = AgentModelRetry { _, _ -> },
    )

    private fun response(content: String, finish: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", content).put("finish_reason", finish))
    private fun toolResponse(content: String = "") = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", content).put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "call-once").put("type", "function").put("function", JSONObject().put("name", "probe").put("arguments", "{}")))))
    private val leak = "<|DSML|tool_calls>\n<|DSML|invoke name=\"probe\">{}\n</|DSML|invoke>\n</|DSML|tool_calls>"
}
