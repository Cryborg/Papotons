package com.wobblebot.papotons.ui.settings

import android.os.Build
import android.util.Log
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.domain.Profile
import com.wobblebot.papotons.domain.Screen
import com.wobblebot.papotons.ui.about.AboutDialog
import com.wobblebot.papotons.ui.hideNavigationBar
import com.wobblebot.papotons.update.UpdateReadyDialog
import com.wobblebot.papotons.update.UpdateStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val TAG = "SettingsDialog"
private val HEADER_MIN_HEIGHT = 64.dp
private val HEADER_PADDING = 4.dp
private val HEADER_TITLE_SPACING = 8.dp
private val PROFILE_CARD_PADDING = 16.dp
private val PROFILE_CARD_SPACING = 16.dp

/** Un titre de page tient sur deux lignes à police agrandie ; TalkBack lit le titre entier (`paneTitle`). */
private const val TITLE_MAX_LINES = 2

/** Un prénom long passe à la ligne plutôt que d'être réduit à quelques lettres par « Changer ». */
private const val PROFILE_NAME_MAX_LINES = 2

/** Largeur de carte, à police normale, sous laquelle « Changer » passe sous le prénom ; grandit avec la police. */
private val PROFILE_CARD_BUTTON_BESIDE_MIN_WIDTH = 300.dp

/** Ce qu'il faut aux Réglages en plus de la page ouverte. */
@Immutable
data class SettingsContent(
    /** Les écrans de l'utilisateur ouvert, dans l'ordre des onglets ; vide tant qu'aucune grille n'est affichée. */
    val screens: ImmutableList<Screen>,
    val screensState: ScreensUiState,
    /** La taille présélectionnée d'un nouvel écran : celle de l'écran affiché. */
    val newScreenSize: BoardSize,
    val profiles: ProfilesUiState,
    val caregivers: CaregiversUiState,
    val hasUnsavedChanges: Boolean,
    /** L'appli est épinglée en ce moment (l'option cochée ne fait que le demander). */
    val isAppPinned: Boolean,
    val updateStatus: UpdateStatus,
)

/**
 * L'écran des adultes, en plein écran, ouvert derrière le contrôle : un accueil en sections, et une
 * sous-page par sujet. La page vit dans le ViewModel (jamais restaurée après la mort du processus). Le
 * retour arrière passe par `onDismissRequest`, seul chemin que prend aussi Échap : d'une sous-page à
 * l'accueil, de l'accueil à la fermeture. « À propos » et les modales ne sont jamais sauvés (une
 * rotation les ferme) : sauvés, ils se rouvriraient seuls après la mort du processus.
 */
