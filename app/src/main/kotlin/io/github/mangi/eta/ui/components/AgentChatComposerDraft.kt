package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/** Retained by the conversation owner, without recomposing the chat for each typed character. */
internal class AgentChatComposerDraft(initialText: String) {
    val textFieldState = TextFieldState(initialText)
    var supplementRequestId by mutableStateOf<String?>(null)
    var supplementRequestText by mutableStateOf<String?>(null)
    var supplementInFlight by mutableStateOf(false)

    fun beginSupplement(text: String): String {
        val id = supplementRequestId.takeIf { supplementRequestText == text }
            ?: UUID.randomUUID().toString().also {
                supplementRequestId = it
                supplementRequestText = text
            }
        supplementInFlight = true
        return id
    }

    fun finishSupplement(requestId: String, text: String, accepted: Boolean) {
        if (supplementRequestId != requestId) return
        supplementInFlight = false
        val unchanged = textFieldState.text.toString() == text
        if (accepted && unchanged) textFieldState.clearText()
        if (accepted || !unchanged) {
            supplementRequestId = null
            supplementRequestText = null
        }
    }
}

internal class AgentChatComposerDrafts {
    private val drafts = mutableMapOf<String?, AgentChatComposerDraft>()
    fun get(conversationId: String?, initialText: String): AgentChatComposerDraft =
        drafts.getOrPut(conversationId) { AgentChatComposerDraft(initialText) }
    fun remove(conversationId: String?) { drafts.remove(conversationId) }
}
