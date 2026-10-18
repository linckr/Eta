package io.github.mangi.eta.agent.model

import org.json.JSONArray

/** Soft request estimate and exact UTF-8 body size, never a provider capacity assertion. */
internal object AgentRequestBudget {
    data class Budget(val estimatedTokens: Int, val requestBytes: Long)
    fun evaluate(config: AgentModelClient.ModelConfig, messages: JSONArray, tools: JSONArray,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT): Budget {
        val body = AgentProviderRequestJson.build(ProviderRequest(config, messages, tools, purpose = purpose))
        return Budget(
            AgentContextBudget.estimate(JSONArray().put(body), JSONArray()),
            body.toString().toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }
}
