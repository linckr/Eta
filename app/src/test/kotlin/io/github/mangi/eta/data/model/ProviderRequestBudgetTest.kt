package io.github.mangi.eta.data.model

import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderWithModels
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.provider.BuiltinProviders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestBudgetTest {
    private fun providers(budget: Int?) = listOf<ProviderSetting>(
        OpenAiCompatibleProviderSetting("openai", "Fixture", "https://example.invalid", apiKey = "fixture", requestByteBudget = budget),
        AnthropicProviderSetting("anthropic", "Fixture", "https://example.invalid", apiKey = "fixture", requestByteBudget = budget),
        CustomProviderSetting("custom", "Fixture", "https://example.invalid", apiKey = "fixture", requestByteBudget = budget),
    )

    @Test fun serializationAndEntityMappingKeepDefaultDisabledAndExactBytes() {
        for (budget in listOf(null, 0, 1, 512 * 1024, Int.MAX_VALUE)) {
            for (provider in providers(budget)) {
                val serialized = Json.encodeToString(ProviderSetting.serializer(), provider)
                val restored = Json.decodeFromString(ProviderSetting.serializer(), serialized)
                assertEquals(provider, restored)
                val row = provider.toEntity()
                assertEquals(budget, row.requestByteBudget)
                assertEquals(budget, ProviderWithModels(row, emptyList()).toDomain().requestByteBudget)
                assertEquals("fixture", ProviderWithModels(row, emptyList()).toDomain().apiKey)
            }
        }
    }

    @Test fun legacyProviderAndBackupEntityDefaultToNullWithoutLosingKey() {
        for (provider in providers(12345)) {
            val encoded = Json.encodeToString(ProviderSetting.serializer(), provider)
            val legacy = kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(encoded).jsonObject - "requestByteBudget")
            val restored = Json.decodeFromString(ProviderSetting.serializer(), legacy.toString())
            assertNull(restored.requestByteBudget)
            assertEquals("fixture", restored.apiKey)
            val row = provider.toEntity()
            val oldRow = kotlinx.serialization.json.JsonObject(
                Json.parseToJsonElement(Json.encodeToString(ProviderEntity.serializer(), row)).jsonObject - "requestByteBudget")
            val restoredRow = Json.decodeFromString(ProviderEntity.serializer(), oldRow.toString())
            assertNull(restoredRow.requestByteBudget)
            assertEquals(row.apiKey, restoredRow.apiKey)
        }
    }

    @Test fun builtinAndOrdinaryCopyOperationsPreserveBudget() {
        val builtIn = BuiltinProviders.PROVIDERS.first()
        val row = builtIn.toEntity().copy(requestByteBudget = 128 * 1024)
        val customized = ProviderWithModels(row, emptyList()).toDomain()
        for (copy in listOf(customized.withId("new"), customized.withApiKey("new-key"),
            customized.withModels(emptyList()), customized.withSortOrder(9))) {
            assertEquals(128 * 1024, copy.requestByteBudget)
        }
    }
}
