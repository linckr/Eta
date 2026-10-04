package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContextWindowTest {
    private val model = Model("fixture", "fixture", "fixture")

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
