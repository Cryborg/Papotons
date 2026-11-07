package com.wobblebot.papotons.ui.board

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.app.OnMultiWindowModeChangedProvider
import androidx.core.util.Consumer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceToken
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.backup.BackupDialog
import com.wobblebot.papotons.ui.backup.BackupUiState
import com.wobblebot.papotons.ui.backup.BackupViewModel
import com.wobblebot.papotons.ui.backup.RestoreConfirmDialog
import com.wobblebot.papotons.ui.edit.CellEditorDialog
import com.wobblebot.papotons.ui.edit.AdultGateDialog
import com.wobblebot.papotons.ui.edit.PictogramSearchDialog
import com.wobblebot.papotons.ui.settings.CaregiverActions
import com.wobblebot.papotons.ui.settings.CaregiversViewModel
import com.wobblebot.papotons.ui.settings.ProfileActions
import com.wobblebot.papotons.ui.settings.ProfilesViewModel
import com.wobblebot.papotons.ui.settings.ScreenActions
import com.wobblebot.papotons.ui.settings.ScreensViewModel
import com.wobblebot.papotons.ui.settings.SettingsContent
import com.wobblebot.papotons.ui.settings.SettingsDialog
import com.wobblebot.papotons.ui.settings.SettingsPage
import com.wobblebot.papotons.ui.settings.openProfile
import com.wobblebot.papotons.ui.settings.openProfileName
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

private const val TAG = "BoardScreen"

/** Réglages de synthèse vocale d'Android : action non publique, d'où les replis. */
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

/** Avec `application/zip`, le stockage local ajouterait `.zip` au nom proposé. */
private const val BACKUP_MIME_TYPE = "application/octet-stream"
private const val ANY_MIME_TYPE = "*/*"

@Immutable
data class BoardActions(
    val onCellClick: (Cell) -> Unit,
    val onBack: () -> Unit,
    val onHome: () -> Unit,
    val onSpeakMessage: () -> Unit,
    val onDeleteLastWord: () -> Unit,
    val onClearMessage: () -> Unit,
    val onSpeechAlertClick: () -> Unit,
    val onMutedAlertClick: () -> Unit,
    val onLostBoardsAlertClick: () -> Unit,
    val onSettingsClick: () -> Unit,
    val onEmptySlotClick: (row: Int, column: Int) -> Unit,
    val onStopEditing: () -> Unit,
    val onBackupClick: () -> Unit,
    val onCellLongClick: (Cell) -> Unit = {},
    val onVerbFormClick: (String) -> Unit = {},
    val onLeaveClick: () -> Unit = {},
    val onScreenChosen: (Long) -> Unit = {},
    val onCellMoved: (cell: Cell, toRow: Int, toColumn: Int) -> Unit = { _, _, _ -> },
)

/** Ce qu'ouvre le contrôle des adultes (code d'un accompagnant, ou calcul) quand il est passé. */
private enum class GatedAction { OpenSettings, AcknowledgeLostBoards, RestoreBackup, LeaveApp }

