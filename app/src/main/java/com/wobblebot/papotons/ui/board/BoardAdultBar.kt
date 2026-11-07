package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.background
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.ImmutableList
import com.wobblebot.papotons.domain.Screen

/**
 * Épaisseur fixe, la cible minimale et pas plus, comme la hauteur de la barre du haut : ce qui s'y
 * ajoutera plus tard ne doit jamais déplacer la grille. Après publication, la changer déplacerait
 * toutes les cases à l'écran (invariant 3).
 */
private val ADULT_BAR_THICKNESS = 48.dp
private val BAR_PADDING = 8.dp
private val NAME_START_PADDING = 16.dp
private val NAME_ICON_SIZE = 18.dp
private val NAME_ICON_SPACING = 6.dp

/**
 * La barre du bas, loin des mots et des boutons de la phrase. À gauche, le prénom de l'utilisateur
 * ouvert (pour savoir sur quelles grilles on est, jamais cliquable) ; au centre, ses écrans quand il
 * en a au moins deux ([ScreenTabs]) ; à droite, le bouton Bureau et la roue dentée, toujours entiers.
 * Les écrans sont centrés sur la barre, quelle que soit la longueur du prénom : ils prennent au plus
 * la largeur que laissent les deux côtés à part égale, le prénom se coupe pour leur laisser la place.
 * Elle ne réserve pas la place de la barre de navigation du système, masquée : quand celle-ci revient
 * (glissement, modale, clavier), elle passe par-dessus au lieu de déplacer les cases. En écran partagé,
 * où Android ne la masque pas, l'écran lui réserve sa place.
 */
@Composable
fun BoardAdultBar(
    profileName: String?,
    /** Le bouton Bureau passe par le contrôle des adultes : grisé, mais toujours touchable. */
    isLeaveGated: Boolean,
    onLeaveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Les écrans de l'utilisateur ; affichés à partir de deux. */
    screens: ImmutableList<Screen> = persistentListOf(),
    /** L'écran ouvert (le bas de la pile), marqué au centre. */
    selectedScreenId: Long? = null,
    onScreenChosen: (Long) -> Unit = {},
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .height(ADULT_BAR_THICKNESS),
        content = {
            ProfileName(profileName)
            if (screens.size > 1 && selectedScreenId != null) {
                ScreenTabs(screens = screens, selectedId = selectedScreenId, onChosen = onScreenChosen)
            } else {
                Box {}
            }
            AdultButtons(isLeaveGated, onLeaveClick, onSettingsClick)
        },
    ) { measurables, constraints ->
        val (nameMeasurable, screensMeasurable, buttonsMeasurable) = measurables
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val nameStart = NAME_START_PADDING.roundToPx()
        val buttonsEnd = BAR_PADDING.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val buttons = buttonsMeasurable.measure(loose)
        // Centrés sur la barre entière (ses marges sont inégales) : autant de place de chaque côté,
        // celle des boutons, de leur marge et d'un écart de cible au moins (le dernier écran ne touche
        // jamais le bouton Bureau).
        val sideWidth = buttons.width + buttonsEnd + TARGET_SPACING.roundToPx()
        val screensPlaceable = screensMeasurable.measure(loose.copy(maxWidth = (width - 2 * sideWidth).coerceAtLeast(0)))
        val screensX = (width - screensPlaceable.width) / 2
        val nameEnd = if (screensPlaceable.width > 0) screensX else width - sideWidth
        val name = nameMeasurable.measure(loose.copy(maxWidth = (nameEnd - nameStart).coerceAtLeast(0)))
        layout(width, height) {
            name.placeRelative(nameStart, (height - name.height) / 2)
            screensPlaceable.placeRelative(screensX, (height - screensPlaceable.height) / 2)
            buttons.placeRelative(width - buttons.width - buttonsEnd, (height - buttons.height) / 2)
        }
    }
}

/** Un seul arrêt pour TalkBack, avec son contexte : « Utilisateur ouvert : Léo ». */
@Composable
private fun ProfileName(profileName: String?) {
    val nameDescription = profileName?.let { stringResource(R.string.adult_bar_profile_description, it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { if (nameDescription != null) contentDescription = nameDescription },
    ) {
        if (profileName != null) {
            Icon(
                painter = painterResource(R.drawable.ic_person),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(NAME_ICON_SIZE),
            )
            Spacer(modifier = Modifier.width(NAME_ICON_SPACING))
            // Une ligne, coupée : la barre garde son épaisseur même à police agrandie.
            Text(
                text = profileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AdultButtons(isLeaveGated: Boolean, onLeaveClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(TARGET_SPACING), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLeaveClick) {
            Icon(
                painter = painterResource(R.drawable.ic_exit_to_app),
                contentDescription = stringResource(if (isLeaveGated) R.string.adult_bar_leave_gated else R.string.adult_bar_leave),
                // Grisé sans passer sous 3:1 : la protection se voit, l'icône reste lisible pour l'adulte.
                tint = if (isLeaveGated) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettingsClick) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_open), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
