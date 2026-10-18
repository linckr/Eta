package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun memoryIsEnabledByDefault() {
        assertTrue(Settings().memoryEnabled)
    }

    @Test
    fun appearanceUsesBackwardCompatibleDefaults() {
        assertEquals(AppearanceSettings(), Settings().appearance)
    }

    @Test
    fun compactionSelectionDefaultsToFollowAndSerializesExplicitChoice() {
        org.junit.Assert.assertNull(Settings().compactionModelId)
        val legacy = kotlinx.serialization.json.Json.decodeFromString<Settings>("""{"selectedProviderId":"p","selectedModelId":"m","memoryEnabled":false}""")
        org.junit.Assert.assertNull(legacy.compactionModelId)
        assertEquals("m", legacy.selectedModelId)
        val selected = legacy.copy(compactionModelId = "summary-model")
        val json = kotlinx.serialization.json.Json
        assertEquals(selected, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), selected)))
    }
}
