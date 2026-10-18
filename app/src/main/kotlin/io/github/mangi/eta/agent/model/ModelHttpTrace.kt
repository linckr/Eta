package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** One attempt, attached to its own Call. No URL, header values or body content enter the trace. */
internal class ModelHttpTrace(
    private val protocol: EndpointKind,
    private val purpose: ProviderRequestPurpose,
    private val log: (String) -> Unit = { line -> AndroidAgentLogger.info(line) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val onPhase: ((String) -> Unit)? = null,
) {
    val traceId: String = UUID.randomUUID().toString()
    private val startedAt = nanoTime()
    @Volatile var phase: String = "connect"
        private set
    @Volatile var requestBytes: Long? = null
        private set
    @Volatile var writtenBytes: Long? = null
        private set
    @Volatile var writeCompleted: Boolean = false
        private set
    @Volatile private var responseBytes: Long? = null
    @Volatile private var status: Int? = null
    @Volatile private var terminalSeen: Boolean = false
    private val firstEventSeen = java.util.concurrent.atomic.AtomicBoolean(false)
    private val numericStreamMetrics = linkedMapOf<String, Long>("response_read_bytes" to 0L, "sse_events" to 0L,
        "usage_events" to 0L, "content_chars" to 0L, "reasoning_chars" to 0L, "tool_chars" to 0L,
        "content_events" to 0L, "reasoning_events" to 0L, "tool_events" to 0L)

    @Synchronized private fun addMetric(key: String, value: Long) {
        val current = numericStreamMetrics[key] ?: 0L
        numericStreamMetrics[key] = current + minOf(value.coerceAtLeast(0), Long.MAX_VALUE - current)
    }

    @Synchronized private fun markTimes(first: String, last: String) {
        val now = elapsedMs()
        numericStreamMetrics.putIfAbsent(first, now)
        numericStreamMetrics[last] = now
    }

    @Synchronized fun streamMetrics(): Map<String, Long> = numericStreamMetrics.toMap()

    private fun readObserved(bytes: Int) {
        if (bytes <= 0) return
        val first = synchronized(this) { !numericStreamMetrics.containsKey("first_read_ms") }
        addMetric("response_read_bytes", bytes.toLong())
        markTimes("first_read_ms", "last_read_ms")
        if (first) record("response_body", "first_body_bytes")
    }

    /** Counts parser deltas, not provider tokens; headers and SSE data alone do not imply model text. */
    fun providerEvents(delegate: (ProviderEvent) -> Unit): (ProviderEvent) -> Unit = { event ->
        if (event is ProviderEvent.BlockDelta && event.delta.isNotEmpty()) {
            val category = when (event.kind) {
                AssistantBlockKind.TEXT -> "content"
                AssistantBlockKind.THINKING -> "reasoning"
                AssistantBlockKind.TOOL_CALL -> "tool"
            }
            addMetric("${category}_chars", event.delta.length.toLong())
            addMetric("${category}_events", 1)
            if (category != "tool") markTimes("first_${category}_ms", "last_${category}_ms")
        } else if (event is ProviderEvent.Usage) addMetric("usage_events", 1)
        delegate(event)
    }

    fun attach(builder: Request.Builder): Request.Builder = builder
        .header("X-Trace-ID", traceId)
        .tag(ModelHttpTrace::class.java, this)

    /** Retry trust is a gateway contract plus this attempt's echoed identifier, never an error string. */
    fun trustsReceiveFailure(response: Response, errorBodyComplete: Boolean = false): Boolean = errorBodyComplete && !response.isSuccessful &&
        response.header("X-WorkBuddy-Gateway-Error") == "request-receive-v1" &&
        response.header("X-Trace-ID") == traceId

    fun terminalReceived() {
        terminalSeen = true
        record("complete", "provider_completed")
    }

    fun stream(input: InputStream): InputStream = TracedStream(input, this)

    private class TracedStream(input: InputStream, val trace: ModelHttpTrace) : FilterInputStream(input) {
        override fun read(): Int = `in`.read().also { if (it >= 0) trace.readObserved(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, length).also(trace::readObserved)
    }

    fun failure(failure: Throwable): Throwable {
        val classified = if (failure is Exception) AgentModelFailure.transport(failure) else null
        return classified?.withRequestTrace(traceId, phase, requestBytes, writtenBytes, writeCompleted, elapsedMs(), streamMetrics())
            ?.withRequestPurpose(purpose.name.lowercase())
            ?: failure
    }

    fun elapsedMs(): Long = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)

    private fun record(nextPhase: String, event: String, failureType: String? = null) {
        val recordedPhase = if (terminalSeen) "complete" else nextPhase
        phase = recordedPhase
        runCatching { onPhase?.invoke(recordedPhase) }
        val line = buildString {
            append("model_http_trace trace_id=").append(traceId)
            append(" protocol=").append(protocol.name.lowercase())
            append(" purpose=").append(purpose.name.lowercase())
            append(" phase=").append(recordedPhase).append(" event=").append(event)
            append(" elapsed_ms=").append(elapsedMs())
            requestBytes?.let { append(" request_bytes=").append(it) }
            writtenBytes?.let { append(" written_bytes=").append(it) }
            append(" write_completed=").append(writeCompleted)
            responseBytes?.let { append(" response_bytes=").append(it) }
            status?.let { append(" status=").append(it) }
            streamMetrics().forEach { (key, value) ->
                if (key in ModelFailureDiagnostic.streamNumberFields) append(" ").append(key).append("=").append(value)
            }
            failureType?.let { append(" failure_type=").append(it) }
        }
        // Observability must not change transport or callback success.
        runCatching { log(line) }
    }

    val listener: EventListener = object : EventListener() {
        override fun callStart(call: Call) {
            requestBytes = runCatching { call.request().body?.contentLength() }.getOrNull()?.takeIf { it >= 0 }
            record("connect", "call_started")
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            record("connect", "connect_started")
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            record("connect", "connect_completed")
        }

        override fun requestBodyStart(call: Call) {
            record("request_body", "write_started")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            writtenBytes = byteCount.coerceAtLeast(0)
            writeCompleted = true
            record("request_body", "write_completed")
            record("response_headers", "awaiting_response")
        }

        override fun requestFailed(call: Call, ioe: IOException) {
            record(phase, "request_failed", failureKind(ioe))
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
            record("response_headers", "headers_received")
        }

        override fun responseBodyStart(call: Call) {
            record("response_body", "read_started")
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBytes = byteCount.coerceAtLeast(0)
            record("response_body", "read_completed")
        }

        override fun responseFailed(call: Call, ioe: IOException) {
            record(phase, "response_failed", failureKind(ioe))
        }

        override fun callFailed(call: Call, ioe: IOException) {
            record(phase, "call_failed", failureKind(ioe))
        }

        override fun callEnd(call: Call) {
            record(phase, "http_call_completed")
        }
    }

    private fun failureKind(failure: IOException): String = when (failure) {
        is java.net.SocketTimeoutException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is java.net.ConnectException -> "connect"
        is javax.net.ssl.SSLException -> "tls"
        is java.net.ProtocolException -> "protocol"
        is java.io.InterruptedIOException -> "interrupted"
        else -> "io"
    }

    companion object {
        fun responsesFunctionItem(stream: InputStream) {
            (stream as? TracedStream)?.trace?.addMetric("responses_stream_function_items", 1)
        }

        fun responsesTerminalItems(stream: InputStream, output: org.json.JSONArray?) {
            val trace = (stream as? TracedStream)?.trace ?: return
            for (key in listOf("responses_stream_function_items", "responses_terminal_function_items", "responses_terminal_message_items", "responses_terminal_other_items"))
                trace.addMetric(key, 0)
            if (output == null) return
            for (i in 0 until output.length()) {
                val key = when (output.optJSONObject(i)?.optString("type")) {
                    "function_call" -> "responses_terminal_function_items"
                    "message" -> "responses_terminal_message_items"
                    else -> "responses_terminal_other_items"
                }
                trace.addMetric(key, 1)
            }
        }

        fun firstSseEvent(stream: InputStream) {
            val trace = (stream as? TracedStream)?.trace ?: return
            trace.addMetric("sse_events", 1)
            if (trace.firstEventSeen.compareAndSet(false, true)) {
                trace.record("response_body", "first_sse_event")
            }
        }

        val factory = EventListener.Factory { call ->
            call.request().tag(ModelHttpTrace::class.java)?.listener ?: EventListener.NONE
        }
    }
}
