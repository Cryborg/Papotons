package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.ui.settings.initialOf

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
private val INITIAL_BADGE_SIZE = 32.dp
private val INITIAL_TEXT_SIZE = 16.dp

/**
 * La barre des adultes, loin des mots et des boutons de la phrase : le prénom de l'utilisateur ouvert
 * (pour savoir sur quelles grilles on est, jamais cliquable) et la roue dentée, seule, qui passe par
 * le contrôle des adultes. En bas quand l'écran est en hauteur ; en rail à droite quand il est en
 * largeur, où la place est sur les côtés (en bas, les grandes cases d'un téléphone couché passeraient
 * sous 48 dp). En bas, son fond se prolonge sous la barre de navigation du système.
 */
@Composable
fun BoardAdultBar(profileName: String?, isRail: Boolean, onSettingsClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    if (isRail) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = modifier
                .fillMaxHeight()
                .background(background)
                .width(ADULT_BAR_THICKNESS)
                .padding(vertical = BAR_PADDING),
        ) {
            // En rail, 48 dp de large : l'initiale seule, le prénom entier pour TalkBack.
            if (profileName != null) InitialBadge(stringResource(R.string.adult_bar_profile_description, profileName), profileName) else Spacer(modifier = Modifier.size(INITIAL_BADGE_SIZE))
            SettingsButton(onSettingsClick)
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .background(background)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .height(ADULT_BAR_THICKNESS)
                .padding(start = NAME_START_PADDING, end = BAR_PADDING),
        ) {
            // Un seul arrêt pour TalkBack, avec son contexte : « Utilisateur ouvert : Léo ».
            val nameDescription = profileName?.let { stringResource(R.string.adult_bar_profile_description, it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { if (nameDescription != null) contentDescription = nameDescription },
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
            SettingsButton(onSettingsClick)
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_open), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** L'initiale à l'écran, le prénom et son contexte pour TalkBack, en un seul arrêt. */
@Composable
private fun InitialBadge(description: String, name: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(INITIAL_BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        // Taille fixe comme la pastille : la police agrandie la rognerait (le prénom est lu par TalkBack).
        val fontSize = with(LocalDensity.current) { INITIAL_TEXT_SIZE.toSp() }
        Text(initialOf(name), style = MaterialTheme.typography.titleSmall.copy(fontSize = fontSize, lineHeight = fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
