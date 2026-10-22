package io.github.mangi.eta.data.model

import io.github.mangi.eta.data.db.ProviderWithModels
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RequestSendPolicyTest {
    @Test fun persistedEarlyBootstrapCannotCarryItsSamplingSizedBoundaryForward() {
        val source = provider.copy(requestByteBudget = 48 * 1024 * 1024)
        val oldPolicy = RequestSendPolicy.freeze(source, null, null).copy(
            bootstrapRuleVersion = 0, sendLimitBytes = 48L * 1024 * 1024 + 524_288,
            growthAllowanceBytes = 524_288)
        val persisted = Json.decodeFromString<RequestSendPolicy>(Json.encodeToString(oldPolicy))
        assertFalse(persisted.isValidFor(source))
        assertEquals(786_432L, RequestSendPolicy.freeze(source, null, null).sendLimitBytes)
        val genuineObservation = RequestSendPolicy.freeze(source, 1_782_914, null)
        assertTrue(genuineObservation.copy(bootstrapRuleVersion = 0).isValidFor(source))
        assertFalse(RequestSendPolicy.freeze(source, null, null).copy(sendLimitBytes = 2_000_000).isValidFor(source))
    }
    private val provider = OpenAiCompatibleProviderSetting("p", "Fixture", "https://example.invalid/v1", apiKey = "fixture")

    @Test fun optInFreezesObservedPlusBoundedGrowthRatherThanObservedBoundary() {
        assertEquals(0, provider.requestSendPolicy.version)
        val policy = RequestSendPolicy.freeze(provider, 1_782_914, null)
        assertEquals(445_729L, policy.growthAllowanceBytes)
        assertEquals(2_228_643L, policy.sendLimitBytes)
        assertTrue(policy.isValidFor(provider))
        assertEquals("user_confirmed_observed_success", policy.evidence)
        assertEquals(policy, Json.decodeFromString<RequestSendPolicy>(Json.encodeToString(policy)))
    }

    @Test fun bootstrapNeverClaimsObservedSuccessAndHardLimitIsSeparateFromSoftBudget() {
        val p = provider.copy(requestByteBudget = 1_000_000)
        val policy = RequestSendPolicy.freeze(p, null, 1_100_000)
        assertNull(policy.observedBytes)
        assertEquals("bootstrap_local_policy", policy.evidence)
        assertEquals(786_432L, policy.sendLimitBytes)
        assertEquals(262_144L, policy.growthAllowanceBytes)
        assertEquals(1_000_000, p.requestByteBudget)
        assertEquals(786_432L, RequestSendPolicy.freeze(provider, null, null).sendLimitBytes)
    }

    @Test fun bootstrapDoesNotInheritSamplingOrUserSoftBudgets() {
        for (budget in listOf(48 * 1024 * 1024, 2 * 1024 * 1024, 0, null)) {
            val p = provider.copy(requestByteBudget = budget)
            val policy = RequestSendPolicy.freeze(p, null, null)
            assertEquals(786_432L, policy.sendLimitBytes)
            assertEquals(262_144L, policy.growthAllowanceBytes)
            assertNull(policy.observedBytes)
            assertEquals(budget, p.requestByteBudget)
            assertEquals(700_000L, RequestSendPolicy.freeze(p, null, 700_000).sendLimitBytes)
            val observed = RequestSendPolicy.freeze(p, 1_782_914, null)
            assertEquals(2_228_643L, observed.sendLimitBytes)
            assertEquals(445_729L, observed.growthAllowanceBytes)
        }
    }

    @Test fun frozenLimitDoesNotRatchetWhenOnlySoftBudgetChanges() {
        val frozen = RequestSendPolicy.freeze(provider, null, null)
        val changed = provider.copy(requestByteBudget = 48 * 1024 * 1024, requestSendPolicy = frozen)
        assertTrue(frozen.isValidFor(changed))
        assertEquals(786_432L, changed.requestSendPolicy.sendLimitBytes)
        assertEquals(frozen, ProviderWithModels(changed.toEntity(), emptyList()).toDomain().requestSendPolicy)
    }

    @Test fun routeAuthOrOverridesInvalidateButDisplayChangesDoNot() {
        val policy = RequestSendPolicy.freeze(provider, 1_782_914, null)
        assertTrue(policy.isValidFor(provider.copy(name = "renamed")))
        assertFalse(policy.isValidFor(provider.copy(baseUrl = "https://different.invalid")))
        assertFalse(policy.isValidFor(provider.copy(apiKey = "changed")))
        assertFalse(policy.isValidFor(provider.copy(endpointMode = "responses")))
        assertFalse(policy.isValidFor(provider.copy(customHeaders = listOf(CustomHeader("X-Test", "1")))))
    }

    @Test fun entityRoundTripPreservesFrozenPolicyAndLegacySoftBudget() {
        val p = provider.copy(requestByteBudget = 1234,
            requestSendPolicy = RequestSendPolicy.freeze(provider, 1_782_914, 4_000_000))
        val restored = ProviderWithModels(p.toEntity(), emptyList()).toDomain()
        assertEquals(p.requestSendPolicy, restored.requestSendPolicy)
        assertEquals(1234, restored.requestByteBudget)
        assertEquals(0, ProviderWithModels(p.toEntity().copy(requestSendPolicyJson = "invalid"), emptyList()).toDomain().requestSendPolicy.version)
    }
}
