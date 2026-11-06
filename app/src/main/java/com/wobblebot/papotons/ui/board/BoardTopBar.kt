package com.wobblebot.papotons.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.speech.SpeechStatus

/**
 * Hauteur fixe : ce qui s'affiche ou disparaît ici (boutons d'un dossier, alerte, mots de la phrase)
 * ne doit jamais déplacer la grille. Après publication, la changer déplacerait toutes les cases à
 * l'écran (invariant 3).
 */
private val TOP_BAR_HEIGHT = 104.dp
private val BAR_HORIZONTAL_PADDING = 8.dp
// 8 dp entre deux cibles voisines : un appui qui tremble ne doit pas toucher la voisine.
private val TARGET_SPACING = 8.dp
private val ALERT_ICON_SPACING = 8.dp
private val ALERT_MAX_WIDTH = 200.dp
private const val ALERT_MAX_LINES = 2
/**
 * En dessous, l'alerte n'est plus qu'une icône : écrite, elle ne laisserait presque rien à la phrase
 * (téléphone, écran partagé), et les boutons de la phrase finiraient l'un sur l'autre.
 */
private val WRITTEN_ALERT_MIN_BAR_WIDTH = 720.dp

/**
 * L'unique barre au-dessus de la grille : navigation, phrase en cours ([message]), alerte de voix,
 * « À propos ». Pas de titre écrit : chaque ligne gagnée revient aux cases.
 */
@Composable
fun BoardTopBar(
    canGoBack: Boolean,
    speechStatus: SpeechStatus,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSpeechAlertClick: () -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
    message: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = BAR_HORIZONTAL_PADDING),
    ) {
        val isAlertWritten = maxWidth >= WRITTEN_ALERT_MIN_BAR_WIDTH
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(TARGET_SPACING),
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
            message()
            if (speechStatus.needsAttention) {
                val alertLabel = stringResource(speechAlertLabel(speechStatus))
                if (isAlertWritten) {
                    // Plafonnée : agrandie (police à 200 %), elle prendrait sinon toute la place de la phrase.
                    TextButton(onClick = onSpeechAlertClick, modifier = Modifier.widthIn(max = ALERT_MAX_WIDTH)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(ALERT_ICON_SPACING))
                        Text(
                            text = alertLabel,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = ALERT_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    IconButton(onClick = onSpeechAlertClick) {
                        Icon(painterResource(R.drawable.ic_warning), alertLabel, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            IconButton(onClick = onAboutClick) {
                Icon(painterResource(R.drawable.ic_info), stringResource(R.string.about_open))
            }
        }
    }
}

/** Deux pannes, deux textes : une voix à installer n'est pas un moteur qui ne répond plus. */
@StringRes
fun speechAlertLabel(status: SpeechStatus): Int =
    if (status == SpeechStatus.LanguageMissing) R.string.speech_language_missing else R.string.speech_unavailable
