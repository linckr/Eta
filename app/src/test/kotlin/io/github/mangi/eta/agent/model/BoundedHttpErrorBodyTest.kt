package io.github.mangi.eta.agent.model

import java.io.IOException
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class BoundedHttpErrorBodyTest {
    private val typed = """{"error":{"source":"workbuddy_gateway","phase":"request_body","type":"request_body_timeout","upstream_started":false}}"""
    private fun response(trace: ModelHttpTrace, body: ResponseBody) = Response.Builder()
        .request(Request.Builder().url("http://localhost/fixture").build()).protocol(Protocol.HTTP_1_1)
        .code(408).message("fixture").body(body)
        .header("X-Trace-ID", trace.traceId).header("X-WorkBuddy-Gateway-Error", "request-receive-v1").build()

    @Test fun completeTypedFailureCanAuthorizeExactlyTheIngressRetryContract() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.CHAT, {})
        response(trace, typed.replace("false}}", "false,\"trace_id\":\"${trace.traceId}\"}}").toResponseBody()).use { response ->
            val error = BoundedHttpErrorBody.read(response)
            assertTrue(error.complete)
            assertTrue(AgentModelFailure.http(408, error.text, trace.trustsReceiveFailure(response, error.complete), trace.traceId).receiveRetryAllowed)
        }
    }

    @Test fun parseableOversizedPrefixNeverAuthorizesIngressRetryAndDecodeRemainsBounded() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.CHAT, {})
        response(trace, (typed + " ".repeat(20_000)).toResponseBody()).use { response ->
            val error = BoundedHttpErrorBody.read(response)
            assertFalse(error.complete)
            assertEquals(BoundedHttpErrorBody.MAX_BYTES.toInt(), error.text.toByteArray().size)
            assertFalse(AgentModelFailure.http(408, error.text, trace.trustsReceiveFailure(response, error.complete), trace.traceId).receiveRetryAllowed)
        }
    }

    @Test fun exactBoundaryStillRequiresRealEof() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.CHAT, {})
        val padded = typed + " ".repeat(BoundedHttpErrorBody.MAX_BYTES.toInt() - typed.length)
        response(trace, padded.toResponseBody()).use { assertTrue(BoundedHttpErrorBody.read(it).complete) }
        response(trace, (padded + " ").toResponseBody()).use { assertFalse(BoundedHttpErrorBody.read(it).complete) }
    }

    @Test fun brokenResponseAfterValidJsonDoesNotAuthorizeSafeRetry() {
        val trace = ModelHttpTrace(EndpointKind.CHAT_COMPLETIONS, ProviderRequestPurpose.CHAT, {})
        val source = object : Source {
            var prefix = Buffer().writeUtf8(typed)
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (prefix.size > 0) return prefix.read(sink, byteCount)
                throw IOException("fixture incomplete transport")
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        response(trace, body).use { response ->
            assertThrows(IOException::class.java) { BoundedHttpErrorBody.read(response) }
            assertFalse(trace.trustsReceiveFailure(response))
        }
    }
}
