package io.github.mangi.eta.agent.model

/** Trigger, commit ceiling and early-stop target have different purposes. */
internal object AgentCompactionTokenPolicy {
    fun triggered(tokens: Int, inputBudget: Int): Boolean = tokens.toLong() * 100 >= inputBudget.toLong() * 85
    fun accepts(tokens: Int, inputBudget: Int?): Boolean =
        inputBudget == null || tokens.toLong() * 100 <= inputBudget.toLong() * 80
    fun meetsTarget(tokens: Int, inputBudget: Int?): Boolean =
        inputBudget == null || tokens.toLong() * 100 <= inputBudget.toLong() * 60
}
