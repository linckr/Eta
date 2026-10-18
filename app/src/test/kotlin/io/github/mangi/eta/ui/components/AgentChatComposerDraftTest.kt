package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentChatComposerDraftTest {
    @Test
    fun timeoutAndConversationSwitchKeepDraftAndRetryIdentity() {
        val drafts = AgentChatComposerDrafts()
        val original = drafts.get("main", "补充要求")
        val requestId = original.beginSupplement("补充要求")
        drafts.get("archive-breeno", "")
        // Timeout can arrive after the input window has been disposed.
        original.finishSupplement(requestId, "补充要求", accepted = false)

        val restored = drafts.get("main", "")
        assertSame(original, restored)
        assertEquals("补充要求", restored.textFieldState.text.toString())
        assertFalse(restored.supplementInFlight)
        assertEquals(requestId, restored.beginSupplement("补充要求"))
        assertEquals("", drafts.get("archive-breeno", "").textFieldState.text.toString())
    }

    @Test
    fun lateAcceptancePreservesNewTextAndInvalidatesPreviousRequest() {
        val draft = AgentChatComposerDraft("原要求")
        val requestId = draft.beginSupplement("原要求")
        draft.textFieldState.setTextAndPlaceCursorAtEnd("新要求")
        draft.finishSupplement(requestId, "原要求", accepted = true)

        assertEquals("新要求", draft.textFieldState.text.toString())
        assertNotEquals(requestId, draft.beginSupplement("新要求"))
    }

    @Test
    fun acceptedRetryClearsOnlyItsUnchangedDraft() {
        val draft = AgentChatComposerDraft("补充要求")
        val requestId = draft.beginSupplement("补充要求")
        draft.finishSupplement(requestId, "补充要求", accepted = false)
        assertEquals(requestId, draft.beginSupplement("补充要求"))
        draft.finishSupplement(requestId, "补充要求", accepted = true)

        assertFalse(draft.supplementInFlight)
        assertEquals("", draft.textFieldState.text.toString())
    }
}
