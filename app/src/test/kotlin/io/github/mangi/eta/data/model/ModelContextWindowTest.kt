package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContextWindowTest {
    private val model = Model("fixture", "fixture", "fixture")

    @Test
    fun overridesDoNotInventCapacitySemanticsOrEraseAdvertisedInputLimit() {
        val automatic = model.copy(contextWindow = 128_000, contextWindowKind = "total", inputTokenLimit = 100_000)
        assertEquals("total", automatic.effectiveContextWindowKind)
        val overridden = automatic.copy(contextWindowOverride = 64_000)
        assertEquals("unknown", overridden.effectiveContextWindowKind)
        assertEquals(100_000, overridden.inputTokenLimit)
        assertEquals("total", overridden.copy(contextWindowOverride = null).effectiveContextWindowKind)
        assertEquals("unknown", automatic.copy(contextWindowKind = "unrecognized").effectiveContextWindowKind)
    }

    @Test
    fun olderSerializedModelsKeepUnknownSemantics() {
        val restored = kotlinx.serialization.json.Json.decodeFromString<Model>(
            """{"id":"old","modelId":"fixture","displayName":"fixture","contextWindow":128000,"contextWindowOverride":64000}""")
        assertEquals("unknown", restored.contextWindowKind)
        assertEquals("unknown", restored.effectiveContextWindowKind)
        assertNull(restored.inputTokenLimit)
        assertEquals(64_000, restored.effectiveContextWindow)
    }

    @Test
    fun explicitPositiveOverrideWinsOverCatalogWindow() {
        assertEquals(32_000, model.copy(contextWindow = 128_000, contextWindowOverride = 32_000).effectiveContextWindow)
    }

    @Test
    fun catalogWindowIsUsedWhenOverrideIsAbsentOrInvalid() {
        for (override in listOf(null, 0, -1)) {
            assertEquals(128_000, model.copy(contextWindow = 128_000, contextWindowOverride = override).effectiveContextWindow)
        }
    }

    @Test
    fun unknownOrInvalidCapacityStaysUnknown() {
        for (window in listOf(null, 0, -1)) {
            assertNull(model.copy(contextWindow = window).effectiveContextWindow)
        }
    }
}
