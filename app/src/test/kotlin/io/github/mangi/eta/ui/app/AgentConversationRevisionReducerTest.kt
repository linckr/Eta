package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test
    fun regeneratingTurnAfterSummaryKeepsCompactedContextAndOriginalJournalSeparate() {
        val state = compactedConversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-3")!!

        assertEquals(state.history.take(5), boundary.historyPrefix)
        assertEquals(state.journal.take(8), boundary.journalPrefix)
        assertTrue(boundary.historyPrefix.first().contextSummary)
        assertFalse(boundary.journalPrefix.any { it.contextSummary })
        assertFalse(boundary.contextWasCompacted)
        // 第二轮完整工具调用及结果都保留，第三轮的工具调用及结果都裁掉。
        assertEquals(listOf("assistant", "user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role })
    }

    @Test
    fun deletingTurnAfterSummaryDoesNotExpandModelHistory() {
        val state = compactedConversationState()

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "user-3")!!

        assertEquals(state.history.take(5), revised.history)
        assertEquals(state.journal.take(8), revised.journal)
        assertEquals(listOf("user-1", "assistant-1", "user-2", "assistant-2"),
            revised.messages.map { it.id })
    }

    @Test
    fun editingProtectedUserInvalidatesSummaryOfItsCompletedTools() {
        val state = compactedConversationState()
        val summary = state.history.first().copy(summaryThroughUserTurn = 2)

        val boundary = AgentConversationRevisionReducer.boundary(
            state.copy(history = listOf(summary) + state.history.drop(1)), "user-2",
        )!!

        assertEquals(state.journal.take(4), boundary.historyPrefix)
        assertEquals(boundary.historyPrefix, boundary.journalPrefix)
        assertFalse(boundary.historyPrefix.any { it.contextSummary })
        assertFalse(boundary.contextWasCompacted) // 原始 journal 足以恢复，不是不可恢复丢失。
    }

    @Test
    fun editingSummarizedTurnWithoutCoverageMetadataRebuildsFromJournal() {
        val state = compactedConversationState()
        val summary = state.history.first().copy(compactedUserTurns = 2, summaryThroughUserTurn = 0)

        val boundary = AgentConversationRevisionReducer.boundary(
            state.copy(history = listOf(summary) + state.history.drop(5)), "user-2",
        )!!

        assertEquals(state.journal.take(4), boundary.historyPrefix)
        assertEquals(boundary.historyPrefix, boundary.journalPrefix)
    }

    @Test
    fun firstTurnRevisionHasEmptyContextAndJournalDespiteExistingSummary() {
        val state = compactedConversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "user-1")!!
        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "user-1")!!

        assertTrue(boundary.historyPrefix.isEmpty())
        assertTrue(boundary.journalPrefix.isEmpty())
        assertTrue(revised.history.isEmpty())
        assertTrue(revised.journal.isEmpty())
        assertTrue(revised.messages.isEmpty())
    }

    @Test
    fun stableUserIdWinsOverTailAlignmentWhenDisplayHasUnconsumedSupplement() {
        val state = compactedConversationState()
        val withPending = state.copy(messages = state.messages + UserMessageUi("pending", "尚未消费"))

        val boundary = AgentConversationRevisionReducer.boundary(withPending, "user-3")!!

        assertEquals(state.history.take(5), boundary.historyPrefix)
        assertEquals(state.journal.take(8), boundary.journalPrefix)
        assertEquals(1, boundary.laterTurnCount)
    }

    @Test
    fun legacyMissingIdsKeepSummaryAndUseTailAlignment() {
        val state = compactedConversationState().let { state -> state.copy(
            history = state.history.map { it.copy(messageId = "") },
            journal = state.journal.map { it.copy(messageId = "") },
        ) }

        val boundary = AgentConversationRevisionReducer.boundary(state, "user-3")!!

        assertEquals(state.history.take(5), boundary.historyPrefix)
        assertEquals(state.journal.take(8), boundary.journalPrefix)
    }

    @Test
    fun repeatedRevisionDoesNotRestoreTrimmedTailOrDuplicateUsers() {
        val state = compactedConversationState()
        val first = AgentConversationRevisionReducer.boundary(state, "user-3")!!
        val user = state.journal[8]
        val answer = AgentModelClient.ConversationMessage("assistant", "重生成的第三答")
        val regenerated = state.copy(
            history = first.historyPrefix + user + answer,
            journal = first.journalPrefix + user + answer,
        )

        val second = AgentConversationRevisionReducer.boundary(regenerated, "assistant-3")!!

        assertEquals(first.historyPrefix, second.historyPrefix)
        assertEquals(first.journalPrefix, second.journalPrefix)
        assertEquals(3, regenerated.journal.count { it.role == "user" })
        assertTrue(second.historyPrefix.first().contextSummary)
    }

    @Test
    fun editingUserTurnCoveredBySummaryDropsStaleSummary() {
        val summary = AgentModelClient.ConversationMessage("assistant", "旧操作结果", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("old", "旧请求"), UserMessageUi("latest", "最新请求")),
            history = listOf(summary, AgentModelClient.ConversationMessage("user", "最新请求")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "latest")!!
        assertTrue(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        val next = state.copy(messages = state.messages + UserMessageUi("next", "后续请求"),
            history = state.history + AgentModelClient.ConversationMessage("user", "后续请求"))
        assertTrue(AgentConversationRevisionReducer.boundary(next, "next")!!.historyPrefix.contains(summary))
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "已压缩"),
                AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    private fun compactedConversationState(): AgentChatUiState {
        val users = (1..3).map { turn ->
            AgentModelClient.ConversationMessage("user", "第${turn}问", messageId = "user-$turn")
        }
        val journal = users.flatMapIndexed { index, user ->
            val id = "call-${index + 1}"
            listOf(user,
                AgentModelClient.ConversationMessage("assistant", toolCallsJson =
                    """[{"id":"$id","type":"function","function":{"name":"test","arguments":"{}"}}]"""),
                AgentModelClient.ConversationMessage("tool", "工具结果", toolCallId = id),
                AgentModelClient.ConversationMessage("assistant", "第${index + 1}答"))
        }
        val summary = AgentModelClient.ConversationMessage("assistant", "第一轮摘要",
            contextSummary = true, compactedUserTurns = 1, summaryThroughUserTurn = 1)
        return AgentChatUiState(
            messages = (1..3).flatMap { turn -> listOf(
                UserMessageUi("user-$turn", "第${turn}问"),
                AgentMessageUi("assistant-$turn", "第${turn}答"),
            ) },
            history = listOf(summary) + journal.drop(4), journal = journal,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            ThinkingMessageUi(id = "thinking-1", content = "思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "第二答"),
            UserMessageUi(id = "user-3", content = "第三问"),
            AgentMessageUi(id = "assistant-3", content = "第三答"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        ),
        input = "草稿",
        isStreaming = false,
        thinkingEnabled = false,
    )
}
