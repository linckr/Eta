package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.ContextCompactionActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serializes heartbeat/provider callbacks; close prevents late events after the terminal notice. */
internal class AgentCompactionActivityReporter(
    private val controller: AgentRunController,
    private val clock: () -> Long,
    private val emit: (ContextCompactionActivity) -> Unit,
    heartbeatEnabled: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var sliceStarted = clock()
    private var state = ContextCompactionActivity("preparing")
    private val heartbeat = if (heartbeatEnabled) scheduler.scheduleAtFixedRate(::refresh, 2, 2, TimeUnit.SECONDS) else null

    internal fun refresh() = synchronized(lock) { publish() }

    fun stage(stage: String) = synchronized(lock) {
        if (!closed) { state = state.copy(stage = stage); publish() }
    }

    fun slice(number: Int) = synchronized(lock) {
        if (!closed) {
            if (state.currentSlice != number) sliceStarted = clock()
            state = ContextCompactionActivity("slicing", number)
            publish()
        }
    }

    fun request(refinement: Int, merging: Boolean = false) = synchronized(lock) {
        if (!closed) {
            state = state.copy(stage = if (refinement > 0) "refining" else "connecting",
                refinement = refinement, contentChars = 0, thinkingChars = 0, operation = if (merging) "merge" else "")
            publish()
        }
    }

    fun transport(phase: String) {
        when (phase) {
            "connect" -> stage("connecting")
            "request_body" -> stage("uploading")
            "response_headers" -> stage("waiting")
        }
    }

    fun event(event: ProviderEvent) = synchronized(lock) {
        if (closed || event !is ProviderEvent.BlockDelta || event.delta.isEmpty()) return@synchronized
        val next = when (event.kind) {
            AssistantBlockKind.TEXT -> "output"
            AssistantBlockKind.THINKING -> "thinking"
            AssistantBlockKind.TOOL_CALL -> return@synchronized
        }
        fun add(current: Long) = current + minOf(event.delta.length.toLong(), Long.MAX_VALUE - current)
        val transition = state.stage != next
        state = state.copy(stage = next,
            contentChars = if (next == "output") add(state.contentChars) else state.contentChars,
            thinkingChars = if (next == "thinking") add(state.thinkingChars) else state.thinkingChars)
        if (transition) publish()
    }

    private fun publish() {
        if (!closed && !controller.isCancelled) runCatching {
            emit(state.copy(sliceElapsedMs = (clock() - sliceStarted).coerceAtLeast(0)))
        }
    }

    override fun close() = synchronized(lock) { closed = true; heartbeat?.cancel(false); Unit }

    private companion object {
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "eta-compaction-progress").apply { isDaemon = true }
        }
    }
}
