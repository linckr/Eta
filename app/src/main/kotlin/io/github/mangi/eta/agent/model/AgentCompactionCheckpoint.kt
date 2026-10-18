package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal data class AgentCompactionCheckpoint(
    val summary: String, val cursor: AgentSummarySlices.Cursor, val completedSlices: Int, val sliceLimit: Int,
    val successStreak: Int = 0,
) {
    fun valid() = summary.isNotBlank() && summary != "null" && summary.length <= 12_000 &&
        completedSlices in 1..1_000_000 && sliceLimit in 1_024..64_000 && cursor.batch >= 0 && cursor.message >= -1 &&
        cursor.field >= 0 && cursor.character >= 0 && successStreak in 0..1
}

internal interface AgentCompactionCheckpointStore {
    fun read(key: String): AgentCompactionCheckpoint?
    fun save(key: String, checkpoint: AgentCompactionCheckpoint)
    fun clear(key: String)
    object None : AgentCompactionCheckpointStore {
        override fun read(key: String): AgentCompactionCheckpoint? = null
        override fun save(key: String, checkpoint: AgentCompactionCheckpoint) {}
        override fun clear(key: String) {}
    }
}

/** Files belong to Eta private storage. No source history, API credentials or HTTP data are written. */
internal class FileAgentCompactionCheckpointStore(private val directory: File) : AgentCompactionCheckpointStore {
    override fun read(key: String): AgentCompactionCheckpoint? = synchronized(lock) {
        cleanup()
        val file = target(key)
        if (!file.isFile) return@synchronized null
        if (file.length() > MAX_BYTES) { file.delete(); return@synchronized null }
        val result = runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            require(json.getString("key") == key && json.getInt("version") == 1)
            val cursor = json.getJSONObject("cursor")
            AgentCompactionCheckpoint(json.getString("summary"), AgentSummarySlices.Cursor(
                cursor.getInt("batch"), cursor.getInt("message"), cursor.getInt("field"), cursor.getInt("character")),
                json.getInt("completed_slices"), json.getInt("slice_limit"), json.optInt("success_streak", 0)).also { require(it.valid()) }
        }.getOrNull()
        if (result == null) file.delete()
        result
    }

    override fun save(key: String, checkpoint: AgentCompactionCheckpoint) = synchronized(lock) {
        require(checkpoint.valid())
        require(directory.isDirectory || directory.mkdirs())
        ownerOnly(directory, executable = true)
        val json = JSONObject().put("key", key).put("version", 1).put("summary", checkpoint.summary)
            .put("completed_slices", checkpoint.completedSlices).put("slice_limit", checkpoint.sliceLimit)
            .put("success_streak", checkpoint.successStreak)
            .put("cursor", JSONObject().put("batch", checkpoint.cursor.batch).put("message", checkpoint.cursor.message)
                .put("field", checkpoint.cursor.field).put("character", checkpoint.cursor.character))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream -> ownerOnly(temporary); stream.write(bytes); stream.fd.sync() }
            Files.move(temporary.toPath(), target(key).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            cleanup()
        } finally { temporary.delete() }
    }

    override fun clear(key: String) { synchronized(lock) { target(key).delete() } }
    private fun target(key: String): File { require(key.matches(Regex("[a-f0-9]{64}"))); return File(directory, "$key.json") }
    private fun ownerOnly(file: File, executable: Boolean = false) {
        file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
        if (executable) file.setExecutable(true, true)
    }
    private fun cleanup() {
        val now = System.currentTimeMillis()
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        files.filter { now - it.lastModified() > MAX_AGE_MS || it.length() > MAX_BYTES }.forEach { it.delete() }
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)?.forEach { it.delete() }
    }
    private companion object {
        val lock = Any()
        const val MAX_BYTES = 256 * 1024
        const val MAX_FILES = 16
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

internal fun interface AgentCompactionCallLimiter {
    fun complete(parent: AgentRunController, remainingMs: Long, call: (AgentRunController) -> ProviderResponse): ProviderResponse
}

internal data class AgentCompactionSupport(
    val checkpoints: AgentCompactionCheckpointStore = AgentCompactionCheckpointStore.None,
    val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val budgetMs: Long = 10 * 60 * 1000L,
    val limiter: AgentCompactionCallLimiter = DeadlineLimiter,
    val callBudgetMs: Long = 120_000L,
    val hierarchical: Boolean = false,
    val treeStore: AgentCompactionTreeStore = AgentCompactionTreeStore.None,
)

/** A budget cancels only the current summary call, never the caller's run controller. */
private object DeadlineLimiter : AgentCompactionCallLimiter {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-summary-deadline").apply { isDaemon = true }
    }
    override fun complete(parent: AgentRunController, remainingMs: Long, call: (AgentRunController) -> ProviderResponse): ProviderResponse {
        parent.throwIfCancelled()
        if (remainingMs <= 0) throw AgentCompactionDeadlineException()
        val child = AgentRunController()
        val binding = parent.register(child::cancel)
        val expired = AtomicBoolean(false)
        val deadline = scheduler.schedule({ expired.set(true); child.cancel() }, remainingMs, TimeUnit.MILLISECONDS)
        try {
            val result = call(child)
            parent.throwIfCancelled()
            if (expired.get()) throw AgentCompactionDeadlineException()
            return result
        } catch (error: Exception) {
            parent.throwIfCancelled()
            if (expired.get()) throw AgentCompactionDeadlineException(error)
            throw error
        } finally { deadline.cancel(false); binding.close() }
    }
}

internal class AgentCompactionDeadlineException(cause: Throwable? = null) : RuntimeException("Summary time budget exhausted", cause)