/** Branche [BoardScreen] sur son ViewModel : retour arrière, premier plan, modale de la voix. */
@Composable
fun BoardRoute(
    viewModel: BoardViewModel,
    state: BoardUiState,
    backupViewModel: BackupViewModel = hiltViewModel(),
    profilesViewModel: ProfilesViewModel = hiltViewModel(),
    caregiversViewModel: CaregiversViewModel = hiltViewModel(),
    screensViewModel: ScreensViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val backupState by backupViewModel.uiState.collectAsStateWithLifecycle()
    val profilesState by profilesViewModel.uiState.collectAsStateWithLifecycle()
    val screensState by screensViewModel.uiState.collectAsStateWithLifecycle()
    // Collecté dès l'écran affiché : le contrôle ne doit pas attendre la lecture au moment de l'appui.
    val caregiversState by caregiversViewModel.uiState.collectAsStateWithLifecycle()
    val caregiverActions = remember(caregiversViewModel) {
        CaregiverActions(onSave = caregiversViewModel::onSave, onDelete = caregiversViewModel::onDelete)
    }
    val profileActions = remember(profilesViewModel) {
        ProfileActions(
            onChosen = profilesViewModel::onSwitch,
            onCreate = profilesViewModel::onCreate,
            onRename = profilesViewModel::onRename,
            onDelete = profilesViewModel::onDelete,
            onExitLockChanged = profilesViewModel::onExitLockChanged,
        )
    }
    val screenActions = remember(screensViewModel) {
        ScreenActions(
            onCreate = screensViewModel::onCreate,
            onRename = screensViewModel::onRename,
            onDelete = screensViewModel::onDelete,
            onMove = screensViewModel::onMove,
        )
    }
    val backupFileNamePattern = stringResource(R.string.backup_file_name)
    var isSpeechDialogOpen by rememberSaveable { mutableStateOf(false) }
    var areSettingsUnreachable by rememberSaveable { mutableStateOf(false) }
    var isLostBoardsDialogOpen by rememberSaveable { mutableStateOf(false) }
    // Devant le contrôle, pas derrière : le restaurer ne fait que redemander la réponse.
    var gatedAction by rememberSaveable { mutableStateOf<GatedAction?>(null) }
    val activity = LocalActivity.current
    val canTakePhoto = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onCameraResult)
    val choosePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), viewModel::onPhotoResult)
    // Revenu trop tard (l'adulte est parti), un document termine l'édition comme une photo.
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { document ->
        if (!backupViewModel.onSaveDocument(document)) viewModel.onStopEditing()
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document ->
        if (!backupViewModel.onRestoreDocument(document)) viewModel.onStopEditing()
    }
    val isExitLocked = profilesState.openProfile()?.isExitLocked
    // Suivi par l'écouteur d'Android : une fenêtre libre n'entraîne pas toujours la recréation de l'activité.
    var isInMultiWindow by remember { mutableStateOf(activity?.isInMultiWindowMode == true) }
    DisposableEffect(activity) {
        val provider = activity as? OnMultiWindowModeChangedProvider
        val listener = Consumer<MultiWindowModeChangedInfo> { isInMultiWindow = it.isInMultiWindowMode }
        provider?.addOnMultiWindowModeChangedListener(listener)
        onDispose { provider?.removeOnMultiWindowModeChangedListener(listener) }
    }
    // Lu à l'appui, pas capturé : les actions ne se recréent pas quand l'option change.
    val isLeaveGated by rememberUpdatedState(needsGateToLeave(isExitLocked, state.isEditing))
    val actions = remember(viewModel) {
        BoardActions(
            onCellClick = viewModel::onCellClick,
            onBack = viewModel::onBack,
            onHome = viewModel::onHome,
            onSpeakMessage = viewModel::onSpeakMessage,
            onDeleteLastWord = viewModel::onDeleteLastWord,
            onClearMessage = viewModel::onClearMessage,
            onSpeechAlertClick = {
                areSettingsUnreachable = false
                isSpeechDialogOpen = true
            },
            onMutedAlertClick = viewModel::onMutedAlertClick,
            onLostBoardsAlertClick = { isLostBoardsDialogOpen = true },
            onSettingsClick = { if (!viewModel.onSettingsRequested()) gatedAction = GatedAction.OpenSettings },
            onEmptySlotClick = viewModel::onEmptySlotClick,
            onStopEditing = viewModel::onStopEditing,
            onBackupClick = backupViewModel::onOpen,
            onCellLongClick = viewModel::onCellLongClick,
            onVerbFormClick = viewModel::onVerbFormClick,
            onLeaveClick = {
                if (isLeaveGated) gatedAction = GatedAction.LeaveApp else activity?.let(::leaveToHomeScreen)
            },
            onScreenChosen = viewModel::onScreenChosen,
            onCellMoved = viewModel::onCellMoved,
        )
    }
    // Utilisateur pas encore lu : pas d'épinglage (il serait demandé à chaque lancement d'un utilisateur
    // non protégé), mais Bureau reste derrière le contrôle.
    ExitLockEffect(isExitLocked = isExitLocked == true)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.recoverSpeech() }
    // Un vrai départ (écran éteint, autre appli) termine l'édition : la tablette rendue à l'enfant
    // n'y est jamais. Une rotation, qui passe aussi par ON_STOP, la garde.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // L'appli photo et le sélecteur font passer par ON_STOP sans que l'adulte soit parti.
        val isAwaitingOtherApp = viewModel.isAwaitingPhoto || backupViewModel.isAwaitingDocument
        val isLeaving = activity?.isChangingConfigurations != true
        if (isLeaving && !isAwaitingOtherApp) viewModel.onStopEditing()
        // Même en attente d'une autre appli : les Réglages n'en ouvrent aucune. Le contrôle non plus :
        // passé la multiplication de « Code oublié ? », il ouvrirait l'accès à qui reprend la tablette.
        if (isLeaving) {
            viewModel.onCloseSettings()
            gatedAction = null
        }
    }
    // À l'accueil, le retour arrière garde son sens habituel (quitter l'appli) ; en édition, il la termine.
    // La page des formes se referme aussi par le retour arrière, même ouverte depuis l'accueil.
    BackHandler(enabled = state.board?.canGoBack == true || state.verbForms != null, onBack = viewModel::onBack)
    BackHandler(enabled = state.isEditing && state.board?.canGoBack != true, onBack = viewModel::onStopEditing)
    // Les modales de sauvegarde n'existent qu'en édition, derrière le contrôle parental.
    LaunchedEffect(state.isEditing) {
        if (!state.isEditing) backupViewModel.onEditingEnded()
    }
    BoardScreen(
        state = state,
        actions = actions,
        hasUnsavedChanges = backupState.hasUnsavedChanges,
        profileName = profilesState.openProfileName(),
        isLeaveGated = isLeaveGated,
        isInMultiWindow = isInMultiWindow,
    )
    // Un échec d'une ouverture précédente ne s'affiche plus.
    LaunchedEffect(state.isSettingsOpen) {
        if (state.isSettingsOpen) {
            profilesViewModel.onShown()
            caregiversViewModel.onShown()
            screensViewModel.onShown()
        }
    }
    if (state.isSettingsOpen) {
        SettingsDialog(
            page = state.settingsPage ?: SettingsPage.Home,
            content = SettingsContent(
                screens = state.board?.screens ?: persistentListOf(),
                screensState = screensState,
                newScreenSize = state.boardSize ?: BoardSize.STANDARD,
                profiles = profilesState,
                caregivers = caregiversState,
                hasUnsavedChanges = backupState.hasUnsavedChanges,
                // Relu seulement Réglages ouverts.
                isAppPinned = rememberIsPinned(),
            ),
            profileActions = profileActions,
            caregiverActions = caregiverActions,
            screenActions = screenActions,
            onPageChosen = viewModel::onSettingsPageChosen,
            onEditBoards = viewModel::onStartEditing,
            // La sauvegarde n'existe qu'en édition : on y entre, comme « Restaurer » des grilles perdues.
            onBackup = {
                viewModel.onStartEditing()
                backupViewModel.onOpen()
            },
            onClose = viewModel::onCloseSettings,
        )
    }
    gatedAction?.let { action ->
        AdultGateDialog(
            state = caregiversState,
            onVerify = caregiversViewModel::verify,
            onRecover = caregiversViewModel::recover,
            onPassed = passed@{
                // Relu à l'appel : une vérification finie juste après un départ (ON_STOP a fermé le
                // contrôle, la recomposition n'est pas encore passée) n'ouvre rien.
                if (gatedAction != action) return@passed
                gatedAction = null
                when (action) {
                    GatedAction.OpenSettings -> viewModel.onOpenSettings()
                    GatedAction.AcknowledgeLostBoards -> viewModel.onAcknowledgeLostBoards()
                    GatedAction.RestoreBackup -> {
                        viewModel.onStartEditing()
                        backupViewModel.onOpen()
                    }
                    GatedAction.LeaveApp -> activity?.let(::leaveToHomeScreen)
                }
            },
            onClose = { gatedAction = null },
        )
    }
    state.editor?.let { editor ->
        CellEditorDialog(
            state = editor,
            onSave = viewModel::onEditorSave,
            onDelete = viewModel::onEditorDelete,
            onOpenFolder = viewModel::onEditorOpenFolder,
            onSearchPictogram = viewModel::onSearchOpen,
            onRemovePictogram = viewModel::onChosenPictogramRemoved,
            onTakePhoto = if (canTakePhoto) {
                {
                    try {
                        takePhoto.launch(viewModel.onCameraRequested())
                    } catch (exception: ActivityNotFoundException) {
                        Log.w(TAG, "No camera app", exception)
                        viewModel.onPhotoRequestFailed()
                    }
                }
            } else {
                null
            },
            onChoosePhoto = {
                try {
                    viewModel.onGalleryRequested()
                    choosePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (exception: ActivityNotFoundException) {
                    Log.w(TAG, "No photo picker", exception)
                    viewModel.onPhotoRequestFailed()
                }
            },
            onClose = viewModel::onEditorClose,
        )
    }
    state.pictogramSearch?.let { search ->
        PictogramSearchDialog(
            state = search,
            onSearch = viewModel::onSearch,
            onPick = viewModel::onSearchPick,
            onClose = viewModel::onSearchClose,
        )
    }
    if (state.isVolumeLockedDialogOpen) {
        VolumeLockedDialog(
            onOpenSettings = {
                // Aucun écran ouvert : la modale reste, la marche à suivre y est écrite.
                if (openSoundSettings(context)) viewModel.onVolumeLockedDialogClose()
            },
            onClose = viewModel::onVolumeLockedDialogClose,
        )
    }
    if (isLostBoardsDialogOpen && state.hasLostBoards) {
        LostBoardsDialog(
            // Derrière le contrôle parental : c'est souvent la personne elle-même qui ouvre la modale.
            onAcknowledge = {
                isLostBoardsDialogOpen = false
                gatedAction = GatedAction.AcknowledgeLostBoards
            },
            onRestore = {
                isLostBoardsDialogOpen = false
                gatedAction = GatedAction.RestoreBackup
            },
            onClose = { isLostBoardsDialogOpen = false },
        )
    }
    BackupDialogs(
        isEditing = state.isEditing,
        state = backupState,
        onSave = {
            val fileName = backupViewModel.onSaveRequested(backupFileNamePattern)
            try {
                saveBackup.launch(fileName)
            } catch (exception: ActivityNotFoundException) {
                Log.w(TAG, "No document creator", exception)
                backupViewModel.onDocumentRequestFailed()
            }
        },
        onRestore = {
            backupViewModel.onRestoreRequested()
            try {
                // Tout type : celui d'un `.obz` varie d'un fournisseur à l'autre.
                openBackup.launch(arrayOf(ANY_MIME_TYPE))
            } catch (exception: ActivityNotFoundException) {
                Log.w(TAG, "No document picker", exception)
                backupViewModel.onDocumentRequestFailed()
            }
        },
        onConfirmRestore = backupViewModel::onConfirmRestore,
        onCancelRestore = backupViewModel::onCancelRestore,
        onClose = backupViewModel::onClose,
    )
    // La voix revenue referme la modale pour de bon : une panne suivante ne doit pas la rouvrir
    // seule. Une relance en cours (Initializing, par exemple à la rotation) la masque sans la fermer.
    val isSpeechReady = state.speechStatus == SpeechStatus.Ready
    LaunchedEffect(isSpeechReady) {
        if (isSpeechReady) isSpeechDialogOpen = false
    }
    if (isSpeechDialogOpen && state.speechStatus.needsAttention) {
        SpeechUnavailableDialog(
            status = state.speechStatus,
            onRetry = {
                isSpeechDialogOpen = false
                viewModel.recoverSpeech()
            },
            onOpenSettings = {
                // Aucun écran ouvert : la modale reste, avec la marche à suivre à la main.
                areSettingsUnreachable = !openSpeechSettings(context)
                if (!areSettingsUnreachable) isSpeechDialogOpen = false
            },
            areSettingsUnreachable = areSettingsUnreachable,
            onClose = { isSpeechDialogOpen = false },
        )
    }
}