@Composable
fun SettingsDialog(
    page: SettingsPage,
    content: SettingsContent,
    profileActions: ProfileActions,
    caregiverActions: CaregiverActions,
    screenActions: ScreenActions,
    onPageChosen: (SettingsPage) -> Unit,
    onEditBoards: () -> Unit,
    onBackup: () -> Unit,
    onStartUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onClose: () -> Unit,
) {
    var isAboutOpen by remember { mutableStateOf(false) }
    var isUpdateConfirmOpen by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_title)
    val back = { if (page == SettingsPage.Home) onClose() else onPageChosen(SettingsPage.Home) }
    Dialog(
        onDismissRequest = back,
        // Bord à bord : safeDrawingPadding tient lui-même compte des barres système. Le titre de
        // fenêtre est ce que TalkBack annonce à l'ouverture ; chaque page porte ensuite le sien.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
            windowTitle = title,
        ),
    ) {
        // Les icônes des barres système, claires par défaut, disparaissent sur le fond clair. On les
        // passe en sombre sur toutes les versions, après avoir rendu les barres transparentes là où la
        // plateforme pourrait encore les peindre (avant Android 15) : jamais d'icônes sombres sur fond sombre.
        val view = LocalView.current
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (dialogWindow == null) {
                Log.w(TAG, "Settings window not found, system bar icons left as they are")
            } else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) makeSystemBarsTransparent(dialogWindow)
                val controller = WindowCompat.getInsetsController(dialogWindow, view)
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
                // Fenêtre à part : le masquage de l'activité ne vaut pas pour elle.
                hideNavigationBar(controller)
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                val pageTitle = pageTitle(page, content)
                SettingsHeader(page, pageTitle, onBack = back)
                // L'accueil garde sa position au retour d'une sous-page ; une sous-page s'ouvre en haut.
                val homeScroll = rememberScrollState()
                key(page) {
                    val pageScroll = if (page == SettingsPage.Home) homeScroll else rememberScrollState()
                    Column(
                        modifier = Modifier
                            .verticalScroll(pageScroll)
                            .padding(PAGE_PADDING)
                            .semantics { paneTitle = pageTitle },
                        verticalArrangement = pageArrangement,
                    ) {
                        when (page) {
                            SettingsPage.Home -> SettingsHome(
                                content = content,
                                onPageChosen = onPageChosen,
                                onEditBoards = onEditBoards,
                                onExitLockChanged = profileActions.onExitLockChanged,
                                onBackup = onBackup,
                                onStartUpdate = onStartUpdate,
                                onInstallUpdate = { isUpdateConfirmOpen = true },
                                onAbout = { isAboutOpen = true },
                            )
                            SettingsPage.Profiles -> ProfilesPage(content.profiles, profileActions)
                            SettingsPage.Caregivers -> CaregiversPage(content.caregivers, caregiverActions)
                            SettingsPage.Screens -> ScreensPage(content.screens, content.screensState, content.newScreenSize, screenActions)
                        }
                    }
                }
            }
        }
    }
    if (isAboutOpen) {
        AboutDialog(onClose = { isAboutOpen = false })
    }
    if (isUpdateConfirmOpen) {
        UpdateReadyDialog(onInstall = onInstallUpdate, onLater = { isUpdateConfirmOpen = false })
    }
}

@Composable
private fun pageTitle(page: SettingsPage, content: SettingsContent): String = when (page) {
    SettingsPage.Home -> stringResource(R.string.settings_title)
    SettingsPage.Profiles -> stringResource(R.string.settings_profiles)
    SettingsPage.Caregivers -> stringResource(R.string.settings_caregivers)
    SettingsPage.Screens -> content.profiles.openProfileName()
        ?.let { stringResource(R.string.settings_screens_of, it) }
        ?: stringResource(R.string.settings_screens)
}

/** Fermer à l'accueil, revenir en sous-page ; le titre est le titre de la page. */
@Composable
private fun SettingsHeader(page: SettingsPage, title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HEADER_MIN_HEIGHT)
            .padding(horizontal = HEADER_PADDING),
    ) {
        IconButton(onClick = onBack) {
            if (page == SettingsPage.Home) {
                Icon(painterResource(R.drawable.ic_clear), stringResource(R.string.settings_close))
            } else {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.settings_back))
            }
        }
        Spacer(modifier = Modifier.width(HEADER_TITLE_SPACING))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}

