package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentCompactionFailureRecord
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class RoomCompactionFailureStoreTest {
    @Test fun fingerprints5000EntriesAndLargeSingleEntryWithBoundedEncodingBuffers() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication() as Context
        try {
            val store = RoomCompactionFailureStore(context)
            val entry = "事实🔬".repeat(256)
            val started = System.nanoTime()
            val many = store.fingerprint((0 until 5000).asSequence().map { "$it:$entry" })
            val manyMs = (System.nanoTime() - started) / 1_000_000
            val large = entry.repeat(4096)
            val largeStarted = System.nanoTime()
            val single = store.fingerprint(sequenceOf(large))
            val largeMs = (System.nanoTime() - largeStarted) / 1_000_000
            assertEquals(64, many.length)
            assertEquals(64, single.length)
            println("compaction_fingerprint_benchmark entries=5000 elapsed_ms=$manyMs single_chars=${large.length} single_elapsed_ms=$largeMs encoding_chunk_chars=4096")
        } finally { EtaDatabase.closeForTests() }
    }

    @Test fun recordAndPrivateFingerprintSurviveDatabaseReopenWithoutBodyStorage() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        try {
            val first = RoomCompactionFailureStore(context)
            val body = "测试🔬".repeat(100_000)
            val digest = first.fingerprint(sequenceOf("user", body))
            val record = AgentCompactionFailureRecord("conversation-route", "2:$digest", 4, 1,
                "CONTEXT_SUMMARY_INVALID", 123, 2_000_000, 12345)
            first.save(record)
            EtaDatabase.closeForTests()
            val second = RoomCompactionFailureStore(context)
            assertEquals(record, second.load(record.key))
            assertEquals(digest, second.fingerprint(sequenceOf("user", body)))
            assertNotEquals(digest, second.fingerprint(sequenceOf("user", body + "changed")))
            assertNotEquals(second.fingerprint(sequenceOf("ab", "c")), second.fingerprint(sequenceOf("a", "bc")))
            second.clear(record.key)
            assertNull(second.load(record.key))
        } finally {
            EtaDatabase.closeForTests()
            context.deleteDatabase("eta.db")
        }
    }
}
