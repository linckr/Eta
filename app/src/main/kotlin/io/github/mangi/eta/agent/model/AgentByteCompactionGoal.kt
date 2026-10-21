package io.github.mangi.eta.agent.model

/**
 * Local byte target for a compaction triggered by byte or both pressure. It is not a trigger,
 * not a provider capacity claim, and not persisted on the provider.
 */
internal data class AgentByteCompactionGoal(
    val trigger: String,
    val byteBudget: Int,
    val targetBytes: Long?,
) {
    val applies: Boolean get() = targetBytes != null

    fun status(afterBytes: Long): String = when {
        targetBytes == null -> "not_applicable"
        afterBytes <= targetBytes -> "met"
        else -> "not_met"
    }

    fun headroom(afterBytes: Long): Long = byteBudget.toLong() - afterBytes

    companion object {
        private const val NUMERATOR = 3L
        private const val DENOMINATOR = 4L

        fun forTrigger(trigger: String, byteBudget: Int): AgentByteCompactionGoal {
            val applies = byteBudget > 0 && (trigger == "bytes" || trigger == "both")
            return AgentByteCompactionGoal(trigger, byteBudget,
                if (applies) byteBudget.toLong() * NUMERATOR / DENOMINATOR else null)
        }
    }
}

/** How the compactor treats one fully built early candidate. */
internal enum class AgentCandidateAction { TAKE_AND_STOP, RETAIN_AND_CONTINUE, REJECT }

internal data class AgentCandidateVerdict(
    val action: AgentCandidateAction,
    val requestBytes: Long = Long.MAX_VALUE,
) {
    companion object {
        fun stop(requestBytes: Long) = AgentCandidateVerdict(AgentCandidateAction.TAKE_AND_STOP, requestBytes)
        fun retain(requestBytes: Long) = AgentCandidateVerdict(AgentCandidateAction.RETAIN_AND_CONTINUE, requestBytes)
        fun reject(requestBytes: Long = Long.MAX_VALUE) = AgentCandidateVerdict(AgentCandidateAction.REJECT, requestBytes)
    }
}
