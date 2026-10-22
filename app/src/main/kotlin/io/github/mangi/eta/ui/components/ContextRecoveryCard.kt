package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ContextRecoveryUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ContextRecoveryCard(state: ContextRecoveryUi, onAction: (String, String) -> Unit) {
    val d = state.decision
    var confirming by remember(state.key, d.phase) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(stringResource(R.string.context_recovery_title), style = MiuixTheme.textStyles.subtitle)
        Text("${d.reasonCode} · ${d.stage}: ${d.detail}")
        Text(stringResource(R.string.context_recovery_checkpoint, d.completedSlices?.toString() ?: stringResource(R.string.context_recovery_unverified)))
        Text(stringResource(R.string.context_recovery_preserved))
        if (d.phase == "consumed") Text(stringResource(R.string.context_recovery_consumed))
        if (d.phase == "expired") Text(stringResource(R.string.context_recovery_expired))
        if (d.permitUnavailableReason.isNotBlank()) Text(d.permitUnavailableReason)
        state.ackMessage?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (state.waiting) {
            if (d.permitAvailable) EtaTextButton(stringResource(R.string.context_recovery_send_once), { confirming = true }, enabled = state.canSend)
            EtaTextButton(stringResource(if (d.completedSlices != null) R.string.context_recovery_continue else R.string.context_recovery_retry), { onAction(state.key, "compact") }, enabled = state.canCompact)
            EtaTextButton(stringResource(R.string.context_recovery_stop), { onAction(state.key, "stop") }, enabled = !state.submitting)
        }
    }
    EtaWindowDialog(show = confirming && state.canSend, title = stringResource(R.string.context_recovery_send_once), onDismissRequest = { confirming = false }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("${d.providerLabel} / ${d.modelLabel}")
            Text("S: ${d.requestBytes} B · L: ${d.sendLimitBytes} B · H: ${d.hardLimitBytes?.toString() ?: stringResource(R.string.context_recovery_unknown)}")
            Text(stringResource(R.string.context_recovery_tokens, d.estimatedTokens?.toString() ?: "?", d.inputLimit?.toString() ?: stringResource(R.string.context_recovery_unknown)))
            Text(stringResource(R.string.context_recovery_confirm))
            EtaTextButton(stringResource(R.string.context_recovery_send_once), { confirming = false; onAction(state.key, "send_once") }, enabled = state.canSend)
            EtaTextButton(stringResource(R.string.context_recovery_back), { confirming = false })
        }
    }
}
