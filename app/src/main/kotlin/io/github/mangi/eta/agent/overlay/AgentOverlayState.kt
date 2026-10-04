package io.github.mangi.eta.agent.overlay

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentEvent

/** Agent 浮窗所处的阶段。 */
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * Agent 浮窗的渲染状态。由 [AgentEvent] 流累积而来，[AgentOverlayBubble] 直接消费。
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val status: AgentOverlayStatus = AgentOverlayStatus.Preparing,
    val detailText: String = "",
    /** Bounded tail of provider-exposed thinking text, separate from the answer. */
    val progressPreview: String = "",
) {
    val currentThinkingLine: String get() = if (phase == AgentOverlayPhase.RUNNING && status == AgentOverlayStatus.Reasoning)
        progressPreview.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        else ""

    companion object {
        val Initial = AgentOverlayState(status = AgentOverlayStatus.Received)
    }
}

/**
 * 将一个 [AgentEvent] 折叠进当前渲染状态。
 *
 * 文案逻辑只保留面向用户的一句话状态，
 * 工具名经 [toToolLabel] 中文化。详细 trace 流作为后续任务，此处不展开。
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState {
    val next = when (event) {
    is AgentEvent.ContextCompaction -> copy(status = AgentOverlayStatus.RequestingModel, detailText = event.displayMessage)
    is AgentEvent.RunStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.PreparingTools(event.toolCount),
        detailText = "",
    )

    is AgentEvent.RoundStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ReasoningRound(event.round),
    )

    is AgentEvent.ModelRetryScheduled -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = event.displayMessage,
    )

    is AgentEvent.ProviderRequestStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = "",
    )

    is AgentEvent.ProviderResponseStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ModelResponded,
    )

    is AgentEvent.AssistantBlockStart -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingAnswer,
        )

        AgentEvent.AssistantBlockKind.THINKING -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.Reasoning,
            progressPreview = "",
        )

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingToolArguments,
        )
    }

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.Reasoning,
            progressPreview = appendThinkingPreview(progressPreview, event.delta),
        )

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingToolArguments,
        )
    }

    is AgentEvent.AssistantBlockEnd -> this

    is AgentEvent.AssistantReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = if (event.toolNames.isEmpty()) AgentOverlayStatus.PreparingAnswer
        else AgentOverlayStatus.PlanningTools(event.toolNames),
    )

    is AgentEvent.UsageReceived -> this
    is AgentEvent.ModelIdentityObserved -> this

    is AgentEvent.UserSupplementReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.SupplementReceived,
        detailText = "",
    )

    is AgentEvent.ToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RunningTool(event.name),
    )

    is AgentEvent.ToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ToolCompleted(event.name),
    )

    is AgentEvent.HostedToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.HostedToolRunning(event.name),
    )

    is AgentEvent.HostedToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.HostedToolFinished(event.name, event.success),
    )

    is AgentEvent.ToolImagesAttached -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ImagesRead(event.imageCount),
    )

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        status = AgentOverlayStatus.ResultReady,
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        status = AgentOverlayStatus.RunFailed,
        detailText = event.reason,
    )
    }
    // Tool, round, retry and answer events supersede the previous thinking line.
    return if (next.status == AgentOverlayStatus.Reasoning) next else next.copy(progressPreview = "")
}

private const val MaxStreamingPreviewChars = 320
private const val MaxThinkingPreviewChars = 240

private fun appendThinkingPreview(previous: String, delta: String): String {
    val tail = (previous + delta.takeLast(MaxThinkingPreviewChars)).takeLast(MaxThinkingPreviewChars)
    // Truncation must not leave the low half of an emoji at the beginning.
    return if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail
}

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.GeneratingAnswer,
        detailText = nextPreview,
    )
}
