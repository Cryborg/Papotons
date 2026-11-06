package com.wobblebot.papotons.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R

/**
 * Une mise à jour est téléchargée et n'attend plus que le feu vert de l'utilisateur.
 *
 * On ne l'installe pas dans son dos : `completeUpdate()` redémarre l'appli, et le faire au
 * milieu d'une phrase serait vécu comme une panne. « Plus tard » est une réponse pleine
 * et entière : l'hôte la retient pour la session.
 */
@Composable
fun UpdateReadyDialog(onInstall: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.update_ready_title)) },
        text = { Text(stringResource(R.string.update_ready_body)) },
        confirmButton = {
            Button(onClick = onInstall) {
                Text(stringResource(R.string.update_ready_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_ready_later))
            }
        }
    )
}

@Preview
@Composable
private fun UpdateReadyDialogPreview() {
    UpdateReadyDialog(onInstall = {}, onLater = {})
}
