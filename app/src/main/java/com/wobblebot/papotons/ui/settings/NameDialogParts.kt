package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.data.profile.MAX_PROFILE_NAME_LENGTH
import com.wobblebot.papotons.ui.edit.takeWhole

/**
 * Le nom d'un utilisateur, d'un accompagnant ou d'un écran : une ligne, [MAX_PROFILE_NAME_LENGTH]
 * caractères au plus. [missingMessage] s'affiche sous le champ quand [isMissing], annoncé tout de
 * suite : le focus de TalkBack reste sur le bouton qui n'a rien fait. [onDone] : la touche Entrée
 * enregistre (`null` : elle passe au champ suivant).
 */
@Composable
fun NameField(
    name: String,
    onNameChange: (String) -> Unit,
    label: String,
    missingMessage: String,
    isMissing: Boolean,
    capitalization: KeyboardCapitalization,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = name,
        onValueChange = { typed -> onNameChange(typed.replace('\n', ' ').takeWhole(MAX_PROFILE_NAME_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        isError = isMissing,
        supportingText = if (isMissing) {
            { Text(missingMessage, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
    )
}

/**
 * La confirmation d'une suppression ouverte depuis le corps d'une modale. Le texte défile (police
 * agrandie) ; « Supprimer » à droite, loin du « Supprimer » du corps qui a ouvert la confirmation.
 */
@Composable
fun ConfirmDeleteDialog(title: String, text: String, deleteLabel: String, cancelLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = { Text(text = text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(deleteLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancelLabel) } },
    )
}