@Composable
private fun BackupDialogs(
    isEditing: Boolean,
    state: BackupUiState,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onClose: () -> Unit,
) {
    if (!isEditing) return
    val pending = state.pendingRestore
    if (pending != null) {
        RestoreConfirmDialog(
            fileName = pending.fileName,
            currentProfileNames = state.currentProfileNames,
            restoredProfileNames = remember(pending) { pending.profileNames.toImmutableList() },
            boardCount = pending.boardCount,
            cellCount = pending.cellCount,
            onConfirm = onConfirmRestore,
            onCancel = onCancelRestore,
        )
    } else if (state.isDialogOpen) {
        BackupDialog(state = state, onSave = onSave, onRestore = onRestore, onClose = onClose)
    }
}

@Composable
fun BoardScreen(
    state: BoardUiState,
    actions: BoardActions,
    modifier: Modifier = Modifier,
    hasUnsavedChanges: Boolean = true,
    /** Le prénom de l'utilisateur ouvert, `null` tant qu'il n'est pas lu. */
    profileName: String? = null,
    isLeaveGated: Boolean = true,
    /** Écran partagé ou fenêtre : Android n'y masque pas la barre de navigation. */
    isInMultiWindow: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Barre d'état, barre de titre d'une fenêtre libre et encoche seulement. Ni le clavier (édition, MVP-3) ni la barre de navigation,
        // masquée mais qui revient par-dessus (glissement, modale), ne doivent rétrécir la grille.
        // En écran partagé ou en fenêtre, Android ne la masque pas : elle couvrirait la barre des
        // adultes, sa place est réservée.
        val navigationInsets = if (isInMultiWindow) WindowInsets.navigationBars else WindowInsets(0)
        val insets = WindowInsets.statusBars.union(WindowInsets.captionBar).union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        Column(modifier = Modifier.windowInsetsPadding(insets.union(navigationInsets))) {
            BoardTopBar(
                // La page des formes se referme par « Retour », même ouverte depuis l'accueil.
                canGoBack = state.board?.canGoBack == true || state.verbForms != null,
                speechStatus = state.speechStatus,
                hasLostBoards = state.hasLostBoards,
                isMuted = state.isMuted,
                onBack = actions.onBack,
                onHome = actions.onHome,
                onSpeechAlertClick = actions.onSpeechAlertClick,
                onMutedAlertClick = actions.onMutedAlertClick,
                onLostBoardsAlertClick = actions.onLostBoardsAlertClick,
            ) {
                if (state.isEditing) {
                    EditBanner(
                        onDone = actions.onStopEditing,
                        onBackup = actions.onBackupClick,
                        hasUnsavedChanges = hasUnsavedChanges,
                        modifier = Modifier.weight(1f),
                        hasMoveFailed = state.hasMoveFailed,
                    )
                } else {
                    MessageBar(
                        words = state.message,
                        onSpeak = actions.onSpeakMessage,
                        onDeleteLastWord = actions.onDeleteLastWord,
                        onClear = actions.onClearMessage,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            BoardContent(state, actions, Modifier.weight(1f).fillMaxWidth())
            // Toujours là, même pendant le chargement : les Réglages restent atteignables. Les écrans y
            // arrivent avec la grille, dans une barre d'épaisseur fixe : la grille ne bouge pas.
            val board = state.board
            BoardAdultBar(
                profileName = profileName,
                isLeaveGated = isLeaveGated,
                onLeaveClick = actions.onLeaveClick,
                onSettingsClick = actions.onSettingsClick,
                screens = board?.screens ?: persistentListOf(),
                selectedScreenId = board?.rootId,
                onScreenChosen = actions.onScreenChosen,
            )
        }
    }
}

/** Ce qui occupe la place sous la barre du haut : chargement, page des formes d'un verbe, ou grille. */
@Composable
private fun BoardContent(state: BoardUiState, actions: BoardActions, modifier: Modifier) {
    val board = state.board
    val verbForms = state.verbForms
    if (board == null) {
        val loadingDescription = stringResource(R.string.board_loading)
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingDescription })
        }
    } else if (verbForms != null) {
        val pageTitle = stringResource(R.string.conjugation_page_title, verbForms.forms.infinitive)
        VerbFormsGrid(
            page = verbForms,
            onFormClick = actions.onVerbFormClick,
            modifier = modifier
                .testTag(VERB_FORMS_TEST_TAG)
                .semantics { paneTitle = pageTitle },
        )
    } else {
        CommunicationGrid(
            grid = board.grid,
            onCellClick = actions.onCellClick,
            isEditing = state.isEditing,
            onEmptySlotClick = actions.onEmptySlotClick,
            onCellLongClick = actions.onCellLongClick,
            onCellMoved = actions.onCellMoved,
            // Nom de la grille, non affiché : porté en titre de volet pour TalkBack.
            modifier = modifier.semantics { paneTitle = board.name },
        )
    }
}

