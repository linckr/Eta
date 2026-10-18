package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import org.junit.Assert.*
import org.junit.Test

class CompactionModelPickerTest {
    private val providers = listOf(
        CustomProviderSetting("enabled", "Enabled", "http://example.invalid", models = listOf(
            Model("valid", "fixture", "Valid"), Model("disabled-model", "fixture", "Disabled", isEnabled = false),
        )),
        CustomProviderSetting("disabled", "Disabled", "http://example.invalid", isEnabled = false,
            models = listOf(Model("provider-disabled-model", "fixture", "Disabled"))),
    )

    @Test fun showsOnlyEnabledProvidersAndModelsIncludingKeylessLocalProviders() {
        val state = projectCompactionModelPicker(providers, "valid")
        assertEquals(listOf("enabled"), state.providerGroups.map { it.providerId })
        assertEquals(listOf("valid"), state.providerGroups.single().models.map { it.id })
        assertEquals("valid", state.selectedModel!!.id)
        assertTrue(canSelectCompactionModel(state, "valid"))
    }

    @Test fun invalidOrDisabledSelectionNeverSilentlyFallsBack() {
        for (id in listOf("missing", "disabled-model", "provider-disabled-model")) {
            val state = projectCompactionModelPicker(providers, id)
            assertNull(state.selectedModel)
            assertFalse(canSelectCompactionModel(state, id))
            assertTrue(canSelectCompactionModel(state, null))
        }
    }

    @Test fun followCurrentIsAvailableWhenNoModelsAreEnabled() {
        val state = projectCompactionModelPicker(emptyList(), null)
        assertTrue(state.providerGroups.isEmpty())
        assertNull(state.selectedModel)
        assertTrue(canSelectCompactionModel(state, null))
        assertFalse(canSelectCompactionModel(state, "missing"))
    }
}
