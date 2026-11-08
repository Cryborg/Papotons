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
import com.wobblebot.papotons.data.backup.RecoveryReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val PARAGRAPH_SPACING = 12.dp

/**
 * Dernier mot avant de remplacer toutes les grilles : ce que contient la sauvegarde, ce qui sera
 * perdu, utilisateurs nommés (une sauvegarde d'un seul enfant effacerait les autres). Pour une copie
 * abîmée relue ([recovery] non nul) : sa date, ce qu'on en a relu, et que tout est à relire.
 */
@Composable
fun RestoreConfirmDialog(
    fileName: String?,
    recovery: RecoveryReport?,
    currentProfileNames: ImmutableList<String>,
    restoredProfileNames: ImmutableList<String>,
    boardCount: Int,
    cellCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val separator = stringResource(R.string.names_separator)
    val boards = pluralStringResource(R.plurals.restore_confirm_boards, boardCount, boardCount)
    AlertDialog(
        onDismissRequest = onCancel,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (recovery == null) R.string.restore_confirm_title else R.string.recovery_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PARAGRAPH_SPACING),
            ) {
                if (recovery == null) {
                    fileName?.let { Text(stringResource(R.string.restore_confirm_file, it), style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        text = stringResource(R.string.restore_confirm_content, boards, pluralStringResource(R.plurals.restore_confirm_cells, cellCount, cellCount)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(copyDateText(recovery.copiedAtMs), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.recovery_confirm_content, boards, recoveredCellsText(cellCount, recovery.announcedCellCount)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (recovery.leftOutRowCount > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.recovery_confirm_left_out, recovery.leftOutRowCount, recovery.leftOutRowCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (recovery == null) R.string.restore_confirm_warning else R.string.recovery_confirm_warning,
                        currentProfileNames.joinToString(separator = separator),
                        restoredProfileNames.joinToString(separator = separator),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
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
                Text(stringResource(if (recovery == null) R.string.restore_confirm else R.string.recovery_confirm))
            }
        },
    )
}

@Preview
@Composable
private fun RestoreConfirmDialogPreview() {
    RestoreConfirmDialog(
        fileName = "papotons-2026-11-07.obz",
        recovery = null,
        currentProfileNames = persistentListOf("Léo", "Marie"),
        restoredProfileNames = persistentListOf("Léo"),
        boardCount = 12,
        cellCount = 340,
        onConfirm = {},
        onCancel = {},
    )
}

@Preview
@Composable
private fun RecoveryConfirmDialogPreview() {
    RestoreConfirmDialog(
        fileName = null,
        recovery = RecoveryReport(copyName = "000001-preview", copiedAtMs = 1_794_000_000_000L, announcedCellCount = 224, leftOutRowCount = 3),
        currentProfileNames = persistentListOf("Moi"),
        restoredProfileNames = persistentListOf("Léo", "Marie"),
        boardCount = 7,
        cellCount = 180,
        onConfirm = {},
        onCancel = {},
    )
}
