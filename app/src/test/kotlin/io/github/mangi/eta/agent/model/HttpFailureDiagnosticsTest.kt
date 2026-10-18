package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpFailureDiagnosticsTest {
    @Test fun localCompactionFailureCodesSurviveSafeDiagnosticProjection() {
        for (code in listOf("CONTEXT_COMPACTION_MODEL_UNAVAILABLE", "CONTEXT_SUMMARY_INVALID", "CONTEXT_SUMMARY_BUDGET", "CONTEXT_SUMMARY_TIME_BUDGET", "CONTEXT_SUMMARY_CHECKPOINT_FAILED")) {
            val safe = io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize("""{"code":"$code","message":"private fixture"}""")!!
            assertEquals(code, JSONObject(safe).getString("code"))
            assertFalse(safe.contains("private"))
        }
    }
    @Test fun extractsNestedNumericCodeAndFixedFields() {
        val nested = JSONObject().put("code", 14305).put("message", "messages tool_call_id invalid")
        val body = JSONObject().put("error", JSONObject().put("code", "upstream_error").put("message", "upstream 400: $nested")).toString()
        assertEquals("（服务端编号 14305；涉及字段 tool_call_id, messages）", HttpFailureDiagnostics.describe(body))
    }
    @Test fun neverEchoesSecretsPromptOrArbitraryParameters() {
        val body = JSONObject().put("error", JSONObject().put("code", "secret-token").put("param", "private-parameter")
            .put("message", "Bearer private-key user prompt secret transcript")).toString()
        assertEquals("", HttpFailureDiagnostics.describe(body))
        assertFalse(AgentModelFailure.http(400, body).message!!.contains("private"))
    }
    @Test fun ignoresMalformedAndUnboundedCodes() {
        assertEquals("", HttpFailureDiagnostics.describe("not json"))
        assertEquals("", HttpFailureDiagnostics.describe("{\"code\":\"9999999999999\"}"))
    }
    @Test fun diagnosticDoesNotRetryGenericBadRequestOrOverrideContextOverflow() {
        val bad = AgentModelFailure.http(400, "{\"error\":{\"code\":123,\"param\":\"model\"}}")
        assertEquals("HTTP_400", bad.code)
        assertFalse(bad.retryable)
        assertTrue(bad.message!!.contains("服务端编号 123"))
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
    }
    @Test fun gatewayReceiveFailuresRequireExplicitTrustedPreUpstreamContract() {
        val error = JSONObject().put("type", "request_body_timeout").put("phase", "request_body")
            .put("source", "workbuddy_gateway").put("upstream_started", false)
            .put("trace_id", "12345678-1234-1234-1234-123456789abc")
        val body = JSONObject().put("error", error).toString()
        assertFalse(AgentModelFailure.http(408, body).retryable)
        assertTrue(AgentModelFailure.http(408, body, trustedGatewayPreUpstream = true, traceId = "12345678-1234-1234-1234-123456789abc").receiveRetryAllowed)
        error.put("upstream_started", "false")
        assertFalse(AgentModelFailure.http(408, JSONObject().put("error", error).toString(), true).retryable)
        error.put("upstream_started", false).put("type", "read_error")
        val legacy = AgentModelFailure.http(400, JSONObject().put("error", error).toString(), true)
        assertEquals("GATEWAY_REQUEST_READ_FAILED", legacy.code)
        assertFalse(legacy.retryable)
        assertFalse(legacy.message!!.contains("参数无效"))
        error.put("type", "invalid_json")
        assertFalse(AgentModelFailure.http(400, JSONObject().put("error", error).toString(), true).retryable)
    }
    @Test fun diagnosticProjectionDoesNotStoreProviderTextOrArbitraryTokens() {
        val diagnostic = io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
            """{"code":"MODEL_TIMEOUT","phase":"request_body","trace_id":"secret-key","server_type":"private-error","message":"secret transcript","written_bytes":42,"write_completed":true}""")!!
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("private"))
        assertFalse(diagnostic.contains("message"))
        assertEquals(42, JSONObject(diagnostic).getInt("written_bytes"))
        assertTrue(JSONObject(diagnostic).getBoolean("write_completed"))
    }
    @Test fun serverAndClientPhaseRemainDistinctAndIngressTrustIsExplicit() {
        val body = """{"error":{"type":"request_body_timeout","source":"workbuddy_gateway","phase":"request_body","upstream_started":false,"trace_id":"12345678-1234-1234-1234-123456789abc"}}"""
        val trusted = AgentModelFailure.http(408, body, true, "12345678-1234-1234-1234-123456789abc").withRequestTrace(
            "12345678-1234-1234-1234-123456789abc", "response_headers")
        val diagnostic = JSONObject(trusted.diagnostic()!!)
        assertEquals("request_body", diagnostic.getString("phase"))
        assertEquals("response_headers", diagnostic.getString("client_phase"))
        assertTrue(diagnostic.getBoolean("trusted_gateway_ingress"))
        assertFalse(JSONObject(AgentModelFailure.http(408, body).diagnostic()!!).getBoolean("trusted_gateway_ingress"))
        val wrongSource = body.replace("workbuddy_gateway", "untrusted")
        assertFalse(JSONObject(AgentModelFailure.http(408, wrongSource, true).diagnostic()!!).getBoolean("trusted_gateway_ingress"))
        val sanitized = JSONObject(io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
            """{"phase":"request_body","client_phase":"private-hostname","trusted_gateway_ingress":"true"}""")!!)
        assertFalse(sanitized.has("client_phase"))
        assertFalse(sanitized.has("trusted_gateway_ingress"))
    }
    @Test fun compactionSummaryCodesAndPurposeAreAllowedButArbitraryPurposeIsNot() {
        val safe = io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
            """{"code":"CONTEXT_SUMMARY_TRANSPORT_FAILED","purpose":"compaction","client_phase":"response_headers"}""")!!
        assertEquals("CONTEXT_SUMMARY_TRANSPORT_FAILED", JSONObject(safe).getString("code"))
        assertEquals("compaction", JSONObject(safe).getString("purpose"))
        val timeout = io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
            """{"code":"CONTEXT_SUMMARY_TIMEOUT","purpose":"private-provider-label"}""")!!
        assertEquals("CONTEXT_SUMMARY_TIMEOUT", JSONObject(timeout).getString("code"))
        assertFalse(JSONObject(timeout).has("purpose"))
    }
    @Test fun safeIngressRetryRequiresBodyEchoAndMatchingStatusType() {
        val traceId = "12345678-1234-1234-1234-123456789abc"
        val error = JSONObject().put("source", "workbuddy_gateway").put("phase", "request_body")
            .put("upstream_started", false).put("type", "request_body_timeout").put("trace_id", traceId)
        fun failure(status: Int) = AgentModelFailure.http(status, JSONObject().put("error", error).toString(), true, traceId)
        assertTrue(failure(408).receiveRetryAllowed)
        assertFalse(failure(400).receiveRetryAllowed)
        error.put("trace_id", "another-attempt")
        assertFalse(failure(408).receiveRetryAllowed)
        error.remove("trace_id")
        assertFalse(failure(408).receiveRetryAllowed)
        error.put("trace_id", traceId).put("type", "request_body_incomplete")
        assertTrue(failure(400).receiveRetryAllowed)
        assertFalse(failure(408).receiveRetryAllowed)
    }
    @Test fun streamDiagnosticAllowsOnlyBoundedNumericFields() {
        val failure = AgentModelFailure("MODEL_TIMEOUT", true, "fixture").withRequestTrace(
            "12345678-1234-1234-1234-123456789abc", "response_body", streamMetrics = mapOf(
                "response_read_bytes" to 123L, "content_chars" to 0L, "first_read_ms" to 1L,
                "last_read_ms" to -2L, "private-key" to 1234L))
        val diagnostic = JSONObject(failure.diagnostic()!!)
        assertEquals(123L, diagnostic.getLong("response_read_bytes"))
        assertEquals(0L, diagnostic.getLong("content_chars"))
        assertFalse(diagnostic.has("last_read_ms"))
        assertFalse(diagnostic.has("private-key"))
        val sanitized = JSONObject(io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
            """{"code":"MODEL_TIMEOUT","reasoning_chars":"private-text","content_events":2,"first_content_ms":-1}""")!!)
        assertFalse(sanitized.has("reasoning_chars"))
        assertFalse(sanitized.has("first_content_ms"))
        assertEquals(2L, sanitized.getLong("content_events"))
    }
}
