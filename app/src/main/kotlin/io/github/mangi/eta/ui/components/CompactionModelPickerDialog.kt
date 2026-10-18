package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.canSelectCompactionModel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CompactionModelPickerDialog(
    show: Boolean,
    state: AgentModelPickerUiState,
    selectedModelId: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var draftId by remember(show, selectedModelId) { mutableStateOf(selectedModelId) }
    EtaOverlayDialog(
        show = show,
        title = stringResource(R.string.settings_compaction_model),
        summary = stringResource(R.string.settings_compaction_model_route),
        onDismissRequest = onDismissRequest,
    ) {
        Column {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item(key = "follow_current") {
                    EtaPreference(
                        title = stringResource(R.string.settings_compaction_model_follow),
                        endActions = { if (draftId == null) Text("✓") },
                        onClick = { draftId = null },
                    )
                }
                if (state.providerGroups.isEmpty()) {
                    item(key = "no_models") {
                        Text(
                            text = stringResource(R.string.settings_compaction_model_empty),
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                state.providerGroups.forEach { group ->
                    item(key = "provider-${group.providerId}") {
                        Text(
                            text = group.providerName,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(group.models, key = { "model-${it.id}" }) { model ->
                        EtaPreference(
                            title = model.displayName,
                            endActions = { if (draftId == model.id) Text("✓") },
                            onClick = { draftId = model.id },
                        )
                    }
                }
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.page_save_configuration_817af1),
                onCancel = onDismissRequest,
                onConfirm = { if (canSelectCompactionModel(state, draftId)) onConfirm(draftId) },
                confirmEnabled = canSelectCompactionModel(state, draftId),
            )
        }
    }
}
