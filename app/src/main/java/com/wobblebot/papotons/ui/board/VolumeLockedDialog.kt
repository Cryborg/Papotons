package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R

/**
 * Pour l'adulte : l'appui sur « Son coupé » n'a pas pu monter le son (« Ne pas déranger »,
 * réglage du constructeur). Ouverte seulement dans ce cas ; le son revenu la referme.
 */
@Composable
fun VolumeLockedDialog(onOpenSettings: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.volume_locked_title)) },
        // Défilable : à 200 % en paysage, le texte pousserait sinon les boutons hors de la fenêtre.
        text = { Text(stringResource(R.string.volume_locked_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.volume_locked_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.volume_locked_close))
            }
        },
    )
}

@Preview
@Composable
private fun VolumeLockedDialogPreview() {
    VolumeLockedDialog(onOpenSettings = {}, onClose = {})
}
