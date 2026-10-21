package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.toSafeLogToken

/** Content-free boundaries before HTTP tracing begins; never skips the write-ahead commit. */
internal class AgentPreparationTrace(
    runId: String,
    private val component: String,
    private val logger: AgentLogger,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val id = runId.toSafeLogToken()
    private val started = nanoTime()
    enum class Stage { QUEUED, PERSISTED, CONFIG_READY, BOUND, PAYLOAD_READY, SENT, CHECKPOINT_READY, SKILLS_READY, CONTEXT_READY, MODEL_READY }
    fun mark(stage: Stage) {
        val elapsed = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        runCatching { logger.info("agent_request_preparation run_id=$id component=$component stage=${stage.name.lowercase()} elapsed_ms=$elapsed") }
    }
}
