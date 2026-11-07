package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.data.PapotonsDatabase
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val LEO = ProfileEntity(id = 1, name = "Léo", startBoardId = null)

/** Ce que le DAO factice des tests JVM ne sait pas faire : index, clés étrangères, conflits. */
@RunWith(AndroidJUnit4::class)
class BoardDaoTest {
    private lateinit var database: PapotonsDatabase
    private lateinit var boardDao: BoardDao

    private val home = BoardEntity(id = 1, name = "home", rowCount = 2, columnCount = 2, profileId = 1)
    private val folderBoard = BoardEntity(id = 2, name = "folder", rowCount = 1, columnCount = 1, profileId = 1)

    private fun word(row: Int, column: Int, label: String) =
        CellEntity(boardId = home.id, row = row, column = column, label = label, linkedBoardId = null, pictogramId = null, category = null)

    private val folder = CellEntity(boardId = home.id, row = 1, column = 1, label = "folder", linkedBoardId = folderBoard.id, pictogramId = null, category = null)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PapotonsDatabase::class.java).build()
        boardDao = database.boardDao()
        // Toute grille vise un utilisateur (clé étrangère) : celui des grilles de ces tests.
        runBlocking { boardDao.insertProfile(LEO) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedingFillsAnEmptyDatabaseOnlyOnce() = runBlocking {
        assertTrue(boardDao.seedIfEmpty(VocabularySeed(listOf(home, folderBoard), listOf(word(0, 0, "je"), folder)), "Léo"))
        assertFalse(boardDao.seedIfEmpty(VocabularySeed(listOf(home.copy(id = 3)), emptyList()), "Léo"))

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())
        assertEquals(setOf("je", "folder"), stored.cells.map { it.label }.toSet())
        assertEquals(2, boardDao.countBoards())
    }

    @Test
    fun aFailedSeedLeavesNothingBehindAndCanBeRetried() = runBlocking {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.seedIfEmpty(VocabularySeed(listOf(home), listOf(word(0, 0, "a"), word(0, 0, "b"))), "Léo") }
        }

        assertEquals(0, boardDao.countBoards())
        assertTrue(boardDao.seedIfEmpty(VocabularySeed(listOf(home), listOf(word(0, 0, "a"))), "Léo"))
    }

    @Test
    fun cellsAreReadBackAtTheirStoredPositions() = runBlocking {
        boardDao.insertAll(listOf(home), listOf(word(1, 0, "low"), word(0, 1, "right")))

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())

        assertEquals(1 to 0, stored.cells.single { it.label == "low" }.let { it.row to it.column })
        assertEquals(0 to 1, stored.cells.single { it.label == "right" }.let { it.row to it.column })
    }

    @Test
    fun twoCellsCannotShareASlot() {
        runBlocking { boardDao.insertAll(listOf(home), listOf(word(0, 1, "je"))) }

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.insertAll(emptyList(), listOf(word(0, 1, "veux"))) }
        }
    }

    @Test
    fun reinsertingABoardFailsAndKeepsItsCells() = runBlocking {
        boardDao.insertAll(listOf(home), listOf(word(0, 0, "je")))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.insertAll(listOf(home.copy(name = "other")), emptyList()) }
        }

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())
        assertEquals("home", stored.board.name)
        assertEquals(listOf("je"), stored.cells.map { it.label })
    }

    @Test
    fun aBoardOpenedByAFolderCannotBeDeleted() = runBlocking {
        boardDao.insertAll(listOf(home, folderBoard), listOf(folder))

        assertThrows(SQLiteConstraintException::class.java) {
            database.openHelper.writableDatabase.execSQL("DELETE FROM boards WHERE id = ${folderBoard.id}")
        }

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())
        assertEquals(folderBoard.id, stored.cells.single().linkedBoardId)
    }

    @Test
    fun deletingANestedFolderRemovesItsWholeSubtreeWhateverTheIdOrder() = runBlocking {
        // Accueil → A (id 9) → B (id 3) → C (id 7) : un enfant d'id plus petit que son parent ferait
        // échouer RESTRICT si les grilles partaient avant ce qui les vise.
        val boards = listOf(home, BoardEntity(9, "A", 1, 2, profileId = 1), BoardEntity(3, "B", 1, 2, profileId = 1), BoardEntity(7, "C", 1, 1, profileId = 1))
        fun link(boardId: Long, column: Int, target: Long) =
            CellEntity(boardId = boardId, row = 0, column = column, label = "to$target", linkedBoardId = target, pictogramId = null, category = null)
        boardDao.insertAll(boards, listOf(link(1, 0, 9), link(9, 0, 3), link(3, 0, 7), word(1, 0, "reste")))
        val folderCellId = checkNotNull(boardDao.observeBoard(1).first()).cells.single { it.linkedBoardId == 9L }.id

        assertEquals(3, boardDao.deleteCellAndOrphanBoards(folderCellId, setOf(1L)).boardCount)

        assertEquals(1, boardDao.countBoards())
        assertEquals(listOf("reste"), checkNotNull(boardDao.observeBoard(1).first()).cells.map { it.label })
    }

    @Test
    fun aBoardStillLinkedElsewhereAndACycleSurviveAFolderDeletion() = runBlocking {
        // A et B se visent l'un l'autre (cycle) ; S est visée par A et par l'accueil.
        val boards = listOf(home, BoardEntity(2, "A", 1, 3, profileId = 1), BoardEntity(3, "B", 1, 1, profileId = 1), BoardEntity(4, "S", 1, 1, profileId = 1))
        fun link(boardId: Long, column: Int, target: Long) =
            CellEntity(boardId = boardId, row = 0, column = column, label = "to$target", linkedBoardId = target, pictogramId = null, category = null)
        boardDao.insertAll(boards, listOf(link(1, 0, 2), link(1, 1, 4), link(2, 0, 3), link(2, 1, 4), link(3, 0, 2)))
        val folderCellId = checkNotNull(boardDao.observeBoard(1).first()).cells.single { it.linkedBoardId == 2L }.id

        assertEquals(0, boardDao.deleteCellAndOrphanBoards(folderCellId, setOf(1L)).boardCount)

        // Le cycle A↔B reste (rien ne s'efface sans certitude), S aussi : l'accueil la vise encore.
        assertTrue(boardDao.boardExists(2))
        assertTrue(boardDao.boardExists(3))
        assertTrue(boardDao.boardExists(4))
        assertEquals(listOf(4L), checkNotNull(boardDao.observeBoard(1).first()).cells.mapNotNull { it.linkedBoardId })
    }

    @Test
    fun aFolderIsInsertedWithItsBoardOrNotAtAll() = runBlocking {
        boardDao.insertAll(listOf(home), listOf(word(0, 0, "pris")))

        // Emplacement déjà pris : la case échoue, la grille créée juste avant doit partir avec elle.
        val failure = runCatching { boardDao.insertFolder(BoardEntity(name = "dossier", rowCount = 4, columnCount = 6), word(0, 0, "dossier")) }

        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("UNIQUE"))
        assertEquals(1, boardDao.countBoards())
    }

    @Test
    fun anEditChangesOnlyItsCellAndItsFolderBoard() = runBlocking {
        boardDao.insertAll(listOf(home, folderBoard), listOf(word(0, 0, "je"), folder))
        val folderCellId = checkNotNull(boardDao.observeBoard(home.id).first()).cells.single { it.linkedBoardId != null }.id

        boardDao.updateCellContentAndFolderName(folderCellId, null, folderBoard.id, "Jeux", 9813, "NOUN", "photo.jpg", "Les jeux")

        val cells = checkNotNull(boardDao.observeBoard(home.id).first()).cells.associateBy { it.label }
        val edited = cells.getValue("Jeux")
        assertEquals(Triple(1, 1, 9813L), Triple(edited.row, edited.column, edited.pictogramId))
        assertEquals("NOUN", edited.category)
        assertEquals("photo.jpg" to "Les jeux", edited.photoFile to edited.spokenText)
        assertEquals("je", cells.getValue("je").label)
        assertEquals("Jeux", checkNotNull(boardDao.observeBoard(folderBoard.id).first()).board.name)
        assertEquals("home", checkNotNull(boardDao.observeBoard(home.id).first()).board.name)
    }

    @Test
    fun deletingAFolderGivesBackThePhotosOfTheCellsItTook() = runBlocking {
        val boards = listOf(home, BoardEntity(9, "A", 1, 2, profileId = 1))
        val folderCell = CellEntity(boardId = 1, row = 0, column = 0, label = "A", linkedBoardId = 9, pictogramId = null, category = null, photoFile = "dossier.jpg")
        val inside = CellEntity(boardId = 9, row = 0, column = 0, label = "Mamie", linkedBoardId = null, pictogramId = null, category = null, photoFile = "mamie.jpg")
        val stays = CellEntity(boardId = 1, row = 0, column = 1, label = "Papi", linkedBoardId = null, pictogramId = null, category = null, photoFile = "papi.jpg")
        boardDao.insertAll(boards, listOf(folderCell, inside, stays))
        val folderCellId = checkNotNull(boardDao.observeBoard(1).first()).cells.single { it.linkedBoardId == 9L }.id

        val deleted = boardDao.deleteCellAndOrphanBoards(folderCellId, setOf(1L))

        assertEquals(setOf("dossier.jpg", "mamie.jpg"), deleted.photoFiles.toSet())
    }

    @Test
    fun referencedImagesListEveryPictogramAndPhoto() = runBlocking {
        boardDao.insertAll(
            listOf(home, folderBoard),
            listOf(
                word(0, 0, "a").copy(pictogramId = 11),
                // Picto et photo sur la même case : les deux sont visés.
                word(0, 1, "b").copy(pictogramId = 12, photoFile = "mamie.jpg"),
                word(1, 0, "c").copy(pictogramId = 11),
                folder.copy(pictogramId = 13),
            ),
        )

        val referenced = boardDao.referencedImages()

        assertEquals(ReferencedImages(boardCount = 2, pictogramIds = setOf(11L, 12L, 13L), photoFiles = setOf("mamie.jpg")), referenced)
    }

    @Test
    fun aReplacementThatFailsLeavesTheBoardsAsTheyWere() = runBlocking {
        boardDao.insertAll(listOf(home, folderBoard), listOf(word(0, 0, "je"), folder))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.replaceAll(listOf(LEO), listOf(home.copy(name = "new")), listOf(word(0, 0, "a"), word(0, 0, "b"))) }
        }

        assertEquals(listOf("home", "folder"), boardDao.allBoards().map { it.name })
        assertEquals(setOf("je", "folder"), boardDao.allCells().map { it.label }.toSet())
    }

    @Test
    fun aReplacementRemovesEveryOldBoardAndCell() = runBlocking {
        boardDao.insertAll(listOf(home, folderBoard), listOf(word(0, 0, "je"), folder))

        boardDao.replaceAll(listOf(LEO), listOf(home.copy(name = "new")), listOf(word(1, 1, "a")))

        assertEquals(listOf(home.copy(name = "new")), boardDao.allBoards())
        assertEquals(listOf("a"), boardDao.allCells().map { it.label })
    }

    @Test
    fun aBoardWithoutAProfileIsRefused() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.insertAll(listOf(BoardEntity(id = 5, name = "orphan", rowCount = 1, columnCount = 1)), emptyList()) }
        }
    }

    /** Le RESTRICT réel des dossiers imbriqués : les cases d'abord, puis les grilles, puis l'utilisateur ; l'autre reste. */
    @Test
    fun aProfileIsCreatedWithItsVocabularyAndDeletedWithAllItsBoards() = runBlocking {
        val seed = CoreVocabulary.build { "label$it" }
        assertTrue(boardDao.seedIfEmpty(seed, "Léo"))
        val leoBoards = boardDao.allBoards()

        val anna = boardDao.insertProfileWithCoreVocabulary("Anna", seed)
        val annaHome = checkNotNull(boardDao.startBoardCopyOf(anna, HOME_BOARD_ID))
        val nested = boardDao.insertFolder(BoardEntity(name = "dossier", rowCount = 1, columnCount = 1), word(6, 7, "dossier").copy(boardId = annaHome))
        boardDao.insertFolder(BoardEntity(name = "dedans", rowCount = 1, columnCount = 1), word(0, 0, "dedans").copy(boardId = nested))
        assertEquals(anna, boardDao.profileOf(nested))

        boardDao.insertCell(word(6, 7, "Papi").copy(boardId = HOME_BOARD_ID, photoFile = "leo.jpg"))
        boardDao.insertCell(word(6, 6, "Mamie").copy(boardId = annaHome, photoFile = "anna.jpg"))
        val leoCells = boardDao.allCells().filter { cell -> leoBoards.any { it.id == cell.boardId } }

        val deleted = boardDao.deleteProfile(anna)

        assertEquals(seed.boards.size + 2, deleted.boardCount)
        assertEquals(listOf("anna.jpg"), deleted.photoFiles)
        assertEquals(leoBoards, boardDao.allBoards())
        assertEquals(leoCells, boardDao.allCells())
        assertThrows(LastProfileException::class.java) { runBlocking { boardDao.deleteProfile(LEO.id) } }
        assertEquals(listOf(LEO.id), boardDao.allProfiles().map { it.id })
    }

    /** Chaque requête par utilisateur ne regarde que ses grilles : le SQL réel, que le DAO factice imite. */
    @Test
    fun startBoardQueriesStayWithinTheProfile() = runBlocking {
        val seed = CoreVocabulary.build { "label$it" }
        boardDao.seedIfEmpty(seed, "Léo")
        val anna = boardDao.insertProfileWithCoreVocabulary("Anna", seed)
        val annaMedium = checkNotNull(boardDao.startBoardCopyOf(anna, 7))

        assertTrue(annaMedium >= FIRST_USER_BOARD_ID)
        assertEquals(7L, boardDao.startBoardCopyOf(LEO.id, 7))
        assertTrue(boardDao.isStartBoardOf(annaMedium, anna))
        assertFalse(boardDao.isStartBoardOf(7, anna))
        assertFalse(boardDao.isStartBoardOf(annaMedium, LEO.id))
        assertEquals(setOf(7L, 13L, annaMedium, checkNotNull(boardDao.startBoardCopyOf(anna, 13))), boardDao.copiesOf(setOf(7L, 13L)).toSet())
        assertEquals(anna, boardDao.smallestOtherProfileWithBoards(LEO.id))
    }

    @Test
    fun aBoardOpenedByAFolderIsNeverAStartBoard() = runBlocking {
        boardDao.insertAll(listOf(home, folderBoard), listOf(folder))

        assertTrue(boardDao.isStartBoardOf(home.id, LEO.id))
        assertFalse(boardDao.isStartBoardOf(folderBoard.id, LEO.id))
    }

    /** Ensemble ou pas du tout : un utilisateur sans grille ne s'ouvrirait jamais et bloquerait l'export. */
    @Test
    fun aProfileWhoseVocabularyFailsIsNotCreated() = runBlocking {
        boardDao.insertAll(listOf(home), emptyList())
        val clashing = VocabularySeed(listOf(home), listOf(word(0, 0, "a"), word(0, 0, "b")))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.insertProfileWithCoreVocabulary("Anna", clashing) }
        }

        assertEquals(listOf(LEO), boardDao.allProfiles())
        assertEquals(listOf(home), boardDao.allBoards())
    }

    @Test
    fun theExitLockIsWrittenForTheTargetedProfileOnly() = runBlocking {
        val anna = ProfileEntity(id = 5, name = "Anna", startBoardId = null)
        boardDao.insertProfile(anna)

        assertEquals(1, boardDao.setExitLocked(anna.id, isLocked = false))

        assertEquals(listOf(LEO, anna.copy(isExitLocked = false)), boardDao.allProfiles())
        // Un id absent n'écrit rien : le dépôt en fait un échec.
        assertEquals(0, boardDao.setExitLocked(42, isLocked = false))
    }

    @Test
    fun reseedingRemovesOnlyTheProfilesLeftWithoutBoards() = runBlocking {
        boardDao.insertProfile(ProfileEntity(id = 5, name = "Anna", startBoardId = null))

        assertTrue(boardDao.seedIfEmpty(VocabularySeed(listOf(home), listOf(word(0, 0, "je"))), "Léo"))

        assertEquals(listOf(LEO), boardDao.allProfiles())
        assertTrue(boardDao.allBoards().all { it.profileId == LEO.id })
    }
}
