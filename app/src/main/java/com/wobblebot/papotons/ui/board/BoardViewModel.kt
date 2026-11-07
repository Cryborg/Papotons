package com.wobblebot.papotons.ui.board

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.conjugation.Conjugations
import com.wobblebot.papotons.data.photo.PhotoStore
import com.wobblebot.papotons.data.pictogram.ArasaacClient
import com.wobblebot.papotons.data.pictogram.PictogramStore
import com.wobblebot.papotons.data.pictogram.TooManyResultsException
import com.wobblebot.papotons.data.pictogram.isSearchable
import com.wobblebot.papotons.data.board.CellChangedException
import com.wobblebot.papotons.data.board.CellNotFoundException
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.ConjugationTable
import com.wobblebot.papotons.domain.SentenceWord
import com.wobblebot.papotons.domain.WordCategory
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
import kotlinx.coroutines.flow.drop
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

/** Au-delà, une photo revenue de l'appli photo est ignorée et l'édition se termine. */
internal const val PHOTO_REQUEST_TIMEOUT_MS = 2 * 60 * 1000L

/** Ce que l'écran lit en plus de la grille et de la phrase, regroupé pour tenir dans un `combine`. */
private data class SecondaryState(
    val isMuted: Boolean,
    val isVolumeLockedDialogOpen: Boolean,
    val isEditing: Boolean,
    val isSettingsOpen: Boolean,
    val settingFailure: SettingFailure?,
    val editor: CellEditorState?,
    val pictogramSearch: PictogramSearchState?,
)