/** Réglages du son, sinon réglages généraux. Faux quand aucun écran n'a pu s'ouvrir. */
private fun openSoundSettings(context: Context): Boolean = openFirstSettings(context, listOf(Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_SETTINGS))

/**
 * Réglages de synthèse vocale, sinon installation de voix (action publique), sinon réglages
 * généraux. Pas de `resolveActivity` : il exigerait de déclarer chaque action dans `<queries>`.
 * Faux quand aucun écran n'a pu s'ouvrir.
 */
private fun openSpeechSettings(context: Context): Boolean =
    openFirstSettings(context, listOf(TTS_SETTINGS_ACTION, TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, Settings.ACTION_SETTINGS))

/** Ouvre le premier écran de [actions] qui existe. Faux quand aucun n'a pu s'ouvrir. */
private fun openFirstSettings(context: Context, actions: List<String>): Boolean {
    for (action in actions) {
        try {
            context.startActivity(Intent(action))
            return true
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $action", exception)
        } catch (exception: SecurityException) {
            // Activité de réglages non exportée chez certains constructeurs : on passe à la suivante.
            Log.w(TAG, "Activity for $action is not reachable", exception)
        }
    }
    Log.e(TAG, "No settings screen could be opened")
    return false
}

@Preview(showSystemUi = true)
@Composable
private fun BoardScreenPreview() {
    val cells = listOf(
        Cell(id = 1, row = 0, column = 0, label = "je", linkedBoardId = null),
        Cell(id = 2, row = 0, column = 1, label = "veux", linkedBoardId = null),
        Cell(id = 3, row = 1, column = 2, label = "Manger", linkedBoardId = 2),
    )
    PapotonsTheme {
        BoardScreen(
            state = BoardUiState(
                board = DisplayedBoard(id = 1, name = "Accueil", grid = layoutGrid(2, 3, cells), canGoBack = false),
                message = persistentListOf(SentenceToken("je", persistentListOf()), SentenceToken("veux", persistentListOf())),
                speechStatus = SpeechStatus.LanguageMissing,
            ),
            actions = BoardActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}),
        )
    }
}
