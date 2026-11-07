package com.wobblebot.papotons.ui.board

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.pictogram.ArasaacClient
import com.wobblebot.papotons.data.pictogram.PictogramStore
import com.wobblebot.papotons.data.pictogram.TooManyResultsException
import com.wobblebot.papotons.data.pictogram.isSearchable
import com.wobblebot.papotons.data.board.CellNotFoundException
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.CellContent
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import javax.inject.Inject

private const val TAG = "BoardViewModel"
private const val PARALLEL_THUMBNAILS = 4

/** Ce que l'écran lit en plus de la grille et de la phrase, regroupé pour tenir dans un `combine`. */
private data class SecondaryState(
    val isMuted: Boolean,
    val isVolumeLockedDialogOpen: Boolean,
    val isEditing: Boolean,
    val editor: CellEditorState?,
    val pictogramSearch: PictogramSearchState?,
)
internal const val NAVIGATION_KEY = "navigation"
internal const val MESSAGE_KEY = "message"
internal const val STOP_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BoardViewModel @Inject constructor(
    private val repository: BoardRepository,
    private val speaker: Speaker,
    private val mediaVolume: MediaVolume,
    private val arasaac: ArasaacClient,
    private val pictogramStore: PictogramStore,
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
     * Le mode édition et son éditeur, derrière le contrôle parental : jamais dans [savedState], pour
     * qu'aucun ne se rouvre sans contrôle après la mort du processus.
     */
    private val isEditing = MutableStateFlow(false)
    private val editor = MutableStateFlow<CellEditorState?>(null)
    private var writeCount = 0L

    /** La recherche ouverte, ses images téléchargées, et le picto choisi : jamais sauvés, comme l'édition. */
    private val pictogramSearch = MutableStateFlow<PictogramSearchState?>(null)
    private var searchJob: Job? = null
    private val foundBytes = HashMap<Long, ByteArray>()
    private var chosenBytes: Pair<Long, ByteArray>? = null

    /** Change à chaque recherche : une vignette d'une recherche remplacée ne touche plus rien. */
    private var searchCount = 0L

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
            combine(mediaVolume.isMuted, isVolumeLockedDialogOpen, isEditing, editor, pictogramSearch) { isMuted, isDialogOpen, isEditing, editor, search ->
                SecondaryState(
                    isMuted = isMuted,
                    isVolumeLockedDialogOpen = isMuted && isDialogOpen,
                    isEditing = isEditing,
                    editor = editor,
                    pictogramSearch = search,
                )
            },
        ) { board, words, speechStatus, hasLostBoards, secondary ->
            BoardUiState(
                board = board,
                message = sentenceTokens(words).toImmutableList(),
                speechStatus = speechStatus,
                hasLostBoards = hasLostBoards,
                isMuted = secondary.isMuted,
                isVolumeLockedDialogOpen = secondary.isVolumeLockedDialogOpen,
                isEditing = secondary.isEditing,
                editor = secondary.editor,
                pictogramSearch = secondary.pictogramSearch,
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
        // En édition, rien ne parle et aucun dossier ne s'ouvre : la case s'ouvre dans l'éditeur.
        if (isEditing.value) {
            val boardId = uiState.value.board?.id ?: return
            editor.value = CellEditorState(EditTarget.ExistingCell(boardId, cell))
            return
        }
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

    /** Après le contrôle parental. Les mots encore en file se taisent. */
    fun onStartEditing() {
        speaker.stop()
        isEditing.value = true
    }

    /** « Terminer », et tout départ de l'appli (`ON_STOP`) : la tablette rendue à l'enfant n'est jamais en édition. */
    fun onStopEditing() {
        isEditing.value = false
        editor.value = null
        onSearchClose()
        chosenBytes = null
    }

    fun onEmptySlotClick(row: Int, column: Int) {
        if (!isEditing.value) return
        val boardId = uiState.value.board?.id ?: return
        editor.value = CellEditorState(EditTarget.NewCell(boardId, row, column))
    }

    fun onEditorClose() {
        editor.update { current -> if (current?.isWriting == true) current else null }
        if (editor.value == null) chosenBytes = null
    }

    /**
     * Ouvre la recherche, le champ prérempli avec [query] (le libellé de la case). Rien ne part
     * avant « Chercher » : le libellé peut être un prénom, l'adulte lit d'abord l'avertissement.
     */
    fun onSearchOpen(query: String) {
        if (editor.value == null) return
        pictogramSearch.value = PictogramSearchState(query)
    }

    /** « Retirer » dans l'éditeur : le choix fait dans la recherche est oublié (une rotation ne le remet pas). */
    fun onChosenPictogramRemoved() {
        // Pendant une écriture, l'éditeur ne change pas : son résultat doit pouvoir le refermer.
        if (editor.value?.isWriting == true) return
        chosenBytes = null
        editor.update { it?.copy(chosenPictogram = null) }
    }

    /**
     * Une recherche remplace la précédente (annulée, connexions fermées). Vignettes 4 à la fois au
     * plus ; une vignette qui échoue reste grise, les autres s'affichent.
     */
    fun onSearch(query: String) {
        searchJob?.cancel()
        foundBytes.clear()
        val searchNumber = ++searchCount
        if (!isSearchable(query)) {
            pictogramSearch.value = PictogramSearchState(query)
            return
        }
        pictogramSearch.value = PictogramSearchState(query, SearchStatus.Loading)
        searchJob = viewModelScope.launch {
            val results = try {
                arasaac.search(query)
            } catch (exception: TooManyResultsException) {
                if (searchNumber == searchCount) pictogramSearch.update { it?.copy(status = SearchStatus.TooManyResults) }
                return@launch
            } catch (exception: IOException) {
                // Une recherche déjà remplacée (son délai expire après coup) ne touche plus rien.
                if (searchNumber != searchCount) return@launch
                // Ni connexion ni réponse lisible : l'adulte a « Réessayer ».
                Log.w(TAG, "Pictogram search failed", exception)
                pictogramSearch.update { it?.copy(status = SearchStatus.Failed) }
                return@launch
            }
            if (searchNumber != searchCount) return@launch
            pictogramSearch.update { current ->
                current?.copy(status = SearchStatus.Done, results = results.map { FoundPictogram(it.id, it.keyword) }.toImmutableList())
            }
            val permits = Semaphore(PARALLEL_THUMBNAILS)
            coroutineScope {
                for (result in results) {
                    launch { permits.withPermit { loadThumbnail(result.id, searchNumber) } }
                }
            }
        }
    }

    private suspend fun loadThumbnail(pictogramId: Long, searchNumber: Long) {
        val bytes = try {
            arasaac.download(pictogramId)
        } catch (exception: IOException) {
            Log.w(TAG, "Thumbnail $pictogramId failed", exception)
            return
        }
        val image = pictogramStore.decode(bytes) ?: return
        if (searchNumber != searchCount) return
        foundBytes[pictogramId] = bytes
        pictogramSearch.update { current ->
            current?.copy(results = current.results.map { if (it.id == pictogramId) it.copy(image = image) else it }.toImmutableList())
        }
    }

    /** Le picto choisi va dans l'éditeur ; il n'est gardé sur l'appareil qu'à l'« Enregistrer ». */
    fun onSearchPick(pictogramId: Long) {
        val bytes = foundBytes[pictogramId] ?: return
        val image = pictogramSearch.value?.results?.firstOrNull { it.id == pictogramId }?.image ?: return
        chosenBytes = pictogramId to bytes
        editor.update { it?.copy(chosenPictogram = ChosenPictogram(pictogramId, image)) }
        onSearchClose()
    }

    fun onSearchClose() {
        searchCount++
        searchJob?.cancel()
        searchJob = null
        foundBytes.clear()
        pictogramSearch.value = null
    }

    /** [asFolder] ne compte que pour une nouvelle case : une case existante garde son type. */
    fun onEditorSave(content: CellContent, asFolder: Boolean) {
        writeFromEditor { target ->
            // Le picto choisi d'abord : la case ne vise jamais un picto absent de l'appareil (invariant 2).
            val chosen = chosenBytes
            if (chosen != null && content.pictogramId == chosen.first && !pictogramStore.store(chosen.first, chosen.second)) {
                throw IOException("Pictogram ${chosen.first} could not be stored")
            }
            when (target) {
                is EditTarget.NewCell ->
                    if (asFolder) {
                        repository.addFolder(target.boardId, target.row, target.column, content)
                    } else {
                        repository.addWord(target.boardId, target.row, target.column, content)
                    }
                is EditTarget.ExistingCell -> repository.updateCell(target.cell, content)
            }
        }
    }

    fun onEditorDelete() {
        writeFromEditor { target ->
            if (target is EditTarget.ExistingCell) {
                repository.deleteCell(target.cell, target.boardId, openRootBoardId = navigation.value.first())
            }
        }
    }

    /** Pour modifier le contenu d'un dossier : on y entre, toujours en édition. */
    fun onEditorOpenFolder() {
        val target = editor.value?.target as? EditTarget.ExistingCell ?: return
        val linkedBoardId = target.cell.linkedBoardId ?: return
        editor.value = null
        chosenBytes = null
        navigation.update { stack -> if (isShowingTopOf(stack)) stack.adding(linkedBoardId) else stack }
    }

    private fun writeFromEditor(write: suspend (EditTarget) -> Unit) {
        val current = editor.value ?: return
        if (current.isWriting) return
        val writing = current.copy(isWriting = true, failure = null, writeToken = ++writeCount)
        editor.value = writing
        viewModelScope.launch {
            // Le résultat ne touche que l'éditeur qui l'a lancé : fermé (Terminer, départ) ou rouvert
            // entre-temps, un autre éditeur ne se referme pas et n'affiche pas l'échec d'un autre.
            try {
                write(current.target)
                if (editor.compareAndSet(writing, null)) chosenBytes = null
            } catch (exception: SQLiteConstraintException) {
                // Emplacement pris entre-temps (un « + » pas encore rafraîchi) : rien n'est écrit.
                reportEditFailure(current, writing, EditFailure.SlotTakenOrCellGone, exception)
            } catch (exception: CellNotFoundException) {
                reportEditFailure(current, writing, EditFailure.SlotTakenOrCellGone, exception)
            } catch (exception: SQLiteException) {
                // Disque plein : rien n'est écrit à moitié (transactions).
                reportEditFailure(current, writing, EditFailure.Storage, exception)
            } catch (exception: IOException) {
                // Marqueur des copies ou picto choisi impossible à écrire : rien n'a été écrit en base.
                reportEditFailure(current, writing, EditFailure.Storage, exception)
            }
        }
    }

    private fun reportEditFailure(current: CellEditorState, writing: CellEditorState, failure: EditFailure, exception: Exception) {
        // Ni libellé ni contenu dans le journal : ce sont les mots de l'utilisateur.
        Log.e(TAG, "Edit on board ${current.target.boardId} failed ($failure)", exception)
        editor.compareAndSet(writing, current.copy(isWriting = false, failure = failure))
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
