package com.wobblebot.papotons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Profile
import com.wobblebot.papotons.ui.about.AboutDialog
import com.wobblebot.papotons.ui.board.SettingFailure
import kotlinx.collections.immutable.persistentListOf

private val SETTINGS_PADDING = 16.dp
private val SECTION_SPACING = 8.dp

/**
 * L'écran des adultes, en plein écran, ouvert derrière le contrôle parental. Son ouverture vient du
 * ViewModel (jamais restaurée après la mort du processus) ; « À propos » vit avec lui : gardé à la
 * rotation, parti avec sa fermeture.
 */
@Composable
fun SettingsDialog(
    /** La taille de la grille ouverte, `null` pour un écran qui n'est l'accueil d'aucune taille. */
    boardSize: BoardSize?,
    settingFailure: SettingFailure?,
    profiles: ProfilesUiState,
    profileActions: ProfileActions,
    onBoardSizeChosen: (BoardSize) -> Unit,
    onEditBoards: () -> Unit,
    onBackup: () -> Unit,
    onClose: () -> Unit,
) {
    var isAboutOpen by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.settings_title)
    Dialog(
        onDismissRequest = onClose,
        // Bord à bord : safeDrawingPadding tient lui-même compte des barres système. Le titre de
        // fenêtre est ce que TalkBack annonce à l'ouverture.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
            windowTitle = title,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                // Hors du défilement : à police agrandie, « Fermer » reste à portée.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = SETTINGS_PADDING, top = SETTINGS_PADDING, end = SETTINGS_PADDING),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.settings_close))
                    }
                }
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(SETTINGS_PADDING),
                    verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
                ) {
                    ProfilesSection(profiles, profileActions, SETTINGS_PADDING)
                    HorizontalDivider()
                    SettingsEntry(R.string.edit_open, R.string.settings_edit_description, onEditBoards)
                    HorizontalDivider()
                    val openProfileName = profiles.profiles.firstOrNull { it.id == profiles.openProfileId }?.name
                    BoardSizeChoice(boardSize, openProfileName, settingFailure, onBoardSizeChosen)
                    HorizontalDivider()
                    SettingsEntry(R.string.backup_open, R.string.settings_backup_description, onBackup)
                    HorizontalDivider()
                    SettingsEntry(R.string.about_open, R.string.settings_about_description) { isAboutOpen = true }
                }
            }
        }
    }
    if (isAboutOpen) {
        AboutDialog(onClose = { isAboutOpen = false })
    }
}

/** Une ligne entière cliquable : titre et explication sont lus d'un bloc par TalkBack. */
@Composable
private fun SettingsEntry(@StringRes title: Int, @StringRes description: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    )
}

private val BoardSize.title: Int
    @StringRes get() = when (this) {
        BoardSize.LARGE_CELLS -> R.string.board_size_large_cells
        BoardSize.MEDIUM -> R.string.board_size_medium
        BoardSize.STANDARD -> R.string.board_size_standard
    }

private val BoardSize.description: Int
    @StringRes get() = when (this) {
        BoardSize.LARGE_CELLS -> R.string.board_size_large_cells_description
        BoardSize.MEDIUM -> R.string.board_size_medium_description
        BoardSize.STANDARD -> R.string.board_size_standard_description
    }

/** Appliquée dès l'appui : la grille de la taille choisie s'ouvre derrière les Réglages. */
@Composable
private fun BoardSizeChoice(current: BoardSize?, profileName: String?, failure: SettingFailure?, onChosen: (BoardSize) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            // Elle ne vaut que pour l'utilisateur ouvert.
            text = if (profileName != null) stringResource(R.string.settings_board_size_of, profileName) else stringResource(R.string.settings_board_size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = SETTINGS_PADDING)
                .semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_board_size_explanation),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = SETTINGS_PADDING),
        )
        for (size in BoardSize.entries) {
            ListItem(
                leadingContent = { RadioButton(selected = size == current, onClick = null) },
                headlineContent = { Text(stringResource(size.title)) },
                supportingContent = { Text(stringResource(size.description)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = size == current, role = Role.RadioButton, onClick = { onChosen(size) }),
            )
        }
        if (failure != null) {
            val message = when (failure) {
                SettingFailure.NotSaved -> R.string.settings_board_size_failed
                SettingFailure.Unavailable -> R.string.settings_board_size_unavailable
            }
            Text(
                text = stringResource(message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = SETTINGS_PADDING)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Preview
@Composable
private fun SettingsDialogPreview() {
    SettingsDialog(
        boardSize = BoardSize.STANDARD,
        settingFailure = null,
        profiles = ProfilesUiState(persistentListOf(Profile(1, "Léo"), Profile(2, "Anna")), openProfileId = 1),
        profileActions = ProfileActions(onChosen = {}, onCreate = {}, onRename = { _, _ -> }, onDelete = {}),
        onBoardSizeChosen = {},
        onEditBoards = {},
        onBackup = {},
        onClose = {},
    )
}
