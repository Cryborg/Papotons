package com.wobblebot.papotons.ui.board

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceToken
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.about.AboutDialog
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf

private const val TAG = "BoardScreen"

/** Réglages de synthèse vocale d'Android : action non publique, d'où les replis. */
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

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
)

/** Branche [BoardScreen] sur son ViewModel : retour arrière, premier plan, modale de la voix. */
@Composable
fun BoardRoute(viewModel: BoardViewModel, state: BoardUiState) {
    val context = LocalContext.current
    var isSpeechDialogOpen by rememberSaveable { mutableStateOf(false) }
    var areSettingsUnreachable by rememberSaveable { mutableStateOf(false) }
    var isAboutOpen by rememberSaveable { mutableStateOf(false) }
    var isLostBoardsDialogOpen by rememberSaveable { mutableStateOf(false) }
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
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.recoverSpeech() }
    // À l'accueil, le retour arrière garde son sens habituel (quitter l'appli).
    BackHandler(enabled = state.board?.canGoBack == true, onBack = viewModel::onBack)
    BoardScreen(state = state, actions = actions)
    if (isAboutOpen) AboutDialog(onClose = { isAboutOpen = false })
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
            onAcknowledge = {
                isLostBoardsDialogOpen = false
                viewModel.onAcknowledgeLostBoards()
            },
            onClose = { isLostBoardsDialogOpen = false },
        )
    }
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
fun BoardScreen(state: BoardUiState, actions: BoardActions, modifier: Modifier = Modifier) {
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
                MessageBar(
                    words = state.message,
                    onSpeak = actions.onSpeakMessage,
                    onDeleteLastWord = actions.onDeleteLastWord,
                    onClear = actions.onClearMessage,
                    modifier = Modifier.weight(1f),
                )
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
            actions = BoardActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}
