package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val journalPrefix: List<AgentModelClient.ConversationMessage>,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val userMessageIndices = state.messages.indices.filter { state.messages[it] is UserMessageUi }
        val targetUserOrdinal = userMessageIndices.indexOf(userMessageIndex)
        if (targetUserOrdinal < 0) return null

        val laterTurnCount = userMessageIndices.size - targetUserOrdinal - 1
        val source = state.journal.ifEmpty { state.history }
        val journalIndex = userIndex(source, userMessage.id, laterTurnCount)
        val originalPrefix = journalIndex?.let(source::take).orEmpty()
        val targetTurn = if (journalIndex != null) {
            originalPrefix.userTurnCount() + 1
        } else {
            (source.userTurnCount() - laterTurnCount).coerceAtLeast(1)
        }
        fun AgentModelClient.ConversationMessage.isInvalidSummary(): Boolean = contextSummary &&
            maxOf(summaryThroughUserTurn, compactedUserTurns) >= targetTurn

        val journalPrefix = originalPrefix.filterNot { it.isInvalidSummary() }
        val historyIndex = userIndex(state.history, userMessage.id, laterTurnCount)
        val contextPrefix = historyIndex?.let(state.history::take)
        // 摘要可能还包含受保护 user 的工具结果，覆盖范围不只 compactedUserTurns。
        val invalidatesSummary = (contextPrefix ?: state.history).any { it.isInvalidSummary() }
        val historyPrefix = if (contextPrefix != null && !invalidatesSummary) {
            contextPrefix
        } else {
            // 只有修改摘要覆盖内的轮次才恢复原始记录；摘要外修改不展开 journal。
            journalPrefix
        }
        val compacted = journalIndex == null || originalPrefix.any { it.isInvalidSummary() }

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = historyPrefix,
            journalPrefix = journalPrefix,
            laterTurnCount = laterTurnCount,
            contextWasCompacted = compacted,
        )
    }

    private fun List<AgentModelClient.ConversationMessage>.userTurnCount(): Int =
        sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }

    private fun userIndex(
        messages: List<AgentModelClient.ConversationMessage>,
        messageId: String,
        laterTurnCount: Int,
    ): Int? {
        messages.indexOfFirst { it.role == "user" && it.messageId == messageId }
            .takeIf { it >= 0 }?.let { return it }
        // 旧版本可能没有 messageId 或已丢失前缀，只能对已有用户记录做尾部对齐。
        val users = messages.indices.filter { messages[it].role == "user" }
        return users.getOrNull(users.size - laterTurnCount - 1)
    }

    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val boundary = boundary(state, targetMessageId) ?: return null
        return state.copy(
            messages = state.messages.take(boundary.userMessageIndex),
            history = boundary.historyPrefix,
            journal = boundary.journalPrefix,
            messageEdit = null,
        )
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }
}
