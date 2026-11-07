package com.wobblebot.papotons.data.board

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.wobblebot.papotons.data.CorruptedDatabaseCopies
import com.wobblebot.papotons.data.backup.BackupContent
import com.wobblebot.papotons.data.photo.CELL_PHOTOS_DIRECTORY
import com.wobblebot.papotons.data.photo.CELL_PHOTO_NAME
import com.wobblebot.papotons.data.pictogram.DOWNLOADED_PICTOGRAMS_DIRECTORY
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BoardRepository"
private const val PREFERENCES_RETRY_DELAY_MS = 10_000L

/**
 * Marge du ménage des images : un fichier modifié moins de 2 s avant la création du dépôt n'est pas
 * candidat (dates arrondies à la seconde, horloge qui recule un peu).
 */
private const val SWEEP_MODIFICATION_MARGIN_MS = 2_000L

/** Les seuls noms que le ménage efface : ceux qu'écrivent les pictos téléchargés et les photos. */
private val PICTOGRAM_FILE_NAME = Regex("""(\d+)\.png""")

private fun CellContent.toEntity(boardId: Long, row: Int, column: Int, linkedBoardId: Long?) = CellEntity(
    boardId = boardId,
    row = row,
    column = column,
    label = label,
    linkedBoardId = linkedBoardId,
    pictogramId = pictogramId,
    category = category?.name,
    photoFile = photoFile,
    spokenText = spokenText,
)

/**
 * Posé une fois le vocabulaire de base inséré. Pas un réglage : ne jamais l'effacer, sinon une base
 * perdue repasserait pour un premier lancement, sans alerte.
 */
private val CORE_VOCABULARY_SEEDED = booleanPreferencesKey("core_vocabulary_seeded")

/**
 * Des modifications n'ont pas été sauvegardées dans un fichier. Absente : vrai (une installation qui
 * avait des grilles avant que la clé existe n'a peut-être jamais été sauvegardée) ; seule une
 * installation neuve l'écrit à faux.
 */
private val HAS_UNSAVED_CHANGES = booleanPreferencesKey("has_unsaved_changes")

/** Quand (ms) la base a été trouvée vide alors qu'elle avait déjà été remplie. Retirée par l'adulte. */
private val BOARDS_LOST_AT = longPreferencesKey("boards_lost_at")

/** L'écran de départ choisi dans les Réglages (l'accueil d'une taille). Absent : [HOME_BOARD_ID]. */
private val START_BOARD_ID = longPreferencesKey("start_board_id")

