package com.wobblebot.papotons.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R

private val PARAGRAPH_SPACING = 12.dp

/** Dernier mot avant de remplacer toutes les grilles : ce que contient la sauvegarde, ce qui sera perdu. */
@Composable
fun RestoreConfirmDialog(
    fileName: String?,
    boardCount: Int,
    cellCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PARAGRAPH_SPACING),
            ) {
                fileName?.let { Text(stringResource(R.string.restore_confirm_file, it), style = MaterialTheme.typography.bodyMedium) }
                Text(
                    text = stringResource(
                        R.string.restore_confirm_content,
                        pluralStringResource(R.plurals.restore_confirm_boards, boardCount, boardCount),
                        pluralStringResource(R.plurals.restore_confirm_cells, cellCount, cellCount),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(R.string.restore_confirm_warning), style = MaterialTheme.typography.titleSmall)
            }
        },
        // « Annuler » en bouton principal : remplacer toutes les grilles ne se fait pas d'un appui distrait.
        confirmButton = {
            Button(onClick = onCancel) {
                Text(stringResource(R.string.restore_cancel))
            }
        },
        dismissButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.restore_confirm))
            }
        },
    )
}

@Preview
@Composable
private fun RestoreConfirmDialogPreview() {
    RestoreConfirmDialog(fileName = "papotons-2026-11-07.obz", boardCount = 12, cellCount = 340, onConfirm = {}, onCancel = {})
}