/** Les deux zones réservées aux adultes. */
private data class AdultState(val isEditing: Boolean, val isSettingsOpen: Boolean, val settingFailure: SettingFailure?)
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
    private val photos: PhotoStore,
    private val conjugations: Conjugations,
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
     * Le mode édition, son éditeur et les Réglages, derrière le contrôle parental : jamais dans
     * [savedState], pour qu'aucun ne se rouvre sans contrôle après la mort du processus.
     */
    private val isEditing = MutableStateFlow(false)
    private val isSettingsOpen = MutableStateFlow(false)
    private val settingFailure = MutableStateFlow<SettingFailure?>(null)

    /** Le choix de taille en cours : un nouveau choix l'annule, le dernier appui gagne. */
    private var sizeChoice: Job? = null
    private val editor = MutableStateFlow<CellEditorState?>(null)
    private var writeCount = 0L

    /** La recherche ouverte, ses images téléchargées, et le picto choisi : jamais sauvés, comme l'édition. */
    private val pictogramSearch = MutableStateFlow<PictogramSearchState?>(null)
    private var searchJob: Job? = null
    private val foundBytes = HashMap<Long, ByteArray>()
    private var chosenBytes: Pair<Long, ByteArray>? = null

    /** Change à chaque recherche : une vignette d'une recherche remplacée ne touche plus rien. */
    private var searchCount = 0L

    /** Une photo préparée dans l'éditeur et écrite dans aucune case : à effacer si on y renonce. */
    private var unsavedPhoto: String? = null

    /** Change à chaque photo demandée ou abandonnée : un import arrivé trop tard est jeté. */
    private var photoCount = 0L

    /** Depuis quand l'appli photo ou le sélecteur sont ouverts ; `null` sinon. */
    private var photoRequestedAtMs: Long? = null
    private var cameraCapture: Uri? = null

    /**
     * La page des formes ouverte par un appui long, à la place de la grille. Jamais dans [savedState] :
     * c'est un passage, la grille revient après la mort du processus.
     */
    private val verbForms = MutableStateFlow<VerbFormsPage?>(null)

    /** Vide jusqu'à la fin de sa lecture : un appui long d'ici là parle comme un appui simple. */
    private var conjugationTable = ConjugationTable.EMPTY

    /** L'horloge du délai de prise de vue ; remplaçable en test. */
    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    /** Un choix de photo est en cours : l'appli passe par `ON_STOP` sans que l'adulte soit parti. */
    val isAwaitingPhoto: Boolean get() = photoRequestedAtMs != null

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
            combine(
                mediaVolume.isMuted,
                isVolumeLockedDialogOpen,
                combine(isEditing, isSettingsOpen, settingFailure, ::AdultState),
                editor,
                pictogramSearch,
            ) { isMuted, isDialogOpen, adult, editor, search ->
                SecondaryState(
                    isMuted = isMuted,
                    isVolumeLockedDialogOpen = isMuted && isDialogOpen,
                    isEditing = adult.isEditing,
                    isSettingsOpen = adult.isSettingsOpen,
                    settingFailure = adult.settingFailure,
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
                isSettingsOpen = secondary.isSettingsOpen,
                settingFailure = secondary.settingFailure,
                editor = secondary.editor,
                pictogramSearch = secondary.pictogramSearch,
            )
        }.combine(verbForms) { state, page -> state.copy(verbForms = page) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BoardUiState())

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
            // Un compteur, pas un événement : une restauration faite quand l'écran n'écoutait pas compte aussi.
            repository.replacements.drop(1).collect { onBoardsReplaced() }
        }
        viewModelScope.launch { conjugationTable = conjugations.table() }
        viewModelScope.launch {
            // La page vaut pour la grille d'où elle a été ouverte : toute pile changée la referme
            // (grille affichée disparue, grilles remplacées).
            navigation.drop(1).collect { verbForms.value = null }
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
            say(SentenceWord(cell.spoken, cell.category, cell.picture))
        }
    }

    /**
     * Appui long sur un verbe (jamais un dossier, jamais en édition) : la page de ses formes. Un verbe
     * hors de la table parle comme un appui simple. Seul un appui parti pendant le chargement d'une
     * autre grille reste sans effet, comme pour un dossier.
     */
    fun onCellLongClick(cell: Cell) {
        // Comme un dossier : un appui parti pendant qu'une autre grille se charge ne vise plus rien.
        if (isEditing.value || cell.linkedBoardId != null || !isShowingTopOf(navigation.value)) return
        val forms = if (cell.category == WordCategory.VERB) conjugationTable.formsOf(cell.spoken) else null
        if (forms == null) {
            // Ni libellé ni contenu dans le journal : ce sont les mots de l'utilisateur.
            Log.i(TAG, "No forms for cell ${cell.id} (${conjugationTable.verbCount} verbs loaded)")
            onCellClick(cell)
        } else {
            verbForms.value = VerbFormsPage(cell, forms)
        }
    }

    /** Une forme touchée entre dans la phrase comme un mot de la grille, avec l'image de sa case ; la grille revient. */
    fun onVerbFormClick(form: String) {
        val page = verbForms.value ?: return
        verbForms.value = null
        // L'infinitif du coin n'est pas une forme conjuguée : « je » ne s'élide pas devant lui.
        val isConjugated = form != page.forms.infinitive
        say(SentenceWord(form, page.cell.category, page.cell.picture, isConjugated = isConjugated))
    }

    private fun say(word: SentenceWord) {
        speaker.speak(word.label, interrupt = false)
        // Au moment de parler : certaines coupures (« Ne pas déranger ») ne se signalent pas.
        mediaVolume.refresh()
        message.update { it.adding(word) }
    }

    fun onBack() {
        // La page des formes se referme d'abord, comme un dossier : la grille d'en dessous ne bouge pas.
        if (verbForms.value != null) {
            verbForms.value = null
            return
        }
        navigation.update { stack -> if (stack.size > 1 && isShowingTopOf(stack)) stack.removingAt(stack.lastIndex) else stack }
    }

    fun onHome() {
        verbForms.value = null
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

    /** Après le contrôle parental. Les mots encore en file se taisent ; les Réglages se ferment sur la grille à modifier. */
    fun onStartEditing() {
        speaker.stop()
        isSettingsOpen.value = false
        verbForms.value = null
        isEditing.value = true
    }

    /**
     * La roue dentée : en édition, l'adulte vient de passer le contrôle, les Réglages s'ouvrent tout
     * de suite (vrai) ; sinon rien ne s'ouvre, le contrôle doit passer d'abord (faux).
     */
    fun onSettingsRequested(): Boolean {
        if (!isEditing.value) return false
        onOpenSettings()
        return true
    }

    /** Après le contrôle parental (ou depuis l'édition, par [onSettingsRequested]). */
    fun onOpenSettings() {
        settingFailure.value = null
        isSettingsOpen.value = true
    }

    /** « Fermer », retour arrière, et tout départ de l'appli (`ON_STOP`). */
    fun onCloseSettings() {
        isSettingsOpen.value = false
    }

    /**
     * Enregistre l'accueil de [size] comme écran de départ, puis l'ouvre (la phrase reste). Après
     * l'ouverture en cours : sinon elle pourrait finir après et rouvrir l'ancien accueil. La pile
     * passe d'un coup au nouvel accueil : l'ancien reste affiché jusqu'à sa lecture.
     */
    fun onBoardSizeChosen(size: BoardSize) {
        sizeChoice?.cancel()
        settingFailure.value = null
        sizeChoice = viewModelScope.launch {
            homeOpening?.join()
            val isAvailable = try {
                repository.setStartBoard(size)
            } catch (exception: IOException) {
                Log.e(TAG, "Board size $size could not be saved", exception)
                settingFailure.value = SettingFailure.NotSaved
                return@launch
            } catch (exception: SQLiteException) {
                Log.e(TAG, "Board size $size could not be checked", exception)
                settingFailure.value = SettingFailure.NotSaved
                return@launch
            }
            if (isAvailable) navigation.value = persistentListOf(size.homeBoardId) else settingFailure.value = SettingFailure.Unavailable
        }
    }

    /** « Terminer », et tout départ de l'appli (`ON_STOP`) : la tablette rendue à l'enfant n'est jamais en édition. */
    fun onStopEditing() {
        isEditing.value = false
        editor.value = null
        onSearchClose()
        chosenBytes = null
        discardUnsavedPhoto()
    }

    /** Toutes les grilles ont été remplacées : la pile et l'éditeur visent peut-être des grilles qui n'existent plus. */
    private fun onBoardsReplaced() {
        editor.value = null
        // Pas seulement le collecteur de pile : une pile qui revient à la même valeur ([1], [], [1]) peut lui échapper.
        verbForms.value = null
        onSearchClose()
        chosenBytes = null
        discardUnsavedPhoto()
        navigation.value = persistentListOf()
        reopenHome()
    }

    fun onEmptySlotClick(row: Int, column: Int) {
        if (!isEditing.value) return
        val boardId = uiState.value.board?.id ?: return
        editor.value = CellEditorState(EditTarget.NewCell(boardId, row, column))
    }

    fun onEditorClose() {
        editor.update { current -> if (current?.isWriting == true) current else null }
        if (editor.value == null) {
            chosenBytes = null
            discardUnsavedPhoto()
        }
    }

    /** Juste avant d'ouvrir l'appli photo : le fichier où elle écrit, vidé d'avance. */
    fun onCameraRequested(): Uri {
        photoRequestedAtMs = elapsedRealtimeMs()
        return photos.freshCameraCapture().also { cameraCapture = it }
    }

    fun onGalleryRequested() {
        photoRequestedAtMs = elapsedRealtimeMs()
    }

    /** Aucune appli photo ni sélecteur : un message, jamais un plantage. */
    fun onPhotoRequestFailed() {
        photoRequestedAtMs = null
        editor.update { it?.copy(photoFailure = PhotoFailure.NoPhotoApp) }
    }

    /** L'appareil photo a rendu la main ; [isSaved] faux si l'adulte a renoncé. */
    fun onCameraResult(isSaved: Boolean) {
        onPhotoResult(if (isSaved) cameraCapture else null)
    }

    /**
     * Une photo revenue de l'appareil photo ou du sélecteur. Revenue plus de [PHOTO_REQUEST_TIMEOUT_MS]
     * après la demande, l'adulte est sans doute parti et l'enfant a repris la tablette dans la tâche
     * de l'appli photo : l'édition se termine, la photo est ignorée.
     */
    fun onPhotoResult(source: Uri?) {
        val requestedAt = photoRequestedAtMs ?: return
        photoRequestedAtMs = null
        if (elapsedRealtimeMs() - requestedAt > PHOTO_REQUEST_TIMEOUT_MS) {
            Log.i(TAG, "Photo came back too late, edit mode ended")
            photos.discardCameraCapture()
            onStopEditing()
            return
        }
        // Pendant une écriture, l'éditeur ne change pas (la photo en cours de validation lui appartient).
        if (source == null || editor.value == null || editor.value?.isWriting == true) {
            photos.discardCameraCapture()
            return
        }
        val photoNumber = ++photoCount
        editor.update { it?.copy(isImportingPhoto = true, photoFailure = null) }
        viewModelScope.launch {
            val name = photos.prepare(source)
            photos.discardCameraCapture()
            if (photoNumber != photoCount || editor.value == null) {
                if (name != null) photos.delete(name)
                return@launch
            }
            if (name == null) {
                editor.update { it?.copy(isImportingPhoto = false, photoFailure = PhotoFailure.UnusableImage) }
                return@launch
            }
            discardUnsavedPhoto()
            unsavedPhoto = name
            // Une photo remplace le picto : la règle de priorité ne sert qu'aux données incohérentes.
            chosenBytes = null
            editor.update { it?.copy(isImportingPhoto = false, chosenPhoto = name, chosenPictogram = null) }
        }
    }

    private fun discardUnsavedPhoto() {
        photoCount++
        val name = unsavedPhoto ?: return
        unsavedPhoto = null
        viewModelScope.launch { photos.delete(name) }
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
        // Un import en cours est abandonné avec : l'éditeur ne reste pas bloqué à l'attendre.
        discardUnsavedPhoto()
        editor.update { it?.copy(chosenPictogram = null, chosenPhoto = null, isImportingPhoto = false, photoFailure = null) }
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
        // Un picto remplace la photo choisie.
        discardUnsavedPhoto()
        editor.update { it?.copy(chosenPictogram = ChosenPictogram(pictogramId, image), chosenPhoto = null, photoFailure = null) }
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
        if (editor.value?.isImportingPhoto == true) return
        writeFromEditor { target, writeToken ->
            // La photo validée d'abord ; validation idempotente : un nouvel essai après un échec en
            // base réutilise le même fichier.
            val photo = content.photoFile
            // Seules deux photos peuvent être écrites : celle choisie ici, ou celle que la case a déjà.
            // Une autre (brouillon en retard d'une frame) viserait un fichier peut-être effacé.
            if (photo != null && photo != unsavedPhoto && photo != (target as? EditTarget.ExistingCell)?.cell?.photoFile) {
                throw IOException("Photo is not this editor's")
            }
            // L'écriture prend la photo en charge : un « Terminer » ou un départ pendant qu'elle tourne
            // ne doit pas effacer un fichier que la case va viser. Rendue à l'éditeur si l'écriture échoue.
            val ownedPhoto = photo?.takeIf { it == unsavedPhoto }
            if (ownedPhoto != null) unsavedPhoto = null
            try {
                // Seule la photo choisie dans cet éditeur est à valider : une photo déjà dans la case l'est,
                // et l'exiger bloquerait l'édition d'une case dont le fichier a disparu.
                if (ownedPhoto != null && !photos.commit(ownedPhoto)) throw IOException("Photo could not be committed")
                writeCellContent(target, content, asFolder)
            } catch (exception: Exception) {
                if (ownedPhoto != null) {
                    // Rendue à son éditeur s'il est toujours là ; sinon elle n'est plus à personne, et
                    // on n'efface qu'elle (jamais la photo d'un éditeur ouvert depuis).
                    if (editor.value?.writeToken == writeToken && unsavedPhoto == null) {
                        unsavedPhoto = ownedPhoto
                    } else {
                        photos.delete(ownedPhoto)
                    }
                }
                throw exception
            }
        }
    }

    /** Picto choisi gardé, case écrite, puis ancienne photo effacée si elle n'appartient plus à personne. */
    private suspend fun writeCellContent(target: EditTarget, content: CellContent, asFolder: Boolean) {
        // Le picto choisi d'abord : la case ne vise jamais un picto absent de l'appareil (invariant 2).
        val chosen = chosenBytes
        if (chosen != null && content.pictogramId == chosen.first && !pictogramStore.store(chosen.first, chosen.second)) {
            throw IOException("Pictogram ${chosen.first} could not be stored")
        }
        when (target) {
            is EditTarget.NewCell ->
                if (asFolder) {
                    // À la taille des dossiers du jeu ouvert : un dossier de grandes cases reste en grandes cases.
                    val size = BoardSize.withHome(navigation.value.firstOrNull()) ?: BoardSize.STANDARD
                    repository.addFolder(target.boardId, target.row, target.column, content, size)
                } else {
                    repository.addWord(target.boardId, target.row, target.column, content)
                }
            is EditTarget.ExistingCell -> {
                // L'ancienne photo est lue en base, dans la transaction : jamais un instantané périmé.
                val previousPhoto = repository.updateCell(target.cell, content)
                // Écrite en base : l'ancienne photo, remplacée ou retirée, n'appartient plus à personne.
                if (previousPhoto != null && previousPhoto != content.photoFile) photos.delete(previousPhoto)
            }
        }
    }

    fun onEditorDelete() {
        writeFromEditor { target, writeToken ->
            if (target is EditTarget.ExistingCell) {
                val deletedPhotos = repository.deleteCell(target.cell, target.boardId, openRootBoardId = navigation.value.first())
                for (photo in deletedPhotos) photos.delete(photo)
                // La photo choisie dans CET éditeur seulement, s'il est encore là.
                if (editor.value?.writeToken == writeToken) discardUnsavedPhoto()
            }
        }
    }

    /** Pour modifier le contenu d'un dossier : on y entre, toujours en édition. */
    fun onEditorOpenFolder() {
        val target = editor.value?.target as? EditTarget.ExistingCell ?: return
        val linkedBoardId = target.cell.linkedBoardId ?: return
        editor.value = null
        chosenBytes = null
        discardUnsavedPhoto()
        navigation.update { stack -> if (isShowingTopOf(stack)) stack.adding(linkedBoardId) else stack }
    }

    /** [write] reçoit la cible et le jeton de l'écriture : elle ne touche qu'à ce qui est à elle. */
    private fun writeFromEditor(write: suspend (EditTarget, Long) -> Unit) {
        val current = editor.value ?: return
        if (current.isWriting) return
        val writing = current.copy(isWriting = true, failure = null, writeToken = ++writeCount)
        editor.value = writing
        viewModelScope.launch {
            // Le résultat ne touche que l'éditeur qui l'a lancé : fermé (Terminer, départ) ou rouvert
            // entre-temps, un autre éditeur ne se referme pas et n'affiche pas l'échec d'un autre.
            try {
                write(current.target, writing.writeToken)
                if (editor.compareAndSet(writing, null)) chosenBytes = null
            } catch (exception: SQLiteConstraintException) {
                // Emplacement pris entre-temps (un « + » pas encore rafraîchi) : rien n'est écrit.
                reportEditFailure(current, writing, EditFailure.SlotTakenOrCellGone, exception)
            } catch (exception: CellNotFoundException) {
                reportEditFailure(current, writing, EditFailure.SlotTakenOrCellGone, exception)
            } catch (exception: CellChangedException) {
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
            // Juste après l'ouverture de l'accueil, donc après la détection d'une perte. À part :
            // pendant le ménage, une relance de l'ouverture (accueil introuvable) doit rester possible.
            viewModelScope.launch { repository.sweepOrphanImages() }
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
            // L'accueil réinséré garde son id : la pile ne change pas et le collecteur ne verrait rien.
            verbForms.value = null
            return null
        }
        return DisplayedBoard(
            id = board.id,
            name = board.name,
            grid = layoutGrid(board.rowCount, board.columnCount, board.cells),
            canGoBack = stack.size > 1,
            rootBoardId = stack.first(),
        )
    }
}