@Singleton
class BoardRepository @Inject constructor(
    private val boardDao: BoardDao,
    private val preferences: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) {
    /** Une perte constatée dont l'écriture a échoué : connue de ce processus seulement. */
    private val unsavedLostBoards = MutableStateFlow(false)

    /** Nombre d'écritures dans ce processus : un export ne se dit à jour que si aucune n'a eu lieu depuis son instantané. */
    private val writeCount = AtomicLong(0)

    private val replacementCount = MutableStateFlow(0)

    /** Monte à chaque restauration : l'écran repart de l'accueil, la grille ouverte n'existe peut-être plus. */
    val replacements: StateFlow<Int> = replacementCount.asStateFlow()

    /** Avant toute édition possible : seules les images plus anciennes sont candidates au ménage. */
    private val createdAtMs = System.currentTimeMillis()

    /** La base a été trouvée vide dans ce processus : perte possible, même si les préférences l'ignorent. */
    @Volatile
    private var wasFoundEmpty = false

    private val hasSweptImages = AtomicBoolean(false)

    /** [ensureHomeBoard] a fait sa détection de perte dans ce processus (et réinséré le vocabulaire si besoin). */
    @Volatile
    private var hasOpenedHome = false

    /** Où le ménage lit et efface ses fichiers ; remplacé dans les tests du ViewModel. */
    internal var fileDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Insère le vocabulaire de base dans une base vide, puis rend l'id de la grille à ouvrir : l'écran
     * de départ choisi (l'accueil d'une taille) s'il en est un, sinon l'accueil standard. Une
     * base vide qui avait déjà été remplie (corrompue, effacée) reçoit aussi le vocabulaire de base,
     * pour qu'on puisse parler, et lève l'alerte de [observeLostBoards]. Une base qui a perdu son
     * accueil ouvre sa plus petite grille : ni plantage en boucle ni chargement sans fin, et rien
     * n'est effacé (invariant 4).
     */
    suspend fun ensureHomeBoard(): Long {
        // L'alerte AVANT la réinsertion : un arrêt entre les deux ne doit pas la faire oublier.
        // `countBoards` d'abord : première requête, c'est elle qui ouvre la base, et une base
        // corrompue est copiée à ce moment-là, avant qu'on ne cherche les copies.
        val isDatabaseEmpty = boardDao.countBoards() == 0
        if (isDatabaseEmpty) wasFoundEmpty = true
        raiseLostBoardsIfNeeded(isDatabaseEmpty)
        val seed = CoreVocabulary.build(context::getString)
        val isSeeded = boardDao.seedIfEmpty(seed.boards, seed.cells)
        if (isSeeded) {
            wasFoundEmpty = true
            Log.i(TAG, "Core vocabulary inserted: ${seed.boards.size} boards, ${seed.cells.size} cells")
        }
        // Le marqueur APRÈS : un arrêt entre les deux laisse une base remplie sans marqueur, que le
        // lancement suivant marque sans alerte.
        markSeeded(isFreshSeed = isSeeded && !copies().hasCopies())
        hasOpenedHome = true
        return openableStartBoard(readStartBoardId())
    }

    /** [startBoardId] s'il est un écran de départ, sinon l'accueil standard, sinon la plus petite grille. */
    private suspend fun openableStartBoard(startBoardId: Long): Long {
        if (isStartBoard(startBoardId)) return startBoardId
        if (startBoardId != HOME_BOARD_ID) {
            Log.e(TAG, "Start board $startBoardId missing or opened by a folder, opening home instead")
            forgetStartBoard()
        }
        if (boardDao.boardExists(HOME_BOARD_ID)) return HOME_BOARD_ID
        // Jamais nul : la base n'est pas vide après seedIfEmpty.
        val fallbackBoardId = checkNotNull(boardDao.smallestBoardId()) { "No board after seeding" }
        Log.e(TAG, "Home board $HOME_BOARD_ID missing, opening board $fallbackBoardId instead")
        return fallbackBoardId
    }

    /**
     * Vrai tant qu'une perte des grilles constatée par [ensureHomeBoard] (au lancement, ou quand
     * l'accueil devient introuvable) n'a pas été acquittée, même si son écriture a échoué.
     */
    fun observeLostBoards(): Flow<Boolean> = preferences.data
        .map { it[BOARDS_LOST_AT] != null }
        .combine(unsavedLostBoards) { isSaved, isUnsaved -> isSaved || isUnsaved }
        // Une lecture ratée ne termine pas le flux : terminé, il figerait l'alerte dans le `combine`
        // de l'écran (cachée pour de bon, ou jamais montrée pour une perte constatée ensuite).
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            // Une fois : la panne peut durer, et l'essai repart toutes les [PREFERENCES_RETRY_DELAY_MS].
            if (attempt == 0L) Log.e(TAG, "Preferences could not be read, retrying", cause)
            delay(PREFERENCES_RETRY_DELAY_MS)
            true
        }
        .distinctUntilChanged()

    /** Un mot à un emplacement libre ; échoue (index unique) si l'emplacement est pris. */
    suspend fun addWord(boardId: Long, row: Int, column: Int, content: CellContent) {
        write { boardDao.insertCell(content.toEntity(boardId, row, column, linkedBoardId = null)) }
    }

    /**
     * Retient l'accueil de [size] comme écran de départ. Faux, sans rien écrire, si ces grilles n'ont
     * pas cet accueil (sauvegarde d'avant les tailles, où l'id peut être un dossier de l'adulte). Un
     * échec d'écriture remonte, rien n'est retenu. Pas une modification des grilles : la sauvegarde
     * `.obz` n'en dépend pas, le drapeau « non sauvegardé » reste tel quel.
     */
    suspend fun setStartBoard(size: BoardSize): Boolean {
        // L'accueil standard s'ouvre toujours au lancement s'il existe : il se choisit de même.
        val isAvailable = if (size == BoardSize.STANDARD) boardDao.boardExists(HOME_BOARD_ID) else isStartBoard(size.homeBoardId)
        if (!isAvailable) {
            Log.e(TAG, "Home board of $size missing or opened by a folder, size not available")
            return false
        }
        preferences.edit { settings -> settings[START_BOARD_ID] = size.homeBoardId }
        return true
    }

    /**
     * Un choix devenu invalide (grilles restaurées) est oublié : sinon une restauration suivante qui
     * ramènerait cet accueil rouvrirait une taille que personne n'a choisie depuis. Au mieux : un
     * échec laisse seulement le repli recommencer au lancement suivant.
     */
    private suspend fun forgetStartBoard() {
        try {
            preferences.edit { settings -> settings.remove(START_BOARD_ID) }
        } catch (exception: IOException) {
            Log.e(TAG, "Invalid start board could not be forgotten", exception)
        }
    }

    /** Une lecture ratée ne doit jamais empêcher de parler : l'accueil standard. */
    private suspend fun readStartBoardId(): Long = try {
        preferences.data.first()[START_BOARD_ID] ?: HOME_BOARD_ID
    } catch (exception: IOException) {
        Log.e(TAG, "Start board could not be read, opening home", exception)
        HOME_BOARD_ID
    }

    /**
     * Un écran de départ est une grille qui existe et qu'aucune case n'ouvre : dans une base d'avant
     * les tailles, l'id d'un accueil de taille peut être celui d'un dossier de l'adulte.
     */
    private suspend fun isStartBoard(boardId: Long): Boolean = boardDao.boardExists(boardId) && boardDao.countLinksTo(boardId) == 0

    /** Un dossier : une grille vide, nommée comme lui, à la taille des dossiers de [size], et la case qui l'ouvre. */
    suspend fun addFolder(boardId: Long, row: Int, column: Int, content: CellContent, size: BoardSize) {
        val board = BoardEntity(name = content.label, rowCount = size.folderRowCount, columnCount = size.folderColumnCount)
        write { boardDao.insertFolder(board, content.toEntity(boardId, row, column, linkedBoardId = null)) }
    }

    /** Le contenu seulement ; un dossier renommé renomme sa grille (titre lu par TalkBack). Rend la photo d'avant. */
    suspend fun updateCell(cell: Cell, content: CellContent): String? = write {
        boardDao.updateCellContentAndFolderName(
            cell.id,
            cell.photoFile,
            cell.linkedBoardId,
            content.label,
            content.pictogramId,
            content.category?.name,
            content.photoFile,
            content.spokenText,
        )
    }

    /**
     * Supprime la case et, pour un dossier, les grilles de son sous-arbre que plus rien ne vise.
     * Les accueils des tailles, la grille qui porte la case et [openRootBoardId] ne sont jamais supprimés : une base
     * vidée par l'édition passerait pour une perte des grilles. Rend les photos des cases supprimées :
     * à effacer, une photo n'appartenant qu'à une case.
     */
    suspend fun deleteCell(cell: Cell, boardId: Long, openRootBoardId: Long): List<String> {
        val kept = BoardSize.entries.mapTo(mutableSetOf()) { it.homeBoardId } + boardId + openRootBoardId
        val deleted = write { boardDao.deleteCellAndOrphanBoards(cell.id, kept) }
        Log.i(TAG, "Cell ${cell.id} deleted with ${deleted.boardCount} boards")
        return deleted.photoFiles
    }

    /**
     * Autour de chaque écriture en base, AVANT elle : le marqueur des copies (son échec empêche l'écriture : une
     * modification ne se fait jamais sans protection), puis le drapeau des changements non
     * sauvegardés (son échec est seulement journalisé : un faux « sauvegardé » ne coûte qu'une
     * pastille, une édition refusée coûterait le travail).
     */
    private suspend fun <T> write(block: suspend () -> T): T {
        copies().markEdited()
        writeCount.incrementAndGet()
        try {
            preferences.edit { it[HAS_UNSAVED_CHANGES] = true }
        } catch (exception: IOException) {
            // Gardé en mémoire : la pastille dit vrai au moins jusqu'à la fin du processus.
            unsavedInProcess.value = true
            Log.e(TAG, "Unsaved changes flag could not be written", exception)
        }
        try {
            return block()
        } finally {
            // Aussi après : un export dont l'instantané a été lu pendant cette écriture ne se dit pas à jour.
            writeCount.incrementAndGet()
        }
    }

    /** Le drapeau n'a pas pu s'écrire : connu de ce processus seulement. */
    private val unsavedInProcess = MutableStateFlow(false)

    /** Vrai tant que des modifications n'ont pas été sauvegardées ; vrai aussi si on ne sait pas. */
    fun observeUnsavedChanges(): Flow<Boolean> = preferences.data
        .map { it[HAS_UNSAVED_CHANGES] ?: true }
        // Comme pour l'alerte de perte : une lecture ratée dit « non sauvegardé » et réessaie, sans
        // terminer le flux (la pastille resterait allumée même après une sauvegarde réussie).
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            if (attempt == 0L) Log.e(TAG, "Unsaved changes flag could not be read, retrying", cause)
            emit(true)
            delay(PREFERENCES_RETRY_DELAY_MS)
            true
        }
        .combine(unsavedInProcess) { isUnsaved, isUnsavedInProcess -> isUnsaved || isUnsavedInProcess }
        .distinctUntilChanged()

    /** Toutes les grilles et cases, et le compte d'écritures au moment de les lire (pour [markSaved]). */
    suspend fun snapshot(): Pair<BackupContent, Long> {
        val count = writeCount.get()
        val (boards, cells) = boardDao.snapshot()
        return BackupContent(boards, cells) to count
    }

    /** Après un export réussi : sauvegardé, sauf si une écriture a eu lieu depuis l'instantané. */
    suspend fun markSaved(writeCountAtSnapshot: Long) {
        try {
            preferences.edit { settings ->
                if (writeCount.get() == writeCountAtSnapshot) {
                    settings[HAS_UNSAVED_CHANGES] = false
                    unsavedInProcess.value = false
                }
            }
        } catch (exception: IOException) {
            Log.e(TAG, "Saved state could not be written", exception)
        }
    }

    /**
     * Remplace toutes les grilles par [content] (déjà validé), en une transaction : un échec laisse
     * la base d'avant. Puis l'alerte de perte est acquittée et l'écran repart de l'accueil.
     */
    suspend fun replaceAll(content: BackupContent) {
        write { boardDao.replaceAll(content.boards, content.cells.map { it.copy(id = 0) }) }
        Log.i(TAG, "Boards replaced: ${content.boards.size} boards, ${content.cells.size} cells")
        acknowledgeLostBoards()
        markSaved(writeCount.get())
        replacementCount.update { it + 1 }
    }

    /**
     * Le marqueur s'écrit AVANT chaque écriture en base : une écriture qui échoue ensuite l'aura
     * avancé pour rien (la protection passe à la copie suivante, compromis accepté), mais jamais
     * une modification n'est faite sans protection. Sur le thread
     * appelant : lister un dossier de quelques entrées et écrire quelques octets.
     */
    private fun copies() = CorruptedDatabaseCopies(context.filesDir)

    suspend fun acknowledgeLostBoards() {
        unsavedLostBoards.value = false
        try {
            preferences.edit { it.remove(BOARDS_LOST_AT) }
            Log.i(TAG, "Lost boards notice acknowledged")
        } catch (exception: IOException) {
            Log.e(TAG, "Lost boards notice could not be cleared", exception)
        }
    }

    /**
     * Perte constatée quand la base est vide alors que le vocabulaire avait déjà été inséré, ou
     * quand une base corrompue a été mise de côté sans que les préférences s'en souviennent
     * (préférences abîmées avec la base, ou remises à zéro avant que l'adulte n'ait vu l'alerte).
     * Le marqueur n'est qu'une alarme : une lecture ou une écriture ratée est journalisée, sans
     * alerte (une fausse alerte sur une installation neuve inquiéterait pour rien), et n'empêche
     * jamais d'ouvrir une grille.
     */
    private suspend fun raiseLostBoardsIfNeeded(isDatabaseEmpty: Boolean) {
        val hasCorruptedCopies = copies().hasCopies()
        var isLossFound = false
        try {
            preferences.edit { settings ->
                val wasSeeded = settings[CORE_VOCABULARY_SEEDED] == true
                isLossFound = (isDatabaseEmpty && wasSeeded) || (!wasSeeded && hasCorruptedCopies)
                if (isLossFound) {
                    Log.e(TAG, "Boards lost (database empty: $isDatabaseEmpty, corrupted copies: $hasCorruptedCopies)")
                    settings[BOARDS_LOST_AT] = System.currentTimeMillis()
                }
            }
        } catch (exception: IOException) {
            // Perte vue mais pas écrite (stockage qui flanche) : la base réinsérée la cacherait aux
            // lancements suivants, elle s'affiche au moins jusqu'à la fin du processus.
            // Lecture ratée aussi : une base vide à côté d'une copie prouve la perte sans les préférences.
            if (isLossFound || (isDatabaseEmpty && hasCorruptedCopies)) unsavedLostBoards.value = true
            Log.e(TAG, "Lost boards check could not be saved (loss found: $isLossFound)", exception)
        }
    }

    /** [isFreshSeed] : le vocabulaire vient d'être inséré, sans copie corrompue ; neuve si jamais remplie. */
    private suspend fun markSeeded(isFreshSeed: Boolean) {
        try {
            preferences.edit { settings ->
                // Rien à sauvegarder sur une installation neuve : le vocabulaire de base revient tout seul.
                if (isFreshSeed && settings[CORE_VOCABULARY_SEEDED] != true) settings[HAS_UNSAVED_CHANGES] = false
                settings[CORE_VOCABULARY_SEEDED] = true
            }
        } catch (exception: IOException) {
            Log.e(TAG, "Core vocabulary marker could not be written", exception)
        }
    }

    /**
     * Une fois par processus, après [ensureHomeBoard] : efface les pictos téléchargés et les photos que
     * plus aucune case ne vise. Lire, puis vérifier, puis effacer : une corruption détectée pendant la
     * lecture vide la base et lève l'alerte ou laisse une copie, et c'est après qu'on le voit. Rien
     * n'est effacé tant qu'une perte est possible : base trouvée vide, alerte levée, copie corrompue
     * (ses images doivent survivre pour la récupérer), préférences illisibles.
     */
    suspend fun sweepOrphanImages() {
        if (!hasSweptImages.compareAndSet(false, true)) return
        // Accessoire : un échec imprévu (base fermée, fichiers inaccessibles) n'efface rien de plus
        // et ne fait jamais planter l'appli, qui doit parler.
        try {
            sweepOrphanImagesOnce()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "Images sweep failed", exception)
        }
    }

    private suspend fun sweepOrphanImagesOnce() {
        val referenced = try {
            boardDao.referencedImages()
        } catch (exception: SQLiteException) {
            Log.e(TAG, "Images sweep skipped: references could not be read", exception)
            return
        }
        val skipReason = when {
            !hasOpenedHome -> "home board not opened yet"
            referenced.boardCount == 0 -> "no board"
            wasFoundEmpty -> "database found empty"
            // Couvert aujourd'hui par la base trouvée vide ou par les copies ; gardé : une perte
            // connue de ce processus seul ne doit jamais dépendre de ces deux-là.
            unsavedLostBoards.value -> "lost boards (unsaved)"
            copies().directory.exists() -> "corrupted database copies"
            isLossRecorded() != false -> "lost boards, or preferences unreadable"
            else -> null
        }
        if (skipReason != null) {
            Log.i(TAG, "Images sweep skipped: $skipReason")
            return
        }
        val cutoffMs = createdAtMs - SWEEP_MODIFICATION_MARGIN_MS
        val (pictograms, photos) = withContext(fileDispatcher) { deleteAllOrphans(referenced, cutoffMs) }
        Log.i(TAG, "Images sweep: $pictograms pictograms and $photos photos deleted")
    }

    /** Rend le nombre de pictos et de photos effacés. */
    private fun deleteAllOrphans(referenced: ReferencedImages, cutoffMs: Long): Pair<Int, Int> {
        val pictograms = deleteOrphans(File(context.filesDir, DOWNLOADED_PICTOGRAMS_DIRECTORY), cutoffMs) { name ->
            val id = PICTOGRAM_FILE_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
            id != null && id !in referenced.pictogramIds
        }
        val photos = deleteOrphans(File(context.filesDir, CELL_PHOTOS_DIRECTORY), cutoffMs) { name ->
            CELL_PHOTO_NAME.matches(name) && name !in referenced.photoFiles
        }
        return pictograms to photos
    }

    /** Rend le nombre de fichiers effacés ; un nom jamais écrit par l'appli reste. */
    private fun deleteOrphans(directory: File, cutoffMs: Long, isOrphan: (String) -> Boolean): Int {
        val files = directory.listFiles()
        if (files == null) {
            // Absent : rien à faire. Présent mais illisible : un ménage qui ne verrait jamais rien.
            if (directory.exists()) Log.w(TAG, "Images sweep: ${directory.name} could not be listed")
            return 0
        }
        return files.count { file ->
            // 0 : date illisible, donc inconnue.
            val modifiedAtMs = file.lastModified()
            if (!file.isFile || modifiedAtMs == 0L || modifiedAtMs >= cutoffMs || !isOrphan(file.name)) return@count false
            file.delete().also { isDeleted -> if (!isDeleted) Log.w(TAG, "An orphan image in ${directory.name} could not be deleted") }
        }
    }

    /** `null` si les préférences sont illisibles : on ne sait pas. */
    private suspend fun isLossRecorded(): Boolean? = try {
        preferences.data.first()[BOARDS_LOST_AT] != null
    } catch (exception: IOException) {
        Log.e(TAG, "Preferences could not be read for the images sweep", exception)
        null
    }

    /** `null` quand la grille n'existe pas. */
    fun observeBoard(boardId: Long): Flow<Board?> = boardDao.observeBoard(boardId).map { it?.toDomain() }
}
