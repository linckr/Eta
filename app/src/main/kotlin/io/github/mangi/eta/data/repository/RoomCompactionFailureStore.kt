package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentCompactionFailureRecord
import io.github.mangi.eta.agent.model.AgentCompactionFailureStore
import io.github.mangi.eta.data.db.CompactionFailureEntity
import io.github.mangi.eta.data.db.EtaDatabase
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Runtime calls this synchronous adapter on its IO worker, never from the UI thread. */
internal class RoomCompactionFailureStore(context: Context) : AgentCompactionFailureStore {
    private val dao = EtaDatabase.get(context).compactionFailureDao()
    private val fingerprintKey: ByteArray = synchronized(RoomCompactionFailureStore::class.java) {
        val prefs = context.applicationContext.getSharedPreferences("compaction_failure_fingerprint", Context.MODE_PRIVATE)
        prefs.getString("key_v1", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            check(prefs.edit().putString("key_v1", Base64.encodeToString(it, Base64.NO_WRAP)).commit())
        }
    }

    override fun fingerprint(parts: Sequence<String>): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(fingerprintKey, "HmacSHA256"))
        parts.forEach { part ->
            // Bound temporary encoding memory even when one tool result is very large.
            mac.update(part.length.toString().toByteArray(Charsets.US_ASCII))
            mac.update(0.toByte())
            var offset = 0
            while (offset < part.length) {
                var end = minOf(offset + 4096, part.length)
                if (end < part.length && part[end - 1].isHighSurrogate() && part[end].isLowSurrogate()) end--
                mac.update(part.substring(offset, end).toByteArray(Charsets.UTF_8))
                offset = end
            }
        }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    override fun load(key: String): AgentCompactionFailureRecord? = dao.load(key)?.let {
        AgentCompactionFailureRecord(it.key, it.sourceDigest, it.policyVersion, it.algorithmVersion,
            it.failureCode, it.failedAtMs, it.requestBytes, it.estimatedTokens)
    }

    override fun save(record: AgentCompactionFailureRecord) {
        dao.save(CompactionFailureEntity(record.key, record.sourceDigest, record.policyVersion,
            record.algorithmVersion, record.failureCode, record.failedAtMs, record.requestBytes,
            record.estimatedTokens))
    }

    override fun clear(key: String) = dao.clear(key)
}
