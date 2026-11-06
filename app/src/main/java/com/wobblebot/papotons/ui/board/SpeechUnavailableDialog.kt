package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.speech.SpeechStatus

private val SETTINGS_HINT_SPACING = 12.dp

/**
 * Ouverte par l'alerte de la barre du haut, jamais d'elle-même. Une étape de plus avant les
 * réglages d'Android : un appui involontaire ne fait pas sortir de l'appli.
 */
@Composable
fun SpeechUnavailableDialog(
    status: SpeechStatus,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    areSettingsUnreachable: Boolean = false,
) {
    val body = if (status == SpeechStatus.LanguageMissing) R.string.speech_dialog_body_language_missing else R.string.speech_dialog_body_unavailable
    AlertDialog(
        onDismissRequest = onClose,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(speechAlertLabel(status))) },
        text = {
            Column {
                Text(stringResource(body))
                if (areSettingsUnreachable) {
                    Spacer(modifier = Modifier.height(SETTINGS_HINT_SPACING))
                    Text(stringResource(R.string.speech_dialog_settings_unreachable), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.speech_dialog_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.speech_dialog_settings))
            }
        },
    )
}

@Preview
@Composable
private fun SpeechUnavailableDialogPreview() {
    SpeechUnavailableDialog(status = SpeechStatus.LanguageMissing, onRetry = {}, onOpenSettings = {}, onClose = {})
}
