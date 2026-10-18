package io.github.mangi.eta.data.repository

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class CompactionSettingsRepositoryTest {
    @Test fun independentSelectionRoundTripAndFollowPreserveChat() = runBlocking {
        val before = SettingsDataStore.settings()
        try {
            SettingsDataStore.updateSettings { it.copy(selectedProviderId = "chat-provider", selectedModelId = "chat-model", memoryEnabled = false) }
            SettingsDataStore.setCompactionModelId(" summary-model ")
            assertEquals("summary-model", SettingsDataStore.compactionModelIdFlow().first())
            val selected = SettingsDataStore.settings()
            assertEquals("chat-provider", selected.selectedProviderId)
            assertEquals("chat-model", selected.selectedModelId)
            assertFalse(selected.memoryEnabled)
            SettingsDataStore.setSelection("other-chat", "other-chat-model")
            assertEquals("summary-model", SettingsDataStore.settings().compactionModelId)
            for (follow in listOf(null, "", "  ")) {
                SettingsDataStore.setCompactionModelId(follow)
                assertNull(SettingsDataStore.settings().compactionModelId)
                assertEquals("other-chat-model", SettingsDataStore.settings().selectedModelId)
            }
        } finally { SettingsDataStore.updateSettings { before } }
    }

    @Test fun unrelatedSettingsUpdateKeepsExplicitCompactionId() = runBlocking {
        val before = SettingsDataStore.settings()
        try {
            SettingsDataStore.setCompactionModelId("summary-model")
            SettingsDataStore.updateSettings { it.copy(memoryEnabled = !it.memoryEnabled) }
            assertEquals("summary-model", SettingsDataStore.settings().compactionModelId)
        } finally { SettingsDataStore.updateSettings { before } }
    }
}
