package io.github.mangi.eta.agent.model

/**
 * Local byte target for a compaction triggered by byte or both pressure. It is not a trigger,
 * not a provider capacity claim, and not persisted on the provider.
 */
internal data class AgentByteCompactionGoal(
    val trigger: String,
    val byteBudget: Int,
    val targetBytes: Long?,
    val sendLimitBytes: Long? = null,
) {
    val applies: Boolean get() = targetBytes != null

    fun status(afterBytes: Long): String = when {
        targetBytes == null -> "not_applicable"
        afterBytes <= targetBytes -> "met"
        else -> "not_met"
    }

    fun headroom(afterBytes: Long): Long = (sendLimitBytes ?: byteBudget.toLong()) - afterBytes

    companion object {
        private const val NUMERATOR = 3L
        private const val DENOMINATOR = 4L

        fun forTrigger(trigger: String, byteBudget: Int): AgentByteCompactionGoal {
            val applies = byteBudget > 0 && (trigger == "bytes" || trigger == "both")
            return AgentByteCompactionGoal(trigger, byteBudget,
                if (applies) byteBudget.toLong() * NUMERATOR / DENOMINATOR else null)
        }

        fun forPolicy(trigger: String, byteBudget: Int, config: AgentModelClient.ModelConfig): AgentByteCompactionGoal {
            if (config.compactionPolicyVersion < AgentRequestSendPolicy.VERSION) return forTrigger(trigger, byteBudget)
            val limit = config.requestSendLimitBytes?.takeIf { it > 0 }
            val growth = config.requestGrowthAllowanceBytes?.takeIf { it > 0 }
            if (trigger !in setOf("bytes", "both") || limit == null || growth == null) {
                return AgentByteCompactionGoal(trigger, byteBudget, null, limit)
            }
            // Overflow-safe floor(3*limit/4), plus the explicitly frozen growth reserve.
            val threeQuarters = limit / 4 * 3 + limit % 4 * 3 / 4
            val transportTarget = minOf(threeQuarters, (limit - growth).coerceAtLeast(1)).coerceAtLeast(1)
            val softTarget = byteBudget.takeIf { it > 0 }?.toLong()?.let { it * 3 / 4 }
            return AgentByteCompactionGoal(trigger, byteBudget,
                softTarget?.let { minOf(it, transportTarget) } ?: transportTarget, limit)
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
