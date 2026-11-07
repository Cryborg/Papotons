package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.board.MAX_SCREENS
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Screen
import com.wobblebot.papotons.ui.board.TARGET_SPACING
import kotlinx.collections.immutable.ImmutableList

/** Les actions sur les écrans, regroupées pour la signature des Réglages. */
@Immutable
data class ScreenActions(
    val onCreate: (String, BoardSize) -> Unit,
    val onRename: (Long, String) -> Unit,
    val onDelete: (Long) -> Unit,
    val onMove: (screenId: Long, offset: Int) -> Unit,
)

/** Pas de modale ouverte. */
private const val NO_SCREEN_EDITED = -1L

/** La place d'un bouton absent (monter au premier, descendre au dernier) : les autres ne bougent pas d'une ligne à l'autre. */
private val ICON_BUTTON_SIZE = 48.dp

/** Pour « Ajouter un écran ». */
private const val NEW_SCREEN = 0L

/**
 * Les écrans de l'utilisateur ouvert, dans l'ordre des onglets : monter, descendre, modifier, ajouter.
 * [newScreenSize] : la taille présélectionnée d'un nouvel écran (celle de l'écran affiché). La modale en
 * cours vit en `remember`, comme celle des utilisateurs.
 */
@Composable
fun ScreensPage(screens: ImmutableList<Screen>, state: ScreensUiState, newScreenSize: BoardSize, actions: ScreenActions) {
    var editedScreenId by remember { mutableLongStateOf(NO_SCREEN_EDITED) }
    // L'ordre d'avant un « Monter » : les flèches attendent l'ordre relu, pas seulement la fin de l'écriture.
    // Sinon un double appui tomberait sur la ligne voisine, déjà montée sous le doigt, et défairait le premier.
    // Un échec n'a rien écrit : les flèches reviennent pour réessayer, même après deux échecs de suite.
    var orderBeforeMove by remember { mutableStateOf<ImmutableList<Screen>?>(null) }
    val canMove = !state.isWorking && (state.failure != null || orderBeforeMove != screens)
    PageExplanation(stringResource(R.string.settings_screens_explanation))
    SettingsCard {
        screens.forEachIndexed { index, screen ->
            if (index > 0) SettingsDivider()
            // Par écran, pas par rang : après « Monter », le focus de TalkBack suit l'écran déplacé.
            key(screen.id) {
                ScreenRow(
                    screen = screen,
                    isFirst = index == 0,
                    isLast = index == screens.lastIndex,
                    isEnabled = !state.isWorking,
                    canMove = canMove,
                    onMove = { offset ->
                        orderBeforeMove = screens
                        actions.onMove(screen.id, offset)
                    },
                    onEdit = { editedScreenId = screen.id },
                )
            }
        }
    }
    val isFull = screens.size >= MAX_SCREENS
    AddButton(stringResource(R.string.screen_add), enabled = !state.isWorking && !isFull) { editedScreenId = NEW_SCREEN }
    if (isFull) PageExplanation(stringResource(R.string.screen_limit_reached, MAX_SCREENS))
    state.failure?.let { failure -> FailureText(stringResource(failure.message)) }
    val close = { editedScreenId = NO_SCREEN_EDITED }
    if (editedScreenId == NEW_SCREEN) {
        ScreenDialog(
            target = ScreenDialogTarget.New(newScreenSize),
            onSave = { name, size ->
                close()
                actions.onCreate(name, size)
            },
            onDelete = close,
            onClose = close,
        )
    } else {
        screens.firstOrNull { it.id == editedScreenId }?.let { screen ->
            // Un état par écran : jamais le brouillon d'un autre.
            key(screen.id) {
                ScreenDialog(
                    target = ScreenDialogTarget.Existing(screen.name, canDelete = screens.size > 1),
                    onSave = { name, _ ->
                        close()
                        if (name != screen.name) actions.onRename(screen.id, name)
                    },
                    onDelete = {
                        close()
                        actions.onDelete(screen.id)
                    },
                    onClose = close,
                )
            }
        }
    }
}

/** Le nom et la taille, puis trois boutons : monter et descendre (absents aux bouts, la place reste), modifier. */
@Composable
private fun ScreenRow(screen: Screen, isFirst: Boolean, isLast: Boolean, isEnabled: Boolean, canMove: Boolean, onMove: (Int) -> Unit, onEdit: () -> Unit) {
    Row(
        // Monter, descendre, modifier : trois cibles voisines, jamais collées.
        horizontalArrangement = Arrangement.spacedBy(TARGET_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(start = ROW_HORIZONTAL_PADDING),
    ) {
        // Un seul arrêt TalkBack : « Maison, Grandes cases (3 × 4) ».
        Column(modifier = Modifier.weight(1f).padding(vertical = ROW_VERTICAL_PADDING).semantics(mergeDescendants = true) {}) {
            Text(text = screen.name, style = MaterialTheme.typography.bodyLarge, maxLines = NAME_MAX_LINES, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(screen.size?.title() ?: R.string.settings_board_size_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isFirst) {
                Text(text = stringResource(R.string.screen_first), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (isFirst) {
            Spacer(modifier = Modifier.size(ICON_BUTTON_SIZE))
        } else {
            IconButton(onClick = { onMove(-1) }, enabled = canMove) {
                Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.screen_move_up_description, screen.name))
            }
        }
        if (isLast) {
            Spacer(modifier = Modifier.size(ICON_BUTTON_SIZE))
        } else {
            IconButton(onClick = { onMove(1) }, enabled = canMove) {
                Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = stringResource(R.string.screen_move_down_description, screen.name))
            }
        }
        IconButton(onClick = onEdit, enabled = isEnabled) {
            Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.screen_edit_description, screen.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal val ScreenFailure.message: Int
    get() = when (this) {
        ScreenFailure.NotCreated -> R.string.screen_not_created
        ScreenFailure.NotRenamed -> R.string.screen_not_renamed
        ScreenFailure.NotDeleted -> R.string.screen_not_deleted
        ScreenFailure.NotMoved -> R.string.screen_not_moved
    }
