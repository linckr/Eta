package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    /** Null follows each conversation's model; a value is an explicitly chosen model row ID. */
    val compactionModelId: String? = null,
)