@Composable
private fun ColumnScope.SettingsHome(
    content: SettingsContent,
    onPageChosen: (SettingsPage) -> Unit,
    onEditBoards: () -> Unit,
    onExitLockChanged: (profileId: Long, isLocked: Boolean) -> Unit,
    onBackup: () -> Unit,
    onStartUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onAbout: () -> Unit,
) {
    val openProfile = content.profiles.openProfile()
    val openName = openProfile?.name
    if (openName != null) OpenProfileCard(openName, onChange = { onPageChosen(SettingsPage.Profiles) })
    SettingsSection(if (openName != null) stringResource(R.string.settings_section_grids_of, openName) else stringResource(R.string.settings_section_grids)) {
        SettingsRow(R.drawable.ic_edit, gridBadgeColors(), stringResource(R.string.edit_open), stringResource(R.string.settings_edit_description), onEditBoards)
        SettingsDivider()
        SettingsRow(
            icon = R.drawable.ic_grid_view,
            badgeColors = gridBadgeColors(),
            title = stringResource(R.string.settings_screens),
            // Grille pas encore lue : rien, plutôt qu'une liste vide à tort.
            summary = content.screens.takeIf { it.isNotEmpty() }?.joinToString(separator = stringResource(R.string.names_separator)) { it.name },
            summaryMaxLines = NAMES_SUMMARY_MAX_LINES,
            onClick = { onPageChosen(SettingsPage.Screens) },
        )
        if (openProfile != null) {
            SettingsDivider()
            SettingsSwitchRow(
                icon = R.drawable.ic_exit_to_app,
                badgeColors = gridBadgeColors(),
                title = stringResource(R.string.settings_exit_lock),
                summary = stringResource(R.string.settings_exit_lock_description),
                isChecked = openProfile.isExitLocked,
                enabled = !content.profiles.isWorking,
                onCheckedChange = { onExitLockChanged(openProfile.id, it) },
            )
            // Coché ne veut pas dire épinglé : la confirmation d'Android a pu être refusée, souvent par l'enfant.
            if (openProfile.isExitLocked && !content.isAppPinned) {
                PageExplanation(stringResource(R.string.settings_exit_lock_not_pinned), Modifier.padding(bottom = ROW_VERTICAL_PADDING))
            }
            // Seul échec qui naît sur l'accueil ; les autres restent sur la page des utilisateurs.
            if (content.profiles.failure == ProfileFailure.ExitLockNotSaved) FailureText(stringResource(ProfileFailure.ExitLockNotSaved.message), Modifier.padding(bottom = ROW_VERTICAL_PADDING))
        }
    }
    SettingsSection(stringResource(R.string.settings_section_people)) {
        SettingsRow(
            icon = R.drawable.ic_group,
            badgeColors = peopleBadgeColors(),
            title = stringResource(R.string.settings_profiles),
            summary = content.profiles.profiles.joinToString(separator = stringResource(R.string.names_separator)) { it.name },
            summaryMaxLines = NAMES_SUMMARY_MAX_LINES,
            onClick = { onPageChosen(SettingsPage.Profiles) },
        )
        SettingsDivider()
        val caregivers = content.caregivers.caregivers
        SettingsRow(
            icon = R.drawable.ic_lock,
            badgeColors = peopleBadgeColors(),
            title = stringResource(R.string.settings_caregivers),
            // Inconnu tant que le fichier n'est pas lu : rien, plutôt que « Aucun » à tort.
            summary = when {
                caregivers == null -> null
                caregivers.isEmpty() -> stringResource(R.string.settings_caregivers_summary_none)
                else -> caregivers.joinToString(separator = stringResource(R.string.names_separator)) { it.name }
            },
            summaryMaxLines = if (caregivers.isNullOrEmpty()) Int.MAX_VALUE else NAMES_SUMMARY_MAX_LINES,
            onClick = { onPageChosen(SettingsPage.Caregivers) },
        )
    }
    SettingsSection(stringResource(R.string.settings_section_app)) {
        SettingsRow(
            icon = R.drawable.ic_save,
            badgeColors = appBadgeColors(),
            title = stringResource(R.string.backup_open),
            summary = stringResource(if (content.hasUnsavedChanges) R.string.settings_backup_unsaved else R.string.settings_backup_saved),
            onClick = onBackup,
        )
        UpdateRow(content.updateStatus, onStartUpdate, onInstallUpdate)
        SettingsDivider()
        SettingsRow(
            icon = R.drawable.ic_info,
            badgeColors = appBadgeColors(),
            title = stringResource(R.string.about_open),
            summary = stringResource(R.string.settings_about_summary, AppVersion.current.versionName),
            onClick = onAbout,
        )
    }
}

/**
 * La mise à jour Play, seul endroit où elle se montre : derrière le contrôle, jamais devant la grille.
 * Absente quand il n'y a rien à proposer (ou hors du Play Store).
 */
@Composable
private fun UpdateRow(status: UpdateStatus, onStartUpdate: () -> Unit, onInstallUpdate: () -> Unit) {
    // Annoncée à chaque changement : la ligne touchée disparaît au retour de la fenêtre de Play, et le
    // téléchargement finit pendant que l'adulte est ailleurs. Le conteneur existe toujours, même vide :
    // TalkBack n'annonce que les changements d'un nœud déjà là.
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { UpdateRowContent(status, onStartUpdate, onInstallUpdate) }
}

