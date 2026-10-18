package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import org.json.JSONObject

/** Exactly the body used by transport and budgeting; does not read auth or send requests. */
internal object AgentProviderRequestJson {
    fun build(request: ProviderRequest): JSONObject {
        val config = request.effectiveConfig
        val tools = request.effectiveTools
        val body = when {
            config.providerType == ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider.buildRequestJson(config, request.messages, tools)
            config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider.buildRequestJson(config, request.messages, tools)
            else -> OpenAiChatCompletionsProvider.buildRequestJson(config, request.messages, tools, request.purpose)
        }
        AgentNativeCompaction.validate(config, body, when {
            config.providerType == ProviderTypes.ANTHROPIC -> EndpointKind.ANTHROPIC_MESSAGES
            config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES -> EndpointKind.RESPONSES
            else -> EndpointKind.CHAT_COMPLETIONS
        })
        return body
    }
}
