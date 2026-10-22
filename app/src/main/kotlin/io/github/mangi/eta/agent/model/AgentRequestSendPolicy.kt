package io.github.mangi.eta.agent.model

/** A frozen local sending policy; observed successes are evidence, never a moving boundary. */
internal object AgentRequestSendPolicy {
    const val VERSION = 4
    const val MIN_GROWTH_BYTES = 256L * 1024
    const val MAX_GROWTH_BYTES = 512L * 1024

    fun growthAllowance(anchor: Long): Long {
        require(anchor > 0) { "A positive policy anchor is required" }
        return (anchor / 4 + if (anchor % 4 == 0L) 0 else 1)
            .coerceIn(MIN_GROWTH_BYTES, MAX_GROWTH_BYTES)
    }

    fun deriveSendLimit(anchor: Long, hardLimit: Long? = null): Long {
        val growth = growthAllowance(anchor)
        val limit = if (anchor > Long.MAX_VALUE - growth) Long.MAX_VALUE else anchor + growth
        return hardLimit?.also { require(it > 0) }?.let { minOf(limit, it) } ?: limit
    }

    fun guardExceeded(config: AgentModelClient.ModelConfig, requestBytes: Long): Boolean =
        config.compactionPolicyVersion >= VERSION && (
            config.requestSendLimitBytes?.let { requestBytes > it } == true ||
                config.requestHardLimitBytes?.let { requestBytes > it } == true)

    fun allowsOriginal(config: AgentModelClient.ModelConfig, requestBytes: Long?): Boolean {
        if (config.compactionPolicyVersion < VERSION) return true
        if (requestBytes == null || requestBytes < 0) return false
        val limit = config.requestSendLimitBytes?.takeIf { it > 0 } ?: return false
        val hard = config.requestHardLimitBytes
        return requestBytes <= limit && (hard == null || hard > 0 && requestBytes <= hard)
    }

    /** Last check after final serialization, including retries and recovery requests. */
    fun requireAllowed(config: AgentModelClient.ModelConfig, requestBytes: Long) {
        if (config.compactionPolicyVersion < VERSION) return
        val limit = config.requestSendLimitBytes
        val hard = config.requestHardLimitBytes
        val code: String
        val detail: String
        when {
            requestBytes < 0 || limit == null || limit <= 0 || hard != null && hard <= 0 -> {
                code = "MODEL_REQUEST_SEND_POLICY_INVALID"
                detail = "请求发送策略缺少有效边界，请检查提供商设置。"
            }
            hard != null && requestBytes > hard -> {
                code = "MODEL_REQUEST_HARD_LIMIT"
                detail = "请求超过已配置的硬限制，原历史已保留；请压缩上下文或调整配置。"
            }
            requestBytes > limit -> {
                code = "MODEL_REQUEST_SEND_LIMIT"
                detail = "请求超过冻结的自动发送边界，原历史已保留；请压缩上下文或调整配置。"
            }
            else -> return
        }
        throw AgentModelFailure(code, false, detail, contextRecoveryAllowed = false)
    }
}
