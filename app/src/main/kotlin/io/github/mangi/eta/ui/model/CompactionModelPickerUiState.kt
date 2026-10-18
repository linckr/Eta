package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ProviderSetting

/** Explicit selection only: missing/disabled IDs never silently pick a different provider. */
internal fun projectCompactionModelPicker(
    providers: List<ProviderSetting>,
    selectedModelId: String?,
): AgentModelPickerUiState = AgentModelPickerProjector.project(
    providers = providers,
    selectedProviderId = null,
    selectedModelId = selectedModelId,
    includeProvidersWithoutCredentials = true,
)

internal fun canSelectCompactionModel(state: AgentModelPickerUiState, modelId: String?): Boolean =
    modelId == null || state.providerGroups.any { group -> group.models.any { it.id == modelId } }
