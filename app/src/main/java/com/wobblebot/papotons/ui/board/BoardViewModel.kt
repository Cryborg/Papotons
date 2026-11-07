package com.wobblebot.papotons.ui.board

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceWord
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.domain.sentenceTokens
import com.wobblebot.papotons.speech.MediaVolume
import com.wobblebot.papotons.speech.Speaker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BoardViewModel"
internal const val NAVIGATION_KEY = "navigation"
internal const val MESSAGE_KEY = "message"
internal const val STOP_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BoardViewModel @Inject constructor(
    private val repository: BoardRepository,
    private val speaker: Speaker,
    private val mediaVolume: MediaVolume,
    /**
     * Miroir de la pile et de la phrase, pour survivre à la mort du processus : lu une seule fois
     * ici, à la création, puis seulement recopié (un collecteur par flux).
     */
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /** Pile des grilles ouvertes, l'accueil en bas. Vide tant que l'accueil n'est pas prêt. */
    private val navigation = MutableStateFlow<PersistentList<Long>>(persistentListOf())
    private val message = MutableStateFlow(restoredMessage())

    /**
     * Ouverte seulement par un appui sans effet sur « Son coupé ». Le son revenu la referme pour de
     * bon : un volume recoupé plus tard ne doit pas la rouvrir sans qu'on ait rien touché.
     */
    private val isVolumeLockedDialogOpen = MutableStateFlow(false)

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
        combine(
            displayedBoard,
            message,
            speaker.status,
            // Faux d'ici la première lecture : l'écran n'attend pas l'alerte pour s'afficher.
            repository.observeLostBoards().onStart { emit(false) },
            combine(mediaVolume.isMuted, isVolumeLockedDialogOpen) { isMuted, isDialogOpen -> isMuted to (isMuted && isDialogOpen) },
        ) { board, words, speechStatus, hasLostBoards, (isMuted, isVolumeLocked) ->
            BoardUiState(
                board = board,
                message = sentenceTokens(words).toImmutableList(),
                speechStatus = speechStatus,
                hasLostBoards = hasLostBoards,
                isMuted = isMuted,
                isVolumeLockedDialogOpen = isVolumeLocked,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BoardUiState())

    /** L'ouverture de l'accueil en cours, pour ne pas la lancer deux fois. */
    private var homeOpening: Job? = null

    init {
        // On n'observe rien avant la fin de l'insertion : sinon l'accueil serait lu absent
        // au premier lancement. Une erreur ici remonte en plantage (Android vitals).
        reopenHome(savedStack = savedState.get<LongArray>(NAVIGATION_KEY)?.toList())
        viewModelScope.launch {
            // La pile vide de l'attente de l'accueil n'écrase pas la pile sauvée.
            navigation.collect { stack -> if (stack.isNotEmpty()) savedState[NAVIGATION_KEY] = stack.toLongArray() }
        }
        viewModelScope.launch {
            message.collect { words -> savedState[MESSAGE_KEY] = ArrayList(words.map(SavedWord::from)) }
        }
        viewModelScope.launch {
            mediaVolume.isMuted.collect { isMuted -> if (!isMuted) isVolumeLockedDialogOpen.value = false }
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
            // Au moment de parler : certaines coupures (« Ne pas déranger ») ne se signalent pas.
            mediaVolume.refresh()
            message.update { it.adding(SentenceWord(cell.label, cell.category, cell.pictogramId)) }
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
        if (words.isEmpty()) return
        speaker.speak(sentenceTokens(words).joinToString(separator = " ") { it.text }, interrupt = true)
        mediaVolume.refresh()
    }

    fun onDeleteLastWord() {
        message.update { words -> if (words.isEmpty()) words else words.removingAt(words.lastIndex) }
    }

    fun onClearMessage() {
        // Des mots touchés en rafale peuvent encore attendre leur tour : effacer les fait taire.
        speaker.stop()
        message.value = persistentListOf()
    }

    fun onAcknowledgeLostBoards() {
        viewModelScope.launch { repository.acknowledgeLostBoards() }
    }

    /**
     * À chaque `ON_START` (retour au premier plan, mais aussi rotation) et sur « Réessayer » :
     * une voix a pu être installée ou le moteur réactivé entre-temps, parfois sans que l'appli
     * ne quitte le premier plan (écran partagé). Le volume a pu changer aussi : il est relu.
     */
    fun recoverSpeech() {
        speaker.recoverIfNeeded()
        mediaVolume.refresh()
    }

    /** Monte le son à un niveau audible ; s'il reste coupé, la modale pour l'adulte s'ouvre. */
    fun onMutedAlertClick() {
        if (!mediaVolume.raise()) isVolumeLockedDialogOpen.value = true
    }

    fun onVolumeLockedDialogClose() {
        isVolumeLockedDialogOpen.value = false
    }

    override fun onCleared() {
        speaker.shutdown()
        mediaVolume.release()
    }

    private fun isShowingTopOf(stack: PersistentList<Long>): Boolean = uiState.value.board?.id == stack.lastOrNull()

    /** [savedStack] : la pile d'avant la mort du processus, à la création seulement. */
    private fun reopenHome(savedStack: List<Long>? = null) {
        if (homeOpening?.isActive == true) return
        homeOpening = viewModelScope.launch {
            val homeBoardId = repository.ensureHomeBoard()
            navigation.value = when {
                savedStack.isNullOrEmpty() -> persistentListOf(homeBoardId)
                savedStack.first() == homeBoardId -> savedStack.toPersistentList()
                else -> {
                    Log.w(TAG, "Saved stack $savedStack does not start at home board $homeBoardId, dropped")
                    persistentListOf(homeBoardId)
                }
            }
        }
    }

    private fun restoredMessage(): PersistentList<SentenceWord> =
        savedState.get<ArrayList<SavedWord>>(MESSAGE_KEY).orEmpty().map { it.toSentenceWord() }.toPersistentList()

    private fun display(board: Board?, stack: PersistentList<Long>): DisplayedBoard? {
        if (board == null) {
            Log.e(TAG, "Board ${stack.last()} not found (stack $stack)")
            // Seulement si la pile n'a pas bougé entre-temps : sinon on dépilerait la grille d'un autre appui.
            navigation.update { current -> if (current == stack && current.size > 1) current.removingAt(current.lastIndex) else current }
            // L'accueil lui-même est introuvable (base vidée en cours d'usage) : sans relance, rien ne
            // s'afficherait plus jusqu'à la fin du processus. `ensureHomeBoard` remet le vocabulaire de
            // base et lève l'alerte si c'est une perte.
            if (stack.size == 1) reopenHome()
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
