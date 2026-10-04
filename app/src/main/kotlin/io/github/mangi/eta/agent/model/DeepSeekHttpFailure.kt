package io.github.mangi.eta.agent.model

import org.json.JSONObject
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.net.URI

/** Maps known backend validation categories to static text; never echoes a response body. */
internal object DeepSeekHttpFailure {
    fun appliesTo(config: AgentModelClient.ModelConfig): Boolean {
        val source = ProviderSourceRegistry.resolve(config.providerId, config.providerSourceType,
            config.baseUrl, config.providerType)
        val uri = runCatching { URI(config.baseUrl.trim()) }.getOrNull() ?: return false
        return source == ProviderSourceTypes.DEEPSEEK && uri.scheme.equals("https", true) &&
            uri.host.equals("api.deepseek.com", true) && (uri.port == -1 || uri.port == 443)
    }

    fun from(status: Int, body: String): AgentModelFailure {
        val fallback = AgentModelFailure.http(status, body)
        if (status != 400 || fallback.code != "HTTP_400" || fallback.message.orEmpty().contains("额度或计费受限")) return fallback
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            ?: return fallback
        val message = error.optString("message").lowercase()
        val category = when {
            message.contains("reasoning_content") && message.contains("passed back") ->
                "服务端要求完整回传思考内容（reasoning_content）。"
            error.optString("param") in knownParameters ->
                "服务端指出参数 ${error.optString("param")} 无效，请检查该参数配置。"
            else -> return fallback
        }
        return AgentModelFailure("HTTP_400", false, "DeepSeek 请求参数无效（HTTP 400）：$category")
    }

    private val knownParameters = setOf(
        "model", "messages", "tools", "tool_choice", "thinking", "reasoning_effort",
        "stream", "stream_options", "max_tokens", "response_format", "temperature", "top_p",
        "input", "instructions", "reasoning", "reasoning.effort", "max_output_tokens",
        "text", "text.format", "user", "top_logprobs",
    )
}
