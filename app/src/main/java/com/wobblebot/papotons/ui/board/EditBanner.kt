package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R

private val BANNER_SPACING = 8.dp
private const val BANNER_MAX_LINES = 2
private val BADGE_SPACING = 6.dp

/**
 * Largeur de bannière, à police normale, en dessous de laquelle « Sauvegarde » n'est plus qu'une
 * icône : écrit, il ne laisserait plus rien au titre « Modification » (téléphone, police agrandie).
 */
private val WRITTEN_BACKUP_MIN_WIDTH = 360.dp

/**
 * À la place de la phrase en édition, dans la même barre à hauteur fixe : rien ne bouge (invariant 3).
 * [hasMoveFailed] : un déplacement de case a échoué, dit à la place du titre (la barre ne grandit pas).
 */
@Composable
fun EditBanner(onDone: () -> Unit, onBackup: () -> Unit, hasUnsavedChanges: Boolean, modifier: Modifier = Modifier, hasMoveFailed: Boolean = false) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        val isBackupWritten = maxWidth >= WRITTEN_BACKUP_MIN_WIDTH * LocalDensity.current.fontScale
        BannerRow(onDone, onBackup, hasUnsavedChanges, isBackupWritten, hasMoveFailed)
    }
}

@Composable
private fun BannerRow(onDone: () -> Unit, onBackup: () -> Unit, hasUnsavedChanges: Boolean, isBackupWritten: Boolean, hasMoveFailed: Boolean) {
    Row(
        modifier = Modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(BANNER_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Court : à police agrandie, une consigne longue se couperait. Annoncé à l'entrée en édition.
        if (hasMoveFailed) {
            // Annoncé tout de suite : le doigt vient de lâcher la case, rien n'a bougé.
            Text(
                text = stringResource(R.string.edit_move_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = BANNER_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        } else {
            Text(
                text = stringResource(R.string.edit_banner),
                style = MaterialTheme.typography.titleMedium,
                maxLines = BANNER_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        // Une pastille, et ses mots pour TalkBack : la couleur seule ne dit rien.
        val unsaved = stringResource(R.string.backup_unsaved_badge)
        if (isBackupWritten) {
            OutlinedButton(onClick = onBackup) {
                Text(stringResource(R.string.backup_open))
                if (hasUnsavedChanges) {
                    Spacer(modifier = Modifier.width(BADGE_SPACING))
                    Badge(modifier = Modifier.semantics { contentDescription = unsaved })
                }
            }
        } else {
            IconButton(onClick = onBackup) {
                BadgedBox(badge = { if (hasUnsavedChanges) Badge(modifier = Modifier.semantics { contentDescription = unsaved }) }) {
                    Icon(painterResource(R.drawable.ic_save), stringResource(R.string.backup_open))
                }
            }
        }
        Button(onClick = onDone) {
            Text(stringResource(R.string.edit_done))
        }
    }
}
