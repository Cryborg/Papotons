package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.profile.normalizedProfileName

private val FIELD_SPACING = 8.dp

/**
 * Créer ([initialName] `null`) ou modifier un utilisateur. Rien n'est sauvé (`remember`) : sauvé, un
 * brouillon survivrait à la mort du processus et reviendrait dans la modale d'un autre utilisateur,
 * jusque sur sa confirmation de suppression. Une rotation ferme donc la modale. [onDelete] `null` : pas de suppression
 * (nouvel utilisateur, ou le dernier). La suppression passe par une confirmation.
 */
@Composable
fun ProfileDialog(initialName: String?, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var isNameMissing by remember { mutableStateOf(false) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    if (isConfirmingDelete && onDelete != null) {
        DeleteProfileDialog(name = initialName.orEmpty(), onConfirm = onDelete, onCancel = { isConfirmingDelete = false })
        return
    }
    val save = {
        val normalized = normalizedProfileName(name)
        isNameMissing = normalized.isEmpty()
        if (!isNameMissing) onSave(normalized)
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (initialName == null) R.string.profile_new_title else R.string.profile_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FIELD_SPACING)) {
                if (initialName == null) Text(stringResource(R.string.profile_new_explanation), style = MaterialTheme.typography.bodyMedium)
                NameField(
                    name = name,
                    onNameChange = {
                        name = it
                        isNameMissing = false
                    },
                    label = stringResource(R.string.profile_name),
                    missingMessage = stringResource(R.string.profile_name_missing),
                    isMissing = isNameMissing,
                    capitalization = KeyboardCapitalization.Words,
                    onDone = save,
                )
                // Dans le corps, pas à côté d'« Annuler » : un bouton par emplacement, que la police agrandie
                // replie sans les écraser.
                if (onDelete != null) {
                    TextButton(onClick = { isConfirmingDelete = true }) {
                        Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = save) { Text(stringResource(R.string.profile_save)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.profile_cancel)) }
        },
    )
}

@Composable
private fun DeleteProfileDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDeleteDialog(
        title = stringResource(R.string.profile_delete_confirm_title, name),
        text = stringResource(R.string.profile_delete_confirm_text),
        deleteLabel = stringResource(R.string.profile_delete),
        cancelLabel = stringResource(R.string.profile_cancel),
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}

@Preview
@Composable
private fun ProfileDialogPreview() {
    ProfileDialog(initialName = "Léo", onSave = {}, onDelete = {}, onClose = {})
}
