package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.caregiver.CAREGIVER_CODE_LENGTH
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.data.profile.MAX_PROFILE_NAME_LENGTH
import com.wobblebot.papotons.data.profile.normalizedProfileName
import com.wobblebot.papotons.ui.edit.CodeError
import com.wobblebot.papotons.ui.edit.CodeField
import com.wobblebot.papotons.ui.edit.codeError
import com.wobblebot.papotons.ui.edit.takeWhole

private val FIELD_SPACING = 8.dp

/**
 * Créer ([caregiver] `null`, code obligatoire) ou modifier un accompagnant (code vide : inchangé).
 * Rien n'est sauvé (`remember`) : ni les codes, ni un brouillon qui reviendrait, après la mort du
 * processus, dans la modale d'un autre accompagnant. Une rotation ferme donc la modale.
 */
@Composable
fun CaregiverDialog(caregiver: Caregiver?, isLast: Boolean, onSave: (String, String?) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(caregiver?.name.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var isNameMissing by remember { mutableStateOf(false) }
    var codeErrorMessage by remember { mutableStateOf<Int?>(null) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    if (isConfirmingDelete && caregiver != null) {
        DeleteCaregiverDialog(caregiver.name, isLast, onConfirm = onDelete, onCancel = { isConfirmingDelete = false })
        return
    }
    val save = {
        val normalized = normalizedProfileName(name)
        isNameMissing = normalized.isEmpty()
        // Un accompagnant existant garde son code si les deux champs restent vides.
        val keepsCode = caregiver != null && code.isEmpty() && again.isEmpty()
        codeErrorMessage = if (keepsCode) null else codeError(code, again)
        if (!isNameMissing && codeErrorMessage == null) onSave(normalized, if (keepsCode) null else code)
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (caregiver == null) R.string.caregiver_new_title else R.string.caregiver_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FIELD_SPACING)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { typed ->
                        name = typed.replace('\n', ' ').takeWhole(MAX_PROFILE_NAME_LENGTH)
                        isNameMissing = false
                    },
                    label = { Text(stringResource(R.string.caregiver_name)) },
                    singleLine = true,
                    isError = isNameMissing,
                    supportingText = if (isNameMissing) {
                        { Text(stringResource(R.string.caregiver_name_missing), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                Text(stringResource(R.string.caregiver_code_hint), style = MaterialTheme.typography.bodyMedium)
                CodeField(
                    value = code,
                    onValueChange = { code = it },
                    label = if (caregiver == null) {
                        stringResource(R.string.caregiver_code, CAREGIVER_CODE_LENGTH)
                    } else {
                        stringResource(R.string.caregiver_new_code, CAREGIVER_CODE_LENGTH)
                    },
                    imeAction = ImeAction.Next,
                    isError = codeErrorMessage != null,
                )
                CodeField(
                    value = again,
                    onValueChange = { again = it },
                    label = stringResource(R.string.caregiver_code_again),
                    onDone = save,
                    isError = codeErrorMessage != null,
                )
                codeErrorMessage?.let { CodeError(it) }
                // Dans le corps : un bouton par emplacement, que la police agrandie replie sans les écraser.
                if (caregiver != null) {
                    TextButton(onClick = { isConfirmingDelete = true }) {
                        Text(stringResource(R.string.caregiver_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = save) { Text(stringResource(R.string.caregiver_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.caregiver_cancel)) } },
    )
}

@Composable
private fun DeleteCaregiverDialog(name: String, isLast: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.caregiver_delete_confirm_title, name)) },
        text = {
            Text(
                text = stringResource(if (isLast) R.string.caregiver_delete_last_confirm_text else R.string.caregiver_delete_confirm_text),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        // À droite : loin du « Supprimer » du corps qui a ouvert la confirmation.
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.caregiver_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.caregiver_cancel)) } },
    )
}
