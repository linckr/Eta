package io.github.mangi.eta.agent.model

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelHttpTraceTest {
    @Test fun usageEventWithoutInputDoesNotPretendToCalibrateInput() {
        val logs = mutableListOf<String>()
        val trace = ModelHttpTrace(EndpointKind.RESPONSES, ProviderRequestPurpose.CHAT, logs::add)
        trace.providerEvents {}(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(outputTokens = 42)))
        trace.terminalReceived()
        assertTrue(logs.last().contains("input_usage_status=partial"))
        trace.providerEvents {}(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(inputTokens = 123)))
        trace.terminalReceived()
        assertTrue(logs.last().contains("input_usage_status=available"))
        assertEquals(1L, trace.streamMetrics()["usable_input_usage_events"])
    }
    @Test fun responsesShapeSurvivesFailureWithoutRecordingItemData() {
        val trace = ModelHttpTrace(EndpointKind.RESPONSES, ProviderRequestPurpose.CHAT, log = {})
        val stream = trace.stream(java.io.ByteArrayInputStream(byteArrayOf()))
        ModelHttpTrace.responsesFunctionItem(stream)
        ModelHttpTrace.responsesTerminalItems(stream, org.json.JSONArray()
            .put(JSONObject().put("type", "message").put("content", "PRIVATE_BODY"))
            .put(JSONObject().put("type", "function_call").put("arguments", "PRIVATE_ARGS"))
            .put(JSONObject().put("type", "untrusted-name")))
        val failure = trace.failure(AgentModelFailure("MODEL_TOOL_PROTOCOL_LEAK", false, "blocked")) as AgentModelFailure
        val safe = JSONObject(failure.diagnostic()!!)
        assertEquals(1, safe.getInt("responses_stream_function_items"))
        assertEquals(1, safe.getInt("responses_terminal_function_items"))
        assertEquals(1, safe.getInt("responses_terminal_message_items"))
        assertEquals(1, safe.getInt("responses_terminal_other_items"))
        assertFalse(safe.toString().contains("PRIVATE"))
        assertFalse(safe.toString().contains("untrusted-name"))
    }

    @Test fun actualReadsAndParserDeltasAreCountedSeparatelyAndPersistOnFailure() {
        var time = 0L
        val logs = mutableListOf<String>()
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.COMPACTION, logs::add, { time })
        val payload = "private-body".toByteArray()
        val stream = trace.stream(java.io.ByteArrayInputStream(payload))
        time = 10_000_000L
        assertTrue(stream.read() >= 0)
        time = 20_000_000L
        val remaining = ByteArray(100)
        assertEquals(payload.size - 1, stream.read(remaining, 0, remaining.size))
        time = 25_000_000L
        assertEquals(-1, stream.read())
        ModelHttpTrace.firstSseEvent(stream)
        ModelHttpTrace.firstSseEvent(stream)
        val received = mutableListOf<ProviderEvent>()
        val observer = trace.providerEvents(received::add)
        val text = ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "private-text")
        time = 30_000_000L
        observer(text)
        time = 40_000_000L
        observer(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 1, "thought"))
        time = 50_000_000L
        observer(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 1, "more"))
        observer(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 2, "{}"))
        observer(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, ""))
        time = 60_000_000L
        val failure = trace.failure(java.net.SocketTimeoutException("private exception")) as AgentModelFailure
        val diagnostic = JSONObject(failure.diagnostic()!!)
        assertEquals(payload.size.toLong(), diagnostic.getLong("response_read_bytes"))
        assertEquals(10L, diagnostic.getLong("first_read_ms"))
        assertEquals(20L, diagnostic.getLong("last_read_ms"))
        assertEquals(2L, diagnostic.getLong("sse_events"))
        assertEquals(12L, diagnostic.getLong("content_chars"))
        assertEquals(1L, diagnostic.getLong("content_events"))
        assertEquals(30L, diagnostic.getLong("first_content_ms"))
        assertEquals(30L, diagnostic.getLong("last_content_ms"))
        assertEquals(11L, diagnostic.getLong("reasoning_chars"))
        assertEquals(2L, diagnostic.getLong("reasoning_events"))
        assertEquals(40L, diagnostic.getLong("first_reasoning_ms"))
        assertEquals(50L, diagnostic.getLong("last_reasoning_ms"))
        assertEquals(2L, diagnostic.getLong("tool_chars"))
        assertEquals("compaction", diagnostic.getString("purpose"))
        assertSame(text, received.first())
        assertFalse(diagnostic.toString().contains("private"))
        assertTrue(logs.all { !it.contains("private") && !it.contains("thought") })
    }

    @Test fun receivedBodyAndSseDoNotPretendThereWasContentOrReasoning() {
        val trace = ModelHttpTrace(EndpointKind.RESPONSES, ProviderRequestPurpose.COMPACTION, {})
        val stream = trace.stream(java.io.ByteArrayInputStream("data: heartbeat\n\n".toByteArray()))
        stream.readBytes()
        ModelHttpTrace.firstSseEvent(stream)
        val observer = trace.providerEvents {}
        observer(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(inputTokens = 123)))
        val diagnostic = JSONObject((trace.failure(java.net.SocketTimeoutException()) as AgentModelFailure).diagnostic()!!)
        assertTrue(diagnostic.getLong("response_read_bytes") > 0)
        assertEquals(1L, diagnostic.getLong("sse_events"))
        assertEquals(1L, diagnostic.getLong("usage_events"))
        assertEquals(0L, diagnostic.getLong("content_chars"))
        assertEquals(0L, diagnostic.getLong("reasoning_chars"))
        assertFalse(diagnostic.has("first_content_ms"))
        assertFalse(diagnostic.has("first_reasoning_ms"))
    }

    @Test fun compactionTransportDiagnosticPreservesPurposeWithoutInventingIngressTrust() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.COMPACTION, {})
        val failure = trace.failure(java.net.SocketTimeoutException("private detail")) as AgentModelFailure
        val diagnostic = JSONObject(failure.diagnostic()!!)
        assertEquals("compaction", diagnostic.getString("purpose"))
        assertFalse(failure.receiveRetryAllowed)
        assertFalse(diagnostic.optBoolean("trusted_gateway_ingress"))
        assertFalse(diagnostic.toString().contains("private"))
    }

    @Test fun localUploadReportsActualBytesAndKeepsSecretsOutOfTrace() {
        val logs = CopyOnWriteArrayList<String>()
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.CHAT, logs::add)
        val payload = "private prompt and transcript".toByteArray()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<Int> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                var last = 0
                var boundary = 0
                while (boundary < 4) {
                    val value = input.read()
                    check(value >= 0)
                    boundary = when {
                        value == 13 && boundary % 2 == 0 -> boundary + 1
                        value == 10 && last == 13 -> boundary + 1
                        else -> 0
                    }
                    last = value
                }
                var read = 0
                while (read < payload.size) {
                    check(input.read() >= 0)
                    read++
                }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                read
            }
        }
        try {
            val request = trace.attach(Request.Builder())
                .url("http://127.0.0.1:${server.localPort}/private-path")
                .header("Authorization", "Bearer private-key")
                .post(payload.toRequestBody()).build()
            val client = OkHttpClient.Builder().eventListenerFactory(ModelHttpTrace.factory)
                .callTimeout(5, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { assertEquals("OK", it.body.string()) }
            assertEquals(payload.size, received.get(5, TimeUnit.SECONDS))
            assertTrue(trace.writeCompleted)
            assertEquals(payload.size.toLong(), trace.writtenBytes)
            assertEquals(payload.size.toLong(), trace.requestBytes)
            assertTrue(logs.any { it.contains("event=write_completed") && it.contains("trace_id=${trace.traceId}") })
            assertFalse(logs.joinToString().contains("private"))
            assertFalse(logs.joinToString().contains("127.0.0.1"))
            // Reading the HTTP body is distinct from a parsed model terminal event.
            assertEquals("response_body", trace.phase)
            val tracedStream = trace.stream(java.io.ByteArrayInputStream(byteArrayOf()))
            ModelHttpTrace.firstSseEvent(tracedStream)
            ModelHttpTrace.firstSseEvent(tracedStream)
            assertEquals(1, logs.count { it.contains("event=first_sse_event") })
            trace.terminalReceived()
            assertEquals("complete", trace.phase)
            trace.listener.responseBodyEnd(client.newCall(request), 2)
            assertEquals("complete", trace.phase)
            val failure = trace.failure(java.io.IOException("secret transport detail")) as AgentModelFailure
            val diagnostic = JSONObject(failure.diagnosticJson!!)
            assertEquals(trace.traceId, diagnostic.getString("trace_id"))
            assertTrue(diagnostic.getBoolean("write_completed"))
            assertFalse(diagnostic.toString().contains("secret"))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun ingressRetryTrustRequiresContractAndThisAttemptsEchoedTrace() {
        val trace = ModelHttpTrace(EndpointKind.RESPONSES, ProviderRequestPurpose.CHAT, {})
        val request = trace.attach(Request.Builder().url("http://localhost/test")
            .header("X-Trace-ID", "untrusted-custom-value")).build()
        assertEquals(trace.traceId, request.header("X-Trace-ID"))
        fun response(marker: String?, id: String?, code: Int = 408): Response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .apply {
                marker?.let { header("X-WorkBuddy-Gateway-Error", it) }
                id?.let { header("X-Trace-ID", it) }
            }.build()
        assertTrue(trace.trustsReceiveFailure(response("request-receive-v1", trace.traceId), errorBodyComplete = true))
        assertFalse(trace.trustsReceiveFailure(response("request-receive-v1", trace.traceId)))
        assertFalse(trace.trustsReceiveFailure(response(null, trace.traceId), true))
        assertFalse(trace.trustsReceiveFailure(response("request-receive-v1", "other-attempt"), true))
        assertFalse(trace.trustsReceiveFailure(response("request-receive-v1", trace.traceId, 200), true))
        val another = ModelHttpTrace(EndpointKind.RESPONSES, ProviderRequestPurpose.CHAT, {})
        assertNotEquals(trace.traceId, another.traceId)
        assertFalse(another.trustsReceiveFailure(response("request-receive-v1", trace.traceId), true))
    }

    @Test fun explicitReceiveRetryUsesAnIsolatedPoolWithoutChangingSharedClient() {
        val shared = AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT)
        val retry = AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT, freshConnection = true)
        assertNotSame(shared.connectionPool, retry.connectionPool)
        assertSame(shared, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT))
        assertFalse(retry.retryOnConnectionFailure)
    }

    @Test fun completedEmptyAndProtocolFailuresKeepEachAttemptsTrace() {
        for (code in listOf("MODEL_EMPTY_RESPONSE", "MODEL_TOOL_PROTOCOL_LEAK")) {
            val traces = mutableListOf<ModelHttpTrace>()
            val events = mutableListOf<io.github.mangi.eta.agent.runtime.AgentEvent>()
            val provider = object : AgentProviderClient {
                override val id = "test"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
                override fun complete(request: ProviderRequest, runController: io.github.mangi.eta.agent.runtime.AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    val trace = ModelHttpTrace(capabilities.endpoint, request.purpose, {})
                    traces += trace
                    trace.terminalReceived()
                    return ProviderResponse(JSONObject().put("finish_reason", "stop"), trace)
                }
            }
            val thrown = assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, _ -> }.complete(
                    1, ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test", model = "test", systemPrompt = ""),
                        org.json.JSONArray(), org.json.JSONArray().put(JSONObject().put("name", "test"))),
                    provider, io.github.mangi.eta.agent.runtime.AgentRunController(), events::add,
                    { _, _ -> }, {}, { throw AgentModelFailure(code, false, "test validation failure") },
                )
            }
            assertEquals(2, traces.size)
            val retry = events.filterIsInstance<io.github.mangi.eta.agent.runtime.AgentEvent.ModelRetryScheduled>().single()
            assertEquals(traces[0].traceId, JSONObject(retry.diagnosticJson!!).getString("trace_id"))
            assertEquals(traces[1].traceId, JSONObject(thrown.diagnosticJson!!).getString("trace_id"))
            assertEquals("complete", JSONObject(thrown.diagnosticJson!!).getString("phase"))
        }
    }
}
