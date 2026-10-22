package io.github.mangi.eta.agent.model

import org.json.JSONObject
import io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val contextRecoveryAllowed: Boolean = true,
    val diagnosticJson: String? = null,
    val receiveRetryAllowed: Boolean = false,
) : IllegalStateException(message, cause) {
    fun diagnostic(round: Int? = null, attempt: Int? = null): String? {
        val json = runCatching { JSONObject(diagnosticJson ?: "{}") }.getOrDefault(JSONObject())
        json.put("code", code).put("occurred_at_ms", System.currentTimeMillis())
        round?.let { json.put("round", it) }
        attempt?.let { json.put("attempt", it) }
        return ModelFailureDiagnostic.sanitize(json.toString())
    }

    fun withRequestTrace(traceId: String, phase: String, requestBytes: Long? = null, writtenBytes: Long? = null,
        writeCompleted: Boolean? = null, elapsedMs: Long? = null, streamMetrics: Map<String, Long> = emptyMap()): AgentModelFailure {
        val json = runCatching { JSONObject(diagnosticJson ?: "{}") }.getOrDefault(JSONObject())
        json.put("code", code).put("trace_id", traceId)
        json.put("client_phase", phase)
        if (!json.has("phase")) json.put("phase", phase)
        requestBytes?.let { json.put("request_bytes", it) }
        writtenBytes?.let { json.put("written_bytes", it) }
        writeCompleted?.let { json.put("write_completed", it) }
        elapsedMs?.let { json.put("elapsed_ms", it) }
        streamMetrics.forEach { (key, value) ->
            if (key in ModelFailureDiagnostic.streamNumberFields && value >= 0) json.put(key, value)
        }
        return copyFailure(diagnosticJson = ModelFailureDiagnostic.sanitize(json.toString()))
    }

    fun withRequestPurpose(purpose: String): AgentModelFailure {
        val json = runCatching { JSONObject(diagnosticJson ?: "{}") }.getOrDefault(JSONObject())
        json.put("purpose", purpose)
        return copyFailure(diagnosticJson = ModelFailureDiagnostic.sanitize(json.toString()))
    }

    fun copyFailure(retryable: Boolean = this.retryable, message: String = this.message.orEmpty(),
        contextRecoveryAllowed: Boolean = this.contextRecoveryAllowed, diagnosticJson: String? = this.diagnosticJson,
        receiveRetryAllowed: Boolean = this.receiveRetryAllowed): AgentModelFailure =
        AgentModelFailure(code, retryable, message, this, contextRecoveryAllowed, diagnosticJson, receiveRetryAllowed)

    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        fun http(status: Int, body: String, trustedGatewayPreUpstream: Boolean = false, traceId: String? = null): AgentModelFailure {
            val parsed = HttpFailureDiagnostics.parse(body)
            val trustedIngress = trustedGatewayPreUpstream && parsed.sourceIsGateway && parsed.phase == "request_body" &&
                parsed.upstreamStarted == false && traceId != null && ModelFailureDiagnostic.validTrace(traceId) && parsed.traceId == traceId
            val diagnostic = ModelFailureDiagnostic.sanitize(JSONObject().apply {
                put("http_status", status)
                put("trusted_gateway_ingress", trustedIngress)
                parsed.type?.let { put("server_type", it) }
                parsed.phase?.let { put("phase", it) }
                parsed.upstreamStarted?.let { put("upstream_started", it) }
                traceId?.let { put("trace_id", it) }
            }.toString())
            if (parsed.type in setOf("read_error", "request_body_timeout", "request_body_incomplete", "invalid_json") && status in setOf(400, 408)) {
                val safeRetry = trustedIngress && (parsed.type == "request_body_timeout" && status == 408 ||
                    parsed.type == "request_body_incomplete" && status == 400)
                val code = when (parsed.type) {
                    "request_body_timeout" -> "GATEWAY_REQUEST_BODY_TIMEOUT"
                    "request_body_incomplete" -> "GATEWAY_REQUEST_BODY_INCOMPLETE"
                    "invalid_json" -> "GATEWAY_INVALID_JSON"
                    else -> "GATEWAY_REQUEST_READ_FAILED"
                }
                val message = when (parsed.type) {
                    "request_body_timeout" -> "网关接收请求超时，请检查上传连接。"
                    "request_body_incomplete" -> "网关未完整收到请求，请检查上传连接。"
                    "invalid_json" -> "接口收到的请求 JSON 无效（HTTP $status）。"
                    else -> "接口读取请求失败（HTTP $status），尚不能确定上游是否已开始处理。"
                }
                return AgentModelFailure(code, safeRetry, message, diagnosticJson = diagnostic, receiveRetryAllowed = safeRetry)
            }
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。", diagnosticJson = diagnostic,
            )
            val permanent = isPermanent(error, body)
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                diagnosticJson = diagnostic,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> "模型接口拒绝请求（HTTP 400），请检查请求格式与接口兼容性。" + HttpFailureDiagnostics.describe(body)
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            val diagnostic = ModelFailureDiagnostic.sanitize(JSONObject().apply {
                put("phase", "response_body")
                put("server_type", error.optString("type"))
            }.toString())
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。", diagnosticJson = diagnostic,
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
                diagnosticJson = diagnostic,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException -> AgentModelFailure(
                "MODEL_TLS_FAILED", false, "模型连接安全协商失败，请检查接口证书与连接设置。", failure,
                contextRecoveryAllowed = false,
            )
            is ProtocolException -> AgentModelFailure(
                "MODEL_PROTOCOL_FAILED", false, "模型接口返回的 HTTP 协议不完整或无效。", failure,
                contextRecoveryAllowed = false,
            )
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
