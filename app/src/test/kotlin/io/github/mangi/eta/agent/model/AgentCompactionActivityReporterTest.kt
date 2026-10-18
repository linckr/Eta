package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.ContextCompactionActivity
import io.github.mangi.eta.agent.runtime.ContextCompactionProgress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionActivityReporterTest {
    @Test fun mergeIdentitySurvivesTransportAndIsWhitelistedOnRestore() {
        val events = mutableListOf<ContextCompactionActivity>()
        AgentCompactionActivityReporter(AgentRunController(), { 0L }, events::add, heartbeatEnabled = false).use { reporter ->
            reporter.slice(8)
            reporter.request(0, merging = true)
            reporter.transport("response_headers")
            val restored = ContextCompactionActivity.decode(events.last().encode())!!
            assertTrue(restored.displayMessage.contains("合并区间"))
            assertTrue(restored.displayMessage.contains("等待模型"))
            assertEquals("", ContextCompactionActivity.decode(restored.encode().put("operation", "PRIVATE"))!!.operation)
        }
    }
    @Test fun waitingHasElapsedTimeWithoutClaimingCompletedSlicesAndClosedReporterCannotReviveIt() {
        var now = 0L
        val events = mutableListOf<ContextCompactionActivity>()
        val reporter = AgentCompactionActivityReporter(AgentRunController(), { now }, { events.add(it) }, heartbeatEnabled = false)
        try {
            reporter.slice(34)
            reporter.request(0)
            reporter.transport("response_headers")
            now = 5000
            reporter.refresh()
            assertEquals("waiting", events.last().stage)
            assertEquals(5000L, events.last().sliceElapsedMs)
            // A retry of the same data slice retains elapsed time, without advancing its number.
            reporter.slice(34)
            assertEquals(5000L, events.last().sliceElapsedMs)
            assertEquals(34, events.last().currentSlice)
        } finally { reporter.close() }
        val count = events.size
        reporter.refresh()
        reporter.transport("connect")
        assertEquals(count, events.size)
    }

    @Test fun thinkingAndTextExposeOnlyCountsAndRefinementKeepsItsIdentityDuringTransport() {
        val events = mutableListOf<ContextCompactionActivity>()
        AgentCompactionActivityReporter(AgentRunController(), { 0L }, { events.add(it) }, heartbeatEnabled = false).use { reporter ->
            reporter.slice(34)
            reporter.request(2)
            reporter.transport("request_body")
            reporter.event(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "PRIVATE_REASONING"))
            reporter.event(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "PRIVATE_SUMMARY"))
            val state = events.last()
            assertEquals("output", state.stage)
            assertEquals(2, state.refinement)
            assertEquals(17L, state.thinkingChars)
            assertEquals(15L, state.contentChars)
            val encoded = ContextCompactionProgress.encode(33, null, 0, null, state)!!
            assertEquals(33, JSONObject(encoded).getInt("completed_slices"))
            assertFalse(encoded.contains("PRIVATE"))
        }
    }

    @Test fun persistedActivityDropsUnknownStagesAndArbitraryStrings() {
        val safe = ContextCompactionProgress.sanitize("""{"completed_slices":33,"activity":{
            "stage":"waiting","current_slice":34,"thinking_chars":-1,
            "body":"PRIVATE_BODY","api_key":"PRIVATE_KEY"}}""")!!
        assertFalse(safe.contains("PRIVATE"))
        assertEquals(0L, JSONObject(safe).getJSONObject("activity").getLong("thinking_chars"))
        assertNull(ContextCompactionProgress.sanitize("""{"activity":{"stage":"PRIVATE_STAGE"}}"""))
    }
}
