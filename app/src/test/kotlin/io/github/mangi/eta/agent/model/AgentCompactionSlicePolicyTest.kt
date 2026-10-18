package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionSlicePolicyTest {
    @Test fun legacyMinimumCheckpointIsAStartingPointAndSuccessfulSlicesGrow() {
        val policy = AgentCompactionSlicePolicy(64_000, 1024)
        policy.success(5000, 60_000)
        assertEquals(1024, policy.limit)
        policy.success(5000, 60_000)
        assertEquals(5120, policy.limit)
        repeat(40) { policy.success(5000, 60_000) }
        assertEquals(64_000, policy.limit)
        assertEquals(0, policy.successStreak)
    }

    @Test fun thinkingTimeoutAndTransientNetworkFailuresNeverReduceCapacity() {
        for (code in listOf("MODEL_TIMEOUT", "MODEL_CONNECTION_FAILED", "HTTP_429", "HTTP_502")) {
            val policy = AgentCompactionSlicePolicy(64_000, 16_000)
            policy.success(1000, 60_000)
            val failure = AgentModelFailure(code, true, "fixture", diagnosticJson =
                JSONObject().put("phase", "response_body").put("reasoning_chars", 20_000).toString())
            assertFalse(policy.failure(failure))
            assertEquals(16_000, policy.limit)
            assertEquals(0, policy.successStreak)
        }
    }

    @Test fun capacityEvidenceHalvesButUntrustedUploadDoesNot() {
        val policy = AgentCompactionSlicePolicy(64_000, 64_000)
        assertTrue(policy.failure(AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")))
        assertEquals(32_000, policy.limit)
        val json = JSONObject().put("phase", "request_body").put("upstream_started", false)
            .put("request_bytes", 256 * 1024).put("written_bytes", 100 * 1024).put("write_completed", false)
        fun failure() = AgentModelFailure("GATEWAY_REQUEST_BODY_TIMEOUT", true, "fixture", diagnosticJson = json.toString())
        assertFalse(policy.failure(failure()))
        json.put("trusted_gateway_ingress", true)
        assertTrue(policy.failure(failure()))
        assertEquals(16_000, policy.limit)
    }

    @Test fun slowSuccessDoesNotGrowAndSavedStreakCanContinue() {
        val policy = AgentCompactionSlicePolicy(64_000, 1024, 1)
        policy.success(90_000, 60_000)
        assertEquals(1024, policy.limit)
        assertEquals(0, policy.successStreak)
        val resumed = AgentCompactionSlicePolicy(64_000, 1024, 1)
        resumed.success(1000, 60_000)
        assertEquals(5120, resumed.limit)
    }
}
