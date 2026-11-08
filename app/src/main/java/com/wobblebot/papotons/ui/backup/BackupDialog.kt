package com.wobblebot.papotons.ui.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R

private val SECTION_SPACING = 12.dp
private val PROGRESS_SIZE = 24.dp

/** Enregistrer ou restaurer une sauvegarde ; le résultat s'écrit dans la modale. */
@Composable
fun BackupDialog(
    state: BackupUiState,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onRecover: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.backup_title)) },
        text = {
            // Défile : à police agrandie sur un téléphone couché, la modale dépasse l'écran.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
            ) {
                Text(
                    text = stringResource(if (state.hasUnsavedChanges) R.string.backup_unsaved else R.string.backup_up_to_date),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(R.string.backup_explanation), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onSave, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_save))
                }
                OutlinedButton(onClick = onRestore, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_restore))
                }
                if (state.hasCorruptedCopies) {
                    OutlinedButton(onClick = onRecover, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.backup_recover))
                    }
                }
                if (state.isWorking) {
                    // Annoncé : les boutons grisés perdent le focus de TalkBack, le travail en cours se dit.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE))
                        Text(stringResource(R.string.backup_working))
                    }
                }
                state.message?.let { message ->
                    // Annoncé par TalkBack : le résultat arrive sans que le focus bouge.
                    Text(
                        text = stringResource(message.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = !state.isWorking) {
                Text(stringResource(R.string.backup_close))
            }
        },
    )
}

@get:StringRes
private val BackupMessage.text: Int
    get() = when (this) {
        BackupMessage.Saved -> R.string.backup_saved
        BackupMessage.SavedWithoutSomeImages -> R.string.backup_saved_without_some_images
        BackupMessage.SaveFailed -> R.string.backup_save_failed
        BackupMessage.SaveTooLarge -> R.string.backup_save_too_large
        BackupMessage.Restored -> R.string.backup_restored
        BackupMessage.Recovered -> R.string.backup_recovered
        BackupMessage.NothingRecovered -> R.string.backup_nothing_recovered
        BackupMessage.RestoreFailed -> R.string.backup_restore_failed
        BackupMessage.NotPapotons -> R.string.backup_refused_not_papotons
        BackupMessage.Unreadable -> R.string.backup_refused_unreadable
        BackupMessage.TooLarge -> R.string.backup_refused_too_large
        BackupMessage.Invalid -> R.string.backup_refused_invalid
        BackupMessage.NoDocumentApp -> R.string.backup_no_document_app
    }

@Preview
@Composable
private fun BackupDialogPreview() {
    BackupDialog(
        state = BackupUiState(isDialogOpen = true, message = BackupMessage.Saved, hasCorruptedCopies = true),
        onSave = {},
        onRestore = {},
        onRecover = {},
        onClose = {},
    )
}
