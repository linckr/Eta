package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayProgressPreviewTest {
    private fun thinking(text: String) = AgentEvent.AssistantBlockDelta(
        round = 1, kind = AgentEvent.AssistantBlockKind.THINKING,
        index = 0, deltaChars = text.length, delta = text,
    )

    @Test fun displaysLatestExposedThinkingLineAndPreservesAnswer() {
        val state = AgentOverlayState(detailText = "回答正文")
            .applyEvent(thinking("先检查页面\n现在寻找确认按钮"))
            .applyEvent(thinking("的位置"))
        assertEquals("现在寻找确认按钮的位置", state.currentThinkingLine)
        assertEquals("回答正文", state.detailText)
    }

    @Test fun toolSupersedesThinkingWithoutShowingArguments() {
        val state = AgentOverlayState().applyEvent(thinking("查找按钮"))
            .applyEvent(AgentEvent.ToolStarted(1, "call", "tap", "private-arguments"))
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)
        assertEquals("", state.progressPreview)
        assertEquals("", state.currentThinkingLine)
        assertFalse(state.detailText.contains("private-arguments"))
    }

    @Test fun newRoundAndProviderRequestClearPreviousThinking() {
        val thinkingState = AgentOverlayState().applyEvent(thinking("上一轮推理"))
        assertEquals("", thinkingState.applyEvent(AgentEvent.RoundStarted(2, 4)).progressPreview)
        assertEquals("", thinkingState.applyEvent(AgentEvent.ProviderRequestStarted(2)).progressPreview)
        val newBlock = thinkingState.applyEvent(AgentEvent.AssistantBlockStart(
            1, AgentEvent.AssistantBlockKind.THINKING, 1,
        ))
        assertEquals("", newBlock.currentThinkingLine)
    }

    @Test fun repeatedDeltasRemainBoundedAndRetainNewestFragment() {
        var state = AgentOverlayState()
        repeat(100) { state = state.applyEvent(thinking("旧内容".repeat(100))) }
        state = state.applyEvent(thinking("\n最新片段"))
        assertTrue(state.progressPreview.length <= 240)
        assertEquals("最新片段", state.currentThinkingLine)
        val emojiState = AgentOverlayState().applyEvent(thinking("😀" + "x".repeat(239)))
        assertFalse(emojiState.progressPreview.first().isLowSurrogate())
    }

    @Test fun providerWithoutThinkingUsesOnlySemanticStatus() {
        val state = AgentOverlayState().applyEvent(AgentEvent.AssistantBlockStart(
            1, AgentEvent.AssistantBlockKind.THINKING, 0,
        ))
        assertEquals(AgentOverlayStatus.Reasoning, state.status)
        assertEquals("", state.currentThinkingLine)
    }
}
