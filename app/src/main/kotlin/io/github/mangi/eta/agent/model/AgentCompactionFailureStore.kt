package io.github.mangi.eta.agent.model

/** Content-free record. Stores run on the same IO thread as compaction and must not swallow IO errors. */
internal data class AgentCompactionFailureRecord(
    val key: String,
    val sourceDigest: String,
    val policyVersion: Int,
    val algorithmVersion: Int,
    val failureCode: String,
    val failedAtMs: Long,
    val requestBytes: Long,
    val estimatedTokens: Int,
)

internal interface AgentCompactionFailureStore {
    /** Installation-keyed HMAC, streamed per message; neither plaintext nor key is persisted here. */
    fun fingerprint(parts: Sequence<String>): String
    fun load(key: String): AgentCompactionFailureRecord?
    fun save(record: AgentCompactionFailureRecord)
    fun clear(key: String)
}
