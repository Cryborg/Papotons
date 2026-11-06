package com.wobblebot.papotons.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.ui.settings.AppVersion
import com.wobblebot.papotons.ui.settings.AppVersionLine

private val PARAGRAPH_SPACING = 12.dp

/**
 * Crédits : celui d'ARASAAC est obligatoire dès qu'un picto est embarqué (invariant 5). Les
 * adresses restent du texte : rien ici ne fait sortir de l'appli.
 */
@Composable
fun AboutDialog(onClose: () -> Unit, version: AppVersion = AppVersion.current) {
    AlertDialog(
        onDismissRequest = onClose,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            // Défile : à police agrandie sur un téléphone couché, le texte dépasse l'écran.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Paragraph(R.string.about_arasaac_credit)
                Paragraph(R.string.about_icons_credit)
                Paragraph(R.string.about_app_license)
                AppVersionLine(version = version)
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.about_close))
            }
        },
    )
}

@Composable
private fun Paragraph(@StringRes text: Int) {
    Text(text = stringResource(text), style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(PARAGRAPH_SPACING))
}

@Preview
@Composable
private fun AboutDialogPreview() {
    AboutDialog(onClose = {}, version = AppVersion(versionName = "0.1.0", versionCode = 1))
}
