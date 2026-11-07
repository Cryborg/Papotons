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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
private val ALERT_ICON_SPACING = 8.dp
private val ALERT_MAX_WIDTH = 200.dp
private const val ALERT_MAX_LINES = 2
/**
 * En dessous, l'alerte n'est plus qu'une icône : écrite, elle ne laisserait presque rien à la phrase
 * (téléphone, écran partagé), et les boutons de la phrase finiraient l'un sur l'autre.
 */
private val WRITTEN_ALERT_MIN_BAR_WIDTH = 720.dp

/**
 * En dessous (téléphone zoomé), un dossier ne montre que le retour, pas l'accueil : avec une alerte,
 * la phrase, qu'on touche pour la dire, n'aurait bientôt plus la largeur d'une cible. Le test de
 * disposition à la borne le vérifie.
 */
private val BOTH_NAVIGATION_BUTTONS_MIN_BAR_WIDTH = 360.dp

/**
 * L'unique barre au-dessus de la grille : navigation, phrase en cours ([message]), alertes. Les
 * Réglages n'y sont pas : à côté des boutons de l'enfant, ils sont dans la barre des adultes
 * ([BoardAdultBar]). Pas de titre écrit : chaque ligne gagnée revient aux cases.
 */
@Composable
fun BoardTopBar(
    canGoBack: Boolean,
    speechStatus: SpeechStatus,
    hasLostBoards: Boolean,
    isMuted: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSpeechAlertClick: () -> Unit,
    onMutedAlertClick: () -> Unit,
    onLostBoardsAlertClick: () -> Unit,
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
        // Fixé par la largeur de la fenêtre, jamais par l'état : rien ne bouge sous le doigt. En pixels :
        // en dp, l'arrondi de la marge ferait manquer la borne à certaines densités.
        val hasHomeButton = with(LocalDensity.current) {
            constraints.maxWidth + BAR_HORIZONTAL_PADDING.roundToPx() * 2 >= BOTH_NAVIGATION_BUTTONS_MIN_BAR_WIDTH.roundToPx()
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(TARGET_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                ChildIconButton(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.board_back), onBack)
                if (hasHomeButton) ChildIconButton(painterResource(R.drawable.ic_home), stringResource(R.string.board_home), onHome)
            }
            // Les alertes entre la navigation et la phrase : qu'une alerte apparaisse ou disparaisse,
            // seule la phrase change de largeur. Les boutons d'effacement ne bougent jamais sous le
            // doigt, ni le bord droit de la phrase contre eux : c'est là que l'appui pour la dire est stable.
            for (alert in visibleAlerts(isAlertWritten, speechStatus, isMuted, hasLostBoards)) {
                when (alert) {
                    TopBarAlert.Speech -> SpeechAlert(speechStatus, isAlertWritten, onSpeechAlertClick)
                    // Haut-parleur barré : un appui monte le son, c'est ce qu'il faut même si c'est la
                    // personne elle-même qui le touche.
                    TopBarAlert.Muted -> ChildIconButton(
                        painterResource(R.drawable.ic_volume_off),
                        stringResource(R.string.volume_muted_alert),
                        onMutedAlertClick,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    // Icône propre (dossier barré) : elle ne se confond pas avec l'alerte de voix.
                    TopBarAlert.LostBoards -> ChildIconButton(
                        painterResource(R.drawable.ic_folder_off),
                        stringResource(R.string.lost_boards_alert),
                        onLostBoardsAlertClick,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            message()
        }
    }
}

@Composable
private fun SpeechAlert(speechStatus: SpeechStatus, isWritten: Boolean, onClick: () -> Unit) {
    val alertLabel = stringResource(speechAlertLabel(speechStatus))
    if (isWritten) {
        // Plafonnée : agrandie (police à 200 %), elle prendrait sinon toute la place de la phrase.
        ChildButton(onClick = onClick, modifier = Modifier.widthIn(max = ALERT_MAX_WIDTH)) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(ALERT_ICON_SPACING))
            Text(
                text = alertLabel,
                // Le style qu'imposait `TextButton` : plus compact, deux lignes tiennent dans la carte.
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                maxLines = ALERT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        ChildIconButton(painterResource(R.drawable.ic_warning), alertLabel, onClick, tint = MaterialTheme.colorScheme.error)
    }
}

enum class TopBarAlert { Speech, Muted, LostBoards }

/**
 * Les alertes à montrer, dans l'ordre. Barre étroite : la plus importante seule, deux de plus ne
 * laisseraient plus de place aux boutons de la phrase. Par importance : voix en panne (on ne parle
 * plus), son coupé (on parle sans être entendu), voix française manquante (l'appli parle quand
 * même), grilles perdues (pour l'adulte, jusqu'à acquittement). Barre large : toutes, dans un ordre
 * fixe : une alerte ne passe pas devant une autre quand l'état de la voix change.
 */
fun visibleAlerts(isBarWide: Boolean, speechStatus: SpeechStatus, isMuted: Boolean, hasLostBoards: Boolean): List<TopBarAlert> {
    if (isBarWide) {
        return buildList {
            if (speechStatus.needsAttention) add(TopBarAlert.Speech)
            if (isMuted) add(TopBarAlert.Muted)
            if (hasLostBoards) add(TopBarAlert.LostBoards)
        }
    }
    val mostImportant = when {
        speechStatus == SpeechStatus.Unavailable -> TopBarAlert.Speech
        isMuted -> TopBarAlert.Muted
        speechStatus == SpeechStatus.LanguageMissing -> TopBarAlert.Speech
        hasLostBoards -> TopBarAlert.LostBoards
        else -> null
    }
    return listOfNotNull(mostImportant)
}

/** Deux pannes, deux textes : une voix à installer n'est pas un moteur qui ne répond plus. */
@StringRes
fun speechAlertLabel(status: SpeechStatus): Int =
    if (status == SpeechStatus.LanguageMissing) R.string.speech_language_missing else R.string.speech_unavailable
