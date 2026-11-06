package com.wobblebot.papotons.ui.board

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceWord
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.domain.sentenceTokens
import com.wobblebot.papotons.speech.Speaker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BoardViewModel"
internal const val STOP_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BoardViewModel @Inject constructor(
    private val repository: BoardRepository,
    private val speaker: Speaker,
) : ViewModel() {
    /** Pile des grilles ouvertes, l'accueil en bas. Vide tant que l'accueil n'est pas prêt. */
    private val navigation = MutableStateFlow<PersistentList<Long>>(persistentListOf())
    private val message = MutableStateFlow<PersistentList<SentenceWord>>(persistentListOf())

    /**
     * La grille affichée reste la précédente tant que la suivante n'est pas lue : une grille
     * introuvable n'émet rien (pas d'écran vide en passant), elle fait seulement revenir d'un cran.
     * `null` seulement avant que l'accueil ne soit connu : à la reprise de la collecte (écran
     * rallumé), `stateIn` garde la grille affichée jusqu'à la relecture, sans écran de chargement.
     */
    private val displayedBoard: Flow<DisplayedBoard?> = navigation.flatMapLatest<PersistentList<Long>, DisplayedBoard?> { stack ->
        if (stack.isEmpty()) {
            flowOf(null)
        } else {
            repository.observeBoard(stack.last()).mapNotNull { board -> display(board, stack) }
        }
    }

    val uiState: StateFlow<BoardUiState> =
        combine(displayedBoard, message, speaker.status) { board, words, speechStatus ->
            BoardUiState(board = board, message = sentenceTokens(words).toImmutableList(), speechStatus = speechStatus)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BoardUiState())

    init {
        // On n'observe rien avant la fin de l'insertion : sinon l'accueil serait lu absent
        // au premier lancement. Une erreur ici remonte en plantage (Android vitals).
        viewModelScope.launch {
            val homeBoardId = repository.ensureHomeBoard()
            navigation.value = persistentListOf(homeBoardId)
        }
    }

    fun onCellClick(cell: Cell) {
        val linkedBoardId = cell.linkedBoardId
        if (linkedBoardId != null) {
            // Un second appui pendant que la grille suivante se charge viserait encore l'ancienne
            // grille affichée : il n'empile rien.
            navigation.update { stack -> if (isShowingTopOf(stack)) stack.adding(linkedBoardId) else stack }
        } else {
            speaker.speak(cell.label, interrupt = false)
            message.update { it.adding(SentenceWord(cell.label, cell.category)) }
        }
    }

    fun onBack() {
        navigation.update { stack -> if (stack.size > 1 && isShowingTopOf(stack)) stack.removingAt(stack.lastIndex) else stack }
    }

    fun onHome() {
        navigation.update { stack -> if (stack.size > 1) persistentListOf(stack.first()) else stack }
    }

    fun onSpeakMessage() {
        val words = message.value
        if (words.isNotEmpty()) speaker.speak(sentenceTokens(words).joinToString(separator = " "), interrupt = true)
    }

    fun onDeleteLastWord() {
        message.update { words -> if (words.isEmpty()) words else words.removingAt(words.lastIndex) }
    }

    fun onClearMessage() {
        // Des mots touchés en rafale peuvent encore attendre leur tour : effacer les fait taire.
        speaker.stop()
        message.value = persistentListOf()
    }

    /**
     * À chaque `ON_START` (retour au premier plan, mais aussi rotation) et sur « Réessayer » :
     * une voix a pu être installée ou le moteur réactivé entre-temps, parfois sans que l'appli
     * ne quitte le premier plan (écran partagé).
     */
    fun recoverSpeech() {
        speaker.recoverIfNeeded()
    }

    override fun onCleared() {
        speaker.shutdown()
    }

    private fun isShowingTopOf(stack: PersistentList<Long>): Boolean = uiState.value.board?.id == stack.lastOrNull()

    private fun display(board: Board?, stack: PersistentList<Long>): DisplayedBoard? {
        if (board == null) {
            Log.e(TAG, "Board ${stack.last()} not found (stack $stack)")
            // Seulement si la pile n'a pas bougé entre-temps : sinon on dépilerait la grille d'un autre appui.
            navigation.update { current -> if (current == stack && current.size > 1) current.removingAt(current.lastIndex) else current }
            return null
        }
        return DisplayedBoard(
            id = board.id,
            name = board.name,
            grid = layoutGrid(board.rowCount, board.columnCount, board.cells),
            canGoBack = stack.size > 1,
        )
    }
}
