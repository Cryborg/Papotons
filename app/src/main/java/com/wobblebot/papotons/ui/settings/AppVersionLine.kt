package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R

private val VERSION_LINE_VERTICAL_PADDING = 16.dp

/**
 * Version de l'appli, en bas des réglages : « Version 1.4.0 (27) ».
 *
 * Chasse fixe : c'est la seule chaîne de l'appli qu'un utilisateur recopie caractère par caractère
 * ou lit à voix haute, et le pas constant y sépare les groupes de chiffres tout en
 * distinguant le 1 du l.
 *
 * [version] est un paramètre et non une lecture de `BuildConfig` sur place : une preview
 * ou une capture d'écran passe une version figée, sinon elle changerait à chaque montée.
 */
@Composable
fun AppVersionLine(modifier: Modifier = Modifier, version: AppVersion = AppVersion.current) {
    Text(
        text = stringResource(R.string.settings_version, version.versionName, version.versionCode),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = VERSION_LINE_VERTICAL_PADDING)
    )
}

@Preview(showBackground = true)
@Composable
private fun AppVersionLinePreview() {
    AppVersionLine(version = AppVersion(versionName = "1.0.0", versionCode = 1))
}