@Composable
private fun UpdateRowContent(status: UpdateStatus, onStartUpdate: () -> Unit, onInstallUpdate: () -> Unit) {
    when (status) {
        UpdateStatus.None -> Unit
        UpdateStatus.Available -> {
            SettingsDivider()
            SettingsRow(R.drawable.ic_arrow_downward, appBadgeColors(), stringResource(R.string.update_available), stringResource(R.string.update_available_summary), onStartUpdate)
        }
        UpdateStatus.Downloading -> {
            SettingsDivider()
            PageExplanation(stringResource(R.string.update_downloading), Modifier.padding(vertical = ROW_VERTICAL_PADDING))
        }
        UpdateStatus.ReadyToInstall -> {
            SettingsDivider()
            SettingsRow(R.drawable.ic_arrow_downward, appBadgeColors(), stringResource(R.string.update_ready_row), stringResource(R.string.update_ready_row_summary), onInstallUpdate)
        }
    }
}

/** Avant Android 15 seulement : ensuite, la plateforme ignore ces couleurs (bord à bord imposé). */
@Suppress("DEPRECATION")
private fun makeSystemBarsTransparent(window: Window) {
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
}

/**
 * En tête de l'accueil : sur quelles grilles on travaille, et le chemin pour en changer. Carte étroite
 * (zoom, police agrandie) : « Changer » passe sous le prénom, qui garderait sinon quelques lettres.
 */
@Composable
private fun OpenProfileCard(name: String, onChange: () -> Unit) {
    val colors = gridBadgeColors()
    Card(colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val isButtonBeside = maxWidth >= PROFILE_CARD_BUTTON_BESIDE_MIN_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(modifier = Modifier.padding(PROFILE_CARD_PADDING)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NameBadge(name, BadgeColors(MaterialTheme.colorScheme.surface, colors.content))
                    Spacer(modifier = Modifier.width(PROFILE_CARD_SPACING))
                    // Un seul arrêt TalkBack : « Léo, Utilisateur ouvert ».
                    Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = PROFILE_NAME_MAX_LINES, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.settings_open_profile), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isButtonBeside) ChangeProfileButton(colors, onChange)
                }
                if (!isButtonBeside) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { ChangeProfileButton(colors, onChange) }
                }
            }
        }
    }
}

@Composable
private fun ChangeProfileButton(colors: BadgeColors, onChange: () -> Unit) {
    val changeDescription = stringResource(R.string.settings_change_profile_description)
    TextButton(onClick = onChange) {
        // « Changer » seul ne dit pas quoi, lu par TalkBack ou dit à Voice Access.
        Text(
            text = stringResource(R.string.settings_change_profile),
            color = colors.content,
            modifier = Modifier.clearAndSetSemantics { contentDescription = changeDescription },
        )
    }
}

/** L'utilisateur ouvert, `null` tant qu'il n'est pas connu. */
internal fun ProfilesUiState.openProfile(): Profile? = profiles.firstOrNull { it.id == openProfileId }

/** Le prénom de l'utilisateur ouvert, `null` tant qu'il n'est pas connu. */
internal fun ProfilesUiState.openProfileName(): String? = openProfile()?.name

@Preview
@Composable
private fun SettingsDialogPreview() {
    SettingsDialog(
        page = SettingsPage.Home,
        content = SettingsContent(
            screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD), Screen(1000, "Maison", BoardSize.LARGE_CELLS)),
            screensState = ScreensUiState(),
            newScreenSize = BoardSize.STANDARD,
            profiles = ProfilesUiState(persistentListOf(Profile(1, "Léo", isExitLocked = true), Profile(2, "Anna", isExitLocked = false)), openProfileId = 1),
            caregivers = CaregiversUiState(persistentListOf(Caregiver(1, "Marie", codeResetAtMs = null))),
            hasUnsavedChanges = true,
            isAppPinned = false,
            updateStatus = UpdateStatus.ReadyToInstall,
        ),
        profileActions = ProfileActions(onChosen = {}, onCreate = {}, onRename = { _, _ -> }, onDelete = {}, onExitLockChanged = { _, _ -> }),
        caregiverActions = CaregiverActions(onSave = { _, _, _ -> }, onDelete = {}),
        screenActions = ScreenActions(onCreate = { _, _ -> }, onRename = { _, _ -> }, onDelete = {}, onMove = { _, _ -> }),
        onPageChosen = {},
        onEditBoards = {},
        onBackup = {},
        onStartUpdate = {},
        onInstallUpdate = {},
        onClose = {},
    )
}
