package io.github.mangi.eta.agent.model

/** Local upload preference, independent of any provider's HTTP body limit. */
internal object AgentRequestBytePolicy {
    const val SMALL_DEFAULT = 512 * 1024
    const val LARGE_DEFAULT = 1024 * 1024
    const val LARGE_INPUT_THRESHOLD = 256_000

    /** Resolve afresh for the current model; never persist a derived default on its provider. */
    fun resolve(config: AgentModelClient.ModelConfig,
        inputBudget: Int? = AgentContextBudget.inputBudget(config)): Int =
        config.requestByteBudget?.takeIf { it >= 0 }
            ?: if (inputBudget != null && inputBudget >= LARGE_INPUT_THRESHOLD) LARGE_DEFAULT else SMALL_DEFAULT
}
