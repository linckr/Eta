package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    private var protocolRecoverySession: String? = null
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
        validateResponse: (ProviderResponse) -> Unit = {},
    ): Result {
        var round = initialRound
        var retries = 0
        var emptyRetries = 0
        var protocolRetries = 0
        var receiveRetries = 0
        var freshConnection = false
        while (true) {
            controller.throwIfCancelled()
            val attemptRequest = if (protocolRecoverySession == request.sessionId &&
                provider.capabilities.endpoint != EndpointKind.ANTHROPIC_MESSAGES) AgentToolProtocolGuard.recoveryRequest(request) else request
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var visibleOutputStarted = false
            var callbackFailed = false
            var validationFailed = false
            var emptyResponseCanRetry = false
            var protocolResponseCanRetry = false
            try {
                if (attemptRequest.config.compactionPolicyVersion >= 4) {
                    val budget = AgentRequestBudget.evaluate(attemptRequest.config,
                        attemptRequest.messages, attemptRequest.tools, attemptRequest.purpose)
                    AgentRequestSendPolicy.requireAllowed(attemptRequest.config, budget.requestBytes)
                }
                val response = provider.complete(attemptRequest.copy(freshConnection = freshConnection), controller) { event ->
                    if (event is ProviderEvent.BlockDelta && event.delta.isNotEmpty() && event.kind != AssistantBlockKind.TOOL_CALL) visibleOutputStarted = true
                    if (event is ProviderEvent.BlockEnd && event.content.isNotEmpty() && event.kind != AssistantBlockKind.TOOL_CALL) visibleOutputStarted = true
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                controller.throwIfCancelled()
                emptyResponseCanRetry = request.purpose == ProviderRequestPurpose.CHAT &&
                    response.stopReason in setOf(AssistantStopReason.END_TURN, AssistantStopReason.UNKNOWN)
                protocolResponseCanRetry = request.purpose.allowsTools && request.tools.length() > 0 &&
                    response.stopReason in setOf(AssistantStopReason.END_TURN, AssistantStopReason.UNKNOWN, AssistantStopReason.TOOL_USE)
                try {
                    validateResponse(response)
                } catch (failure: Exception) {
                    validationFailed = true
                    throw if (failure is AgentModelFailure) response.httpTrace?.failure(failure) ?: failure else failure
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) {
                    if (failure is AgentModelFailure) throw failure.copyFailure(retryable = false, contextRecoveryAllowed = false)
                    throw failure
                }
                val classified = (AgentModelFailure.transport(failure) ?: throw failure).let { it.copyFailure(diagnosticJson = it.diagnostic(round)) }
                if (hostedToolStarted || visibleOutputStarted && !validationFailed) throw classified.copyFailure(
                    retryable = false, contextRecoveryAllowed = false,
                    message = classified.message.orEmpty() + if (hostedToolStarted)
                        " 已开始服务端工具处理，为避免重复执行已停止自动重试；此前工具结果和检查点已保留，可继续任务。"
                    else " 已收到部分响应，为避免重复执行已停止自动重试；此前工具结果和检查点已保留，可继续任务。",
                )
                if (classified.receiveRetryAllowed) {
                    if (receiveRetries >= 1) throw classified.copyFailure(retryable = false,
                        message = "${classified.message} 已重新连接重试一次仍失败，此前工具结果已保留。")
                    receiveRetries += 1
                    freshConnection = true
                    onEvent(AgentEvent.ModelRetryScheduled(round, receiveRetries, 1, BASE_DELAY_MS.toInt(), classified.code,
                        classified.diagnostic(round, receiveRetries)))
                    waitBeforeRetry(controller, BASE_DELAY_MS)
                    controller.throwIfCancelled()
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (validationFailed && classified.code == "MODEL_TOOL_PROTOCOL_LEAK") {
                    if (!protocolResponseCanRetry) throw classified
                    if (protocolRetries == 1) throw classified.copyFailure(retryable = false,
                        message = "${classified.message} 已重试一次仍异常，已停止并保留此前完成的工具结果。")
                    protocolRetries += 1
                    protocolRecoverySession = request.sessionId
                    onEvent(AgentEvent.ModelRetryScheduled(round, protocolRetries, 1,
                        BASE_DELAY_MS.toInt(), classified.code, classified.diagnostic(round, protocolRetries)))
                    waitBeforeRetry(controller, BASE_DELAY_MS)
                    controller.throwIfCancelled()
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (validationFailed) {
                    if (classified.code != "MODEL_EMPTY_RESPONSE" || !emptyResponseCanRetry) throw classified
                    if (emptyRetries == MAX_EMPTY_RETRIES) throw classified.copyFailure(retryable = false,
                        message = "${classified.message} 已重试 $MAX_EMPTY_RETRIES 次仍为空，已保留此前完成的工具结果。")
                    emptyRetries += 1
                    onEvent(AgentEvent.ModelRetryScheduled(round, emptyRetries, MAX_EMPTY_RETRIES,
                        BASE_DELAY_MS.toInt(), classified.code, classified.diagnostic(round, emptyRetries)))
                    waitBeforeRetry(controller, BASE_DELAY_MS)
                    controller.throwIfCancelled()
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw classified.copyFailure(retryable = false,
                        message = "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。")
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code, classified.diagnostic(round, retries)))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val MAX_EMPTY_RETRIES = 1
        private const val BASE_DELAY_MS = 2_000L
    }
}
