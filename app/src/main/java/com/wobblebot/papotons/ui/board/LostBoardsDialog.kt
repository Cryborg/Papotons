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
 * Pour l'adulte : les grilles personnalisées ont disparu et le vocabulaire de base les remplace.
 * Ouverte par l'alerte de la barre du haut, qui reste tant que [onAcknowledge] n'a pas été choisi.
 */
@Composable
fun LostBoardsDialog(onAcknowledge: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.lost_boards_alert)) },
        // Défilable : à 200 % en paysage, le texte pousserait sinon les boutons hors de la fenêtre.
        text = { Text(stringResource(R.string.lost_boards_dialog_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
        // « Plus tard » en bouton principal : c'est souvent la personne elle-même qui ouvre la modale,
        // et son premier appui ne doit pas effacer l'alerte destinée à l'adulte.
        confirmButton = {
            Button(onClick = onClose) {
                Text(stringResource(R.string.lost_boards_dialog_later))
            }
        },
        dismissButton = {
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.lost_boards_dialog_acknowledge))
            }
        },
    )
}

@Preview
@Composable
private fun LostBoardsDialogPreview() {
    LostBoardsDialog(onAcknowledge = {}, onClose = {})
}
