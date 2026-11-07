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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceToken
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.about.AboutDialog
import com.wobblebot.papotons.ui.backup.BackupDialog
import com.wobblebot.papotons.ui.backup.BackupUiState
import com.wobblebot.papotons.ui.backup.BackupViewModel
import com.wobblebot.papotons.ui.backup.RestoreConfirmDialog
import com.wobblebot.papotons.ui.edit.CellEditorDialog
import com.wobblebot.papotons.ui.edit.ParentalGateDialog
import com.wobblebot.papotons.ui.edit.PictogramSearchDialog
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf

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
    val onAboutClick: () -> Unit,
    val onEmptySlotClick: (row: Int, column: Int) -> Unit,
    val onStopEditing: () -> Unit,
    val onBackupClick: () -> Unit,
)

/** Ce qu'ouvre le contrôle parental quand il est passé. */
private enum class GatedAction { StartEditing, AcknowledgeLostBoards, RestoreBackup }

/** Branche [BoardScreen] sur son ViewModel : retour arrière, premier plan, modale de la voix. */
@Composable
fun BoardRoute(viewModel: BoardViewModel, state: BoardUiState, backupViewModel: BackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val backupState by backupViewModel.uiState.collectAsStateWithLifecycle()
    val backupFileNamePattern = stringResource(R.string.backup_file_name)
    var isSpeechDialogOpen by rememberSaveable { mutableStateOf(false) }
    var areSettingsUnreachable by rememberSaveable { mutableStateOf(false) }
    var isAboutOpen by rememberSaveable { mutableStateOf(false) }
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
            onAboutClick = { isAboutOpen = true },
            onEmptySlotClick = viewModel::onEmptySlotClick,
            onStopEditing = viewModel::onStopEditing,
            onBackupClick = backupViewModel::onOpen,
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.recoverSpeech() }
    // Un vrai départ (écran éteint, autre appli) termine l'édition : la tablette rendue à l'enfant
    // n'y est jamais. Une rotation, qui passe aussi par ON_STOP, la garde.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // L'appli photo et le sélecteur font passer par ON_STOP sans que l'adulte soit parti.
        val isAwaitingOtherApp = viewModel.isAwaitingPhoto || backupViewModel.isAwaitingDocument
        if (activity?.isChangingConfigurations != true && !isAwaitingOtherApp) viewModel.onStopEditing()
    }
    // À l'accueil, le retour arrière garde son sens habituel (quitter l'appli) ; en édition, il la termine.
    BackHandler(enabled = state.board?.canGoBack == true, onBack = viewModel::onBack)
    BackHandler(enabled = state.isEditing && state.board?.canGoBack != true, onBack = viewModel::onStopEditing)
    // Les modales de sauvegarde n'existent qu'en édition, derrière le contrôle parental.
    LaunchedEffect(state.isEditing) {
        if (!state.isEditing) backupViewModel.onEditingEnded()
    }
    BoardScreen(state = state, actions = actions, hasUnsavedChanges = backupState.hasUnsavedChanges)
    if (isAboutOpen) {
        AboutDialog(
            onClose = { isAboutOpen = false },
            onEditBoards = {
                isAboutOpen = false
                gatedAction = GatedAction.StartEditing
            },
        )
    }
    gatedAction?.let { action ->
        ParentalGateDialog(
            onPassed = {
                gatedAction = null
                when (action) {
                    GatedAction.StartEditing -> viewModel.onStartEditing()
                    GatedAction.AcknowledgeLostBoards -> viewModel.onAcknowledgeLostBoards()
                    GatedAction.RestoreBackup -> {
                        viewModel.onStartEditing()
                        backupViewModel.onOpen()
                    }
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
fun BoardScreen(state: BoardUiState, actions: BoardActions, modifier: Modifier = Modifier, hasUnsavedChanges: Boolean = true) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Barres système et encoche seulement : le clavier (édition, MVP-3) ne doit pas rétrécir
        // la grille et déplacer les cases.
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            val board = state.board
            BoardTopBar(
                canGoBack = board?.canGoBack == true,
                speechStatus = state.speechStatus,
                hasLostBoards = state.hasLostBoards,
                isMuted = state.isMuted,
                onBack = actions.onBack,
                onHome = actions.onHome,
                onSpeechAlertClick = actions.onSpeechAlertClick,
                onMutedAlertClick = actions.onMutedAlertClick,
                onLostBoardsAlertClick = actions.onLostBoardsAlertClick,
                onAboutClick = actions.onAboutClick,
            ) {
                if (state.isEditing) {
                    EditBanner(
                        onDone = actions.onStopEditing,
                        onBackup = actions.onBackupClick,
                        hasUnsavedChanges = hasUnsavedChanges,
                        modifier = Modifier.weight(1f),
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
            if (board == null) {
                val loadingDescription = stringResource(R.string.board_loading)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingDescription })
                }
            } else {
                CommunicationGrid(
                    grid = board.grid,
                    onCellClick = actions.onCellClick,
                    isEditing = state.isEditing,
                    onEmptySlotClick = actions.onEmptySlotClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Nom de la grille, non affiché : porté en titre de volet pour TalkBack.
                        .semantics { paneTitle = board.name },
                )
            }
        }
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
