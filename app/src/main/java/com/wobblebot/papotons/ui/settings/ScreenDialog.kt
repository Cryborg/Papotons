package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.profile.MAX_PROFILE_NAME_LENGTH
import com.wobblebot.papotons.data.profile.normalizedProfileName
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.ui.edit.takeWhole

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
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var size by remember { mutableStateOf((target as? ScreenDialogTarget.New)?.initialSize ?: BoardSize.STANDARD) }
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { typed ->
                        name = typed.replace('\n', ' ').takeWhole(MAX_PROFILE_NAME_LENGTH)
                        isNameMissing = false
                    },
                    label = { Text(stringResource(R.string.screen_name)) },
                    singleLine = true,
                    isError = isNameMissing,
                    supportingText = if (isNameMissing) {
                        // Annoncé : le focus de TalkBack reste sur le bouton, qui n'a rien fait.
                        { Text(stringResource(R.string.screen_name_missing), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
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
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.screen_delete_confirm_title, name)) },
        text = { Text(stringResource(R.string.screen_delete_confirm_text)) },
        // À droite, loin du « Supprimer » du corps qui a ouvert la confirmation.
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.screen_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.screen_cancel)) }
        },
    )
}

@Preview
@Composable
private fun NewScreenDialogPreview() {
    ScreenDialog(target = ScreenDialogTarget.New(BoardSize.STANDARD), onSave = { _, _ -> }, onDelete = {}, onClose = {})
}
