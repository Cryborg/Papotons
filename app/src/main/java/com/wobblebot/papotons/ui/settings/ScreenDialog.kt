package com.wobblebot.papotons.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.profile.normalizedProfileName
import com.wobblebot.papotons.domain.BoardSize

private val FIELD_SPACING = 8.dp

/** Ce que la modale d'écran crée ou modifie. */
sealed interface ScreenDialogTarget {
    /** Un nouvel écran, taille présélectionnée [initialSize]. */
    data class New(val initialSize: BoardSize) : ScreenDialogTarget

    /** [canDelete] faux pour le dernier écran : il faut un écran pour parler. */
    data class Existing(val name: String, val canDelete: Boolean) : ScreenDialogTarget
}

/**
 * Créer un écran (nom et taille) ou en modifier un (nom, suppression confirmée). Rien n'est sauvé
 * (`remember`), comme la modale des utilisateurs : une rotation la ferme, et aucun brouillon ne revient
 * après la mort du processus. Le corps défile : nom, trois cartes et clavier ouvert sur un téléphone
 * couché ne tiennent pas d'un coup.
 */
@Composable
fun ScreenDialog(target: ScreenDialogTarget, onSave: (name: String, size: BoardSize) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val existing = target as? ScreenDialogTarget.Existing
    val focusManager = LocalFocusManager.current
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    // La taille ne compte qu'à la création : un écran existant garde la sienne.
    var size by remember {
        mutableStateOf(
            when (target) {
                is ScreenDialogTarget.New -> target.initialSize
                is ScreenDialogTarget.Existing -> BoardSize.STANDARD
            }
        )
    }
    var isNameMissing by remember { mutableStateOf(false) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    if (isConfirmingDelete && existing?.canDelete == true) {
        DeleteScreenDialog(name = existing.name, onConfirm = onDelete, onCancel = { isConfirmingDelete = false })
        return
    }
    val save = {
        val normalized = normalizedProfileName(name)
        isNameMissing = normalized.isEmpty()
        if (!isNameMissing) onSave(normalized, size)
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (existing == null) R.string.screen_new_title else R.string.screen_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FIELD_SPACING), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (existing == null) Text(stringResource(R.string.screen_new_explanation), style = MaterialTheme.typography.bodyMedium)
                NameField(
                    name = name,
                    onNameChange = {
                        name = it
                        isNameMissing = false
                    },
                    label = stringResource(R.string.screen_name),
                    missingMessage = stringResource(R.string.screen_name_missing),
                    isMissing = isNameMissing,
                    capitalization = KeyboardCapitalization.Sentences,
                    // À la création, Entrée ferme le clavier et découvre les tailles : rien n'est créé sans les avoir vues.
                    onDone = if (existing != null) save else { { focusManager.clearFocus() } },
                )
                if (existing == null) {
                    Text(stringResource(R.string.settings_board_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    BoardSizeChoice(selected = size, onChosen = { size = it })
                } else if (existing.canDelete) {
                    // Dans le corps, pas à côté d'« Annuler » : un bouton par emplacement, que la police
                    // agrandie replie sans les écraser.
                    TextButton(onClick = { isConfirmingDelete = true }) {
                        Text(stringResource(R.string.screen_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = save) { Text(stringResource(if (existing == null) R.string.screen_create else R.string.screen_save)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.screen_cancel)) }
        },
    )
}

@Composable
private fun DeleteScreenDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDeleteDialog(
        title = stringResource(R.string.screen_delete_confirm_title, name),
        text = stringResource(R.string.screen_delete_confirm_text),
        deleteLabel = stringResource(R.string.screen_delete),
        cancelLabel = stringResource(R.string.screen_cancel),
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}

@Preview
@Composable
private fun NewScreenDialogPreview() {
    ScreenDialog(target = ScreenDialogTarget.New(BoardSize.STANDARD), onSave = { _, _ -> }, onDelete = {}, onClose = {})
}
