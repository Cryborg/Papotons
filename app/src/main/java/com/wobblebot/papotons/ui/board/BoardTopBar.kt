package com.wobblebot.papotons.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.speech.SpeechStatus

/** Hauteur fixe : ce qui s'affiche ou disparaît ici ne doit jamais déplacer la grille (invariant 3). */
private val TOP_BAR_HEIGHT = 56.dp
private val TITLE_START_PADDING = 16.dp
private val ALERT_ICON_SPACING = 8.dp
private val ALERT_MAX_WIDTH = 200.dp

@Composable
fun BoardTopBar(
    title: String,
    canGoBack: Boolean,
    speechStatus: SpeechStatus,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSpeechAlertClick: () -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.board_back))
            }
            IconButton(onClick = onHome) {
                Icon(painterResource(R.drawable.ic_home), stringResource(R.string.board_home))
            }
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = TITLE_START_PADDING)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (speechStatus.needsAttention) {
            // Plafonnée : agrandie (police à 200 %), elle prendrait sinon toute la place du titre.
            TextButton(onClick = onSpeechAlertClick, modifier = Modifier.widthIn(max = ALERT_MAX_WIDTH)) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(ALERT_ICON_SPACING))
                Text(
                    text = stringResource(speechAlertLabel(speechStatus)),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Un écart entre l'alerte et « À propos » : deux cibles collées se touchent par erreur.
        if (speechStatus.needsAttention) Spacer(modifier = Modifier.width(ALERT_ICON_SPACING))
        IconButton(onClick = onAboutClick) {
            Icon(painterResource(R.drawable.ic_info), stringResource(R.string.about_open))
        }
    }
}

/** Deux pannes, deux textes : une voix à installer n'est pas un moteur qui ne répond plus. */
@StringRes
fun speechAlertLabel(status: SpeechStatus): Int =
    if (status == SpeechStatus.LanguageMissing) R.string.speech_language_missing else R.string.speech_unavailable
