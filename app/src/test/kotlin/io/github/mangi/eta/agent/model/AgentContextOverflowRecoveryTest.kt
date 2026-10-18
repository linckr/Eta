package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextOverflowRecoveryTest {
    @Test fun overflowCompactsOnceAndResumesTheUncommittedRound() {
        var chats = 0; var summaries = 0
        val requests = mutableListOf<String>()
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val result = loop(messages(), provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++; assertEquals(0, request.tools.length()); text("Earlier work completed.")
            } else {
                requests += request.messages.toString()
                if (++chats == 1) {
                    throw overflow()
                }
                assertTrue(request.messages.toString().contains("current goal"))
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "successful attempt"))
                text("done")
            }
        }, onSnapshot = snapshots::add).run()
        assertEquals(2, chats); assertEquals(1, summaries)
        assertEquals("done", result.content); assertEquals("successful attempt", result.reasoningContent)
        assertTrue(requests[1].length < requests[0].length); assertTrue(snapshots.isNotEmpty())
    }

    @Test fun overflowAfterVisibleThinkingStopsWithoutCompactionOrReplay() {
        var chats = 0
        var summaries = 0
        val source = messages()
        val original = source.toString()
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            loop(source, provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    text("must not be requested")
                } else {
                    chats++
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "partial thinking"))
                    throw overflow()
                }
            }, onSnapshot = snapshots::add).run()
        }
        assertEquals(1, chats)
        assertEquals(0, summaries)
        assertTrue(snapshots.isEmpty())
        assertFalse(failure.contextRecoveryAllowed)
        assertTrue(failure.message!!.contains("已收到部分响应"))
        assertEquals(original, source.toString())
    }

    @Test fun overflowAfterLocalToolNeverExecutesItsCommittedBatchAgain() {
        var chats = 0; var summaries = 0; var executions = 0
        val transcript = JSONArray()
        val result = loop(messages(), provider { request, _ ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++; assertTrue(request.messages.toString().contains("completed-once"))
                text("The probe completed-once; continue without repeating it.")
            } else when (++chats) { 1 -> toolResponse(); 2 -> throw overflow(); else -> text("done") }
        }, execute = { executions++; AgentModelClient.ToolResult("completed-once") }, transcript = transcript).run()
        assertEquals("done", result.content); assertEquals(3, chats); assertEquals(1, summaries); assertEquals(1, executions)
        assertEquals(1, (0 until transcript.length()).count { transcript.getJSONObject(it).optString("role") == "tool" })
    }

    @Test fun overflowRecoveryKeepsCurrentScreenshotWithoutRepeatingItsTool() {
        val screenshot = "data:image/png;base64,dGVzdA=="
        var chats = 0; var summaries = 0; var executions = 0
        val requests = mutableListOf<String>()
        val source = messages()
        val transcript = JSONArray()
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val result = loop(source, provider { request, _ ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                assertFalse(request.messages.toString().contains(screenshot))
                text("The observation completed-once; inspect the latest screenshot.")
            } else {
                requests += request.messages.toString()
                when (++chats) {
                    1 -> toolResponse()
                    2 -> {
                        assertTrue(request.messages.toString().contains(screenshot))
                        throw overflow()
                    }
                    else -> {
                        assertTrue(request.messages.toString().contains(screenshot))
                        text("done")
                    }
                }
            }
        }, execute = {
            executions++
            AgentModelClient.ToolResult("completed-once", images = listOf(
                AgentModelClient.ModelImage(screenshot, "image/png", 4, source = "screen"),
            ))
        }, transcript = transcript, onSnapshot = snapshots::add).run()
        assertEquals("done", result.content)
        assertEquals(3, chats); assertEquals(1, summaries); assertEquals(1, executions)
        assertFalse(requests.first().contains(screenshot))
        assertTrue(requests[1].contains(screenshot)); assertTrue(requests[2].contains(screenshot))
        assertFalse(source.toString().contains(screenshot))
        assertFalse(transcript.toString().contains(screenshot))
        assertTrue(snapshots.all { checkpoint -> checkpoint.messages.none { it.contentJson.contains(screenshot) } })
    }

    @Test fun secondOverflowStopsWithoutAnotherCompaction() {
        var chats = 0; var summaries = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            loop(messages(), provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) { summaries++; text("Earlier work completed.") }
                else { chats++; throw overflow() }
            }).run()
        }
        assertEquals("CONTEXT_OVERFLOW", failure.code); assertEquals(2, chats); assertEquals(1, summaries)
    }

    @Test fun summaryOrCheckpointFailureKeepsOriginalHistory() {
        for (checkpointFailure in listOf(false, true)) {
            val source = messages(); val original = source.toString(); var chats = 0; var checkpoints = 0
            assertThrows(IllegalStateException::class.java) {
                loop(source, provider { request, _ ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        if (checkpointFailure) text("Earlier work completed.") else text("", "length")
                    } else { chats++; throw overflow() }
                }, onSnapshot = { checkpoints++; error("checkpoint fixture") }).run()
            }
            assertEquals(1, chats); assertEquals(if (checkpointFailure) 1 else 0, checkpoints)
            assertEquals(original, source.toString())
        }
    }

    @Test fun cancellationPreventsCompactionOrReplay() {
        val source = messages(); val original = source.toString(); val controller = AgentRunController(); var requests = 0
        assertThrows(AgentRunCancelledException::class.java) {
            loop(source, provider { _, _ -> requests++; controller.cancel(); throw overflow() }, controller = controller).run()
        }
        assertEquals(1, requests); assertEquals(original, source.toString())
    }

    @Test fun hostedToolOrCallbackFailurePreventsOverflowReplay() {
        var requests = 0
        assertThrows(AgentModelFailure::class.java) {
            loop(messages(), provider { _, emit -> requests++; emit(ProviderEvent.HostedToolStarted("hosted", "web_search")); throw overflow() }).run()
        }
        assertEquals(1, requests); requests = 0
        assertThrows(AgentModelFailure::class.java) {
            loop(messages(), provider { _, emit -> requests++; emit(ProviderEvent.RequestStarted); text("done") },
                eventSink = { if (it is AgentEvent.ProviderRequestStarted) throw overflow() }).run()
        }
        assertEquals(1, requests)
    }

    @Test fun pendingSignedAnthropicToolResultCannotBeCompacted() {
        var chats = 0; var summaries = 0; var executions = 0
        val source = messages()
        val failure = assertThrows(AgentModelFailure::class.java) {
            loop(source, provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) { summaries++; error("Signed exchange cannot be summarized") }
                if (++chats == 1) toolResponse().also {
                    AnthropicEphemeralState.attachContentBlocks(it.assistantMessage, JSONArray()
                        .put(JSONObject().put("type", "thinking").put("thinking", "fixture").put("signature", "fixture-signature"))
                        .put(JSONObject().put("type", "tool_use").put("id", "probe-call").put("name", "probe").put("input", JSONObject())))
                } else throw overflow()
            }, execute = { executions++; AgentModelClient.ToolResult("completed-once") }).run()
        }
        assertEquals("ANTHROPIC_THINKING_CONTEXT_LOCKED", failure.code)
        assertEquals(2, chats); assertEquals(0, summaries); assertEquals(1, executions)
        assertTrue(AnthropicEphemeralState.hasPendingToolResponse(source))
    }

    private fun loop(source: JSONArray, provider: AgentProviderClient,
        execute: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult = { error("No tools expected") },
        controller: AgentRunController = AgentRunController(), onSnapshot: (AgentContextSnapshot) -> Unit = {},
        eventSink: (AgentEvent) -> Unit = {}, transcript: JSONArray = JSONArray(),
    ) = AgentLoop(
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed", contextWindow = 128_000),
        messages = source,
        tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "probe")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))),
        provider = provider, toolExecutor = AgentModelClient.ToolExecutor(execute), runController = controller,
        traceFormatter = AgentTraceFormatter(), onEvent = eventSink, modelRetry = AgentModelRetry { _, _ -> },
        transcript = transcript, systemCount = 1, onContextSnapshot = onSnapshot,
    )
    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
        .put(JSONObject().put("role", "user").put("content", "earlier goal"))
        .put(JSONObject().put("role", "assistant").put("content", "earlier results ".repeat(800)))
        .put(JSONObject().put("role", "user").put("content", "current goal"))
    private fun overflow() = AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture overflow")
    private fun text(content: String, finish: String = "stop") = ProviderResponse(JSONObject().put("role", "assistant").put("content", content).put("finish_reason", finish))
    private fun toolResponse() = ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject().put("id", "probe-call").put("type", "function")
            .put("function", JSONObject().put("name", "probe").put("arguments", "{}")))))
    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, onEvent)
    }
}
