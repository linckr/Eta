package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit = {},
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    private val requestPurpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val compactionSupport: AgentCompactionSupport = AgentCompactionSupport(),
    private val requestTools: () -> JSONArray = { JSONArray() },
    private val projectRequest: (JSONArray) -> JSONArray = { it },
) {
    private val contextWindow = AgentContextBudget.inputBudget(config, requestPurpose)
    private var inputTokens: Int? = null
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
    }

    /** One safe compaction attempt per outgoing round; the byte threshold is a local soft budget. */
    fun prepareForRequest(estimatedTokens: Int, requestBytes: Long? = null): Boolean {
        val window = AgentContextBudget.requirePositiveBudget(config, requestPurpose)
        val byteBudget = requestByteBudget()
        val tokenPressure = window != null && (estimatedTokens >= window * TRIGGER_RATIO ||
            inputTokens?.let { it >= window * TRIGGER_RATIO } == true)
        val bytePressure = byteBudget > 0 && requestBytes?.let { it > byteBudget.toLong() } == true
        val reason = budgetReason(tokenPressure, bytePressure)
        logBudget(estimatedTokens, requestBytes, window, byteBudget, reason)
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) return false
        if (!config.autoCompactionEnabled || !tokenPressure && !bytePressure) {
            // Preserve the checkpoint publication previously performed before each request.
            compact()
            return false
        }
        try {
            compact(force = true, reasonCode = when (reason) {
                "both" -> "CONTEXT_TOKEN_AND_BYTE_BUDGET"
                "bytes" -> "CONTEXT_REQUEST_BYTE_BUDGET"
                else -> "CONTEXT_TOKEN_BUDGET"
            })
            return true
        } catch (failure: AgentModelFailure) {
            // An estimate is not evidence of overflow. An unavailable/unhelpful summary
            // leaves the exact original request intact for the provider to decide.
            if (failure.code in SOFT_COMPACTION_FAILURES) {
                logSoftFallback("send_original", reason, failure, requestBytes, byteBudget)
                return false
            }
            // Byte-only pressure is a local upload preference, not evidence of provider
            // capacity overflow: a known summary/transport failure must not abort the user's
            // request. Cancellation escapes as AgentRunCancelledException, and unknown or
            // storage-related failures still propagate, so new failure codes fail closed.
            if (reason == "bytes" && byteFallbackEligible(failure)) {
                logSoftFallback("send_original_after_summary_failure", reason, failure, requestBytes, byteBudget)
                return false
            }
            throw failure
        }
    }

    fun reportRequestBudget(estimatedTokens: Int, requestBytes: Long) {
        val window = AgentContextBudget.inputBudget(config, requestPurpose)
        val byteBudget = requestByteBudget()
        logBudget(estimatedTokens, requestBytes, window, byteBudget, budgetReason(
            window != null && (estimatedTokens >= window * TRIGGER_RATIO ||
                inputTokens?.let { it >= window * TRIGGER_RATIO } == true),
            byteBudget > 0 && requestBytes > byteBudget.toLong(),
        ))
    }

    private fun budgetReason(tokens: Boolean, bytes: Boolean): String = when {
        tokens && bytes -> "both"
        tokens -> "token"
        bytes -> "bytes"
        else -> "none"
    }

    private fun requestByteBudget(): Int =
        config.requestByteBudget?.takeIf { it >= 0 } ?: DEFAULT_REQUEST_BYTE_BUDGET

    private fun logBudget(estimatedTokens: Int, requestBytes: Long?, window: Int?, byteBudget: Int, reason: String) {
        runCatching { AndroidAgentLogger.info("context_request_budget purpose=${requestPurpose.name.lowercase()} " +
            "estimated_tokens=$estimatedTokens input_budget=${window ?: "unknown"} " +
            "request_bytes=${requestBytes ?: "unknown"} byte_budget=$byteBudget reason=$reason") }
    }

    private fun logSoftFallback(action: String, trigger: String, failure: AgentModelFailure, requestBytes: Long?, byteBudget: Int) {
        runCatching { AndroidAgentLogger.warn("context_request_budget_fallback purpose=${requestPurpose.name.lowercase()} " +
            "trigger=$trigger action=$action code=${failure.code} " +
            "request_bytes=${requestBytes ?: "unknown"} byte_budget=$byteBudget") }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false, reasonCode: String = "") {

        val before = inputTokens
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            if (force) {
                throw AgentContextCompactor.signedAnthropicToolRoundFailure()
            }
            return
        }
        if (!force && (!config.autoCompactionEnabled || contextWindow == null || before == null ||
                before < contextWindow * TRIGGER_RATIO)
        ) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        val compactionStarted = compactionSupport.clock()
        val completedSlices = java.util.concurrent.atomic.AtomicInteger(0)
        val latestActivity = java.util.concurrent.atomic.AtomicReference<io.github.mangi.eta.agent.runtime.ContextCompactionActivity?>(null)
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before,
            reasonCode = reasonCode, activity = io.github.mangi.eta.agent.runtime.ContextCompactionActivity("preparing")))
        try {
            val compactor = AgentContextCompactor(config, provider, runController, roleplay = roleplay, onEvent = onEvent,
                support = compactionSupport, onProgress = { completed, _ ->
                    completedSlices.set(completed)
                    onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_PROGRESS, before,
                        completedSlices = completed, elapsedMs = (compactionSupport.clock() - compactionStarted).coerceAtLeast(0),
                        activity = latestActivity.get()))
                }, onActivity = { activity ->
                    latestActivity.set(activity)
                    onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_PROGRESS, before,
                        completedSlices = completedSlices.get(), elapsedMs = (compactionSupport.clock() - compactionStarted).coerceAtLeast(0),
                        activity = activity))
                }, candidateFits = { candidate ->
                    val budget = AgentRequestBudget.evaluate(config, projectRequest(candidate), requestTools(), requestPurpose)
                    val inputLimit = AgentContextBudget.inputBudget(config, requestPurpose)
                    (inputLimit == null || budget.estimatedTokens <= inputLimit * 0.80) &&
                        (requestByteBudget() == 0 || budget.requestBytes <= requestByteBudget())
                })
            val candidate = compactor.compact(
                messages, systemCount, sensitiveIds(),
            )
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            compactor.clearCompletedCheckpoint()
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before,
                reasonCode = reasonCode))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    private companion object {
        const val TRIGGER_RATIO = 0.85
        const val DEFAULT_REQUEST_BYTE_BUDGET = 512 * 1024

        /** Expected no-ops after an estimate: keep the original request without a failure. */
        val SOFT_COMPACTION_FAILURES = setOf("CONTEXT_NOT_COMPACTABLE", "CONTEXT_NO_REDUCTION")

        /**
         * Positive classification for the byte-only fallback: only failures that prove the summary
         * attempt itself failed (or could not be attempted) are safe to soften. Anything not listed
         * here — including a future failure code — propagates, so new errors fail closed.
         */
        val BYTE_FALLBACK_ELIGIBLE_CODES = setOf(
            "ANTHROPIC_THINKING_CONTEXT_LOCKED",
            "CONTEXT_SUMMARY_INPUT_BUDGET",
            "CONTEXT_SUMMARY_TIMEOUT",
            "CONTEXT_SUMMARY_TRANSPORT_FAILED",
            "CONTEXT_SUMMARY_INVALID",
            "CONTEXT_SUMMARY_TIME_BUDGET",
            "CONTEXT_SUMMARY_BUDGET",
            "CONTEXT_SUMMARY_MERGE_FAILED",
            "CONTEXT_SUMMARY_FAILED",
            "CONTEXT_OVERFLOW",
            "MODEL_TIMEOUT",
            "MODEL_CONNECTION_FAILED",
            "MODEL_TLS_FAILED",
            "MODEL_PROTOCOL_FAILED",
            "STREAM_INCOMPLETE",
        )

        fun byteFallbackEligible(failure: AgentModelFailure): Boolean =
            failure.code in BYTE_FALLBACK_ELIGIBLE_CODES ||
                failure.code.startsWith("HTTP_") ||
                failure.code.startsWith("GATEWAY_")
    }
}
