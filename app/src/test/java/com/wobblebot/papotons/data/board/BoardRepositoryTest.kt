package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.data.backup.BackupContent
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.wobblebot.papotons.data.CORRUPTED_DATABASES_DIRECTORY
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.data.createPreferences
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.domain.WordCategory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Nom persisté du marqueur : le changer ferait passer toute installation existante pour neuve. */
private val CORE_VOCABULARY_SEEDED = booleanPreferencesKey("core_vocabulary_seeded")

/** Nom persisté de l'alerte : le changer ferait oublier une perte pas encore acquittée. */
private val BOARDS_LOST_AT = longPreferencesKey("boards_lost_at")

class BoardRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val preferencesFile by lazy { File(folder.root, "settings.preferences_pb") }

    /** Le vrai DataStore, sur fichier : celui de l'appli, avec son gestionnaire de corruption. */
    private val preferences by lazy { createPreferences { preferencesFile } }

    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { folder.root }
    }

    @Test
    fun `the first launch inserts the standard core vocabulary once`() = runTest {
        val dao = FakeBoardDao()
        val repository = BoardRepository(dao, preferences, context)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        repository.ensureHomeBoard()

        assertEquals(1, dao.insertCount)
        val seed = CoreVocabulary.build { "label$it" }.subtree(HOME_BOARD_ID)
        assertEquals(seed.boards.size, dao.boards.value.size)
        assertEquals(seed.cells.size, dao.cells.value.size)
        val home = checkNotNull(repository.observeBoard(HOME_BOARD_ID).first())
        val firstCell = home.cells.single { it.row == 0 && it.column == 0 }
        assertNotNull(firstCell.pictogramId)
        assertEquals(WordCategory.PEOPLE, firstCell.category)
    }

    @Test
    fun `existing boards are never overwritten by the core vocabulary`() = runTest {
        val dao = FakeBoardDao(boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "mine", rowCount = 1, columnCount = 1)))

        BoardRepository(dao, preferences, context).ensureHomeBoard()

        assertEquals(0, dao.insertCount)
        assertEquals(listOf("mine"), dao.boards.value.map { it.name })
    }

    @Test
    fun `a board is read back with each cell at its stored position`() = runTest {
        // Grille non carrée et positions asymétriques : une inversion ligne/colonne ou
        // nombre de lignes/colonnes dans la conversion se voit.
        val dao = FakeBoardDao(
            boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)),
            cells = listOf(
                CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 1, column = 2, label = "corner", linkedBoardId = null, pictogramId = null, category = null),
                CellEntity(id = 2, boardId = HOME_BOARD_ID, row = 0, column = 1, label = "folder", linkedBoardId = 7, pictogramId = null, category = null),
            ),
        )

        val board = checkNotNull(BoardRepository(dao, preferences, context).observeBoard(HOME_BOARD_ID).first())

        assertEquals("home", board.name)
        assertEquals(2, board.rowCount)
        assertEquals(3, board.columnCount)
        val corner = board.cells.single { it.label == "corner" }
        assertEquals(1 to 2, corner.row to corner.column)
        val folder = board.cells.single { it.label == "folder" }
        assertEquals(0 to 1, folder.row to folder.column)
        assertEquals(7L, folder.linkedBoardId)
    }

    @Test
    fun `pictogram and category are read back, an unknown category as none`() = runTest {
        val dao = FakeBoardDao(
            boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 1, columnCount = 3)),
            cells = listOf(
                CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "full", linkedBoardId = null, pictogramId = 6632, category = "PEOPLE"),
                CellEntity(id = 2, boardId = HOME_BOARD_ID, row = 0, column = 1, label = "bare", linkedBoardId = null, pictogramId = null, category = null),
                CellEntity(id = 3, boardId = HOME_BOARD_ID, row = 0, column = 2, label = "future", linkedBoardId = null, pictogramId = 2, category = "FUTURE"),
            ),
        )

        val cells = checkNotNull(BoardRepository(dao, preferences, context).observeBoard(HOME_BOARD_ID).first()).cells.associateBy { it.label }

        assertEquals(6632L, cells.getValue("full").pictogramId)
        assertEquals(WordCategory.PEOPLE, cells.getValue("full").category)
        assertEquals(null, cells.getValue("bare").pictogramId)
        assertEquals(null, cells.getValue("bare").category)
        assertEquals(2L, cells.getValue("future").pictogramId)
        assertEquals(null, cells.getValue("future").category)
    }

    @Test
    fun `a database that lost its home board opens its smallest board`() = runTest {
        val dao = FakeBoardDao(
            boards = listOf(
                BoardEntity(id = 9, name = "later", rowCount = 1, columnCount = 1),
                BoardEntity(id = 5, name = "orphan", rowCount = 1, columnCount = 1),
            ),
        )

        assertEquals(5L, BoardRepository(dao, preferences, context).ensureHomeBoard())
        assertEquals(0, dao.insertCount)
    }

    @Test
    fun `an unknown board is observed as null`() = runTest {
        val repository = BoardRepository(FakeBoardDao(), preferences, context)

        assertEquals(null, repository.observeBoard(42).first())
    }

    @Test
    fun `the first launch sets the marker and raises no alert`() = runTest {
        val repository = BoardRepository(FakeBoardDao(), preferences, context)

        repository.ensureHomeBoard()

        assertEquals(true, preferences.data.first()[CORE_VOCABULARY_SEEDED])
        assertFalse(repository.observeLostBoards().first())
    }

    @Test
    fun `an emptied database gets the core vocabulary back and raises an alert until acknowledged`() = runTest {
        preferences.edit { it[CORE_VOCABULARY_SEEDED] = true }
        val dao = FakeBoardDao()
        val repository = BoardRepository(dao, preferences, context)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())

        assertEquals(1, dao.insertCount)
        assertTrue(repository.observeLostBoards().first())
        repository.acknowledgeLostBoards()
        assertFalse(repository.observeLostBoards().first())

        // Le marqueur reste : une perte suivante est signalée à son tour.
        dao.boards.value = emptyList()
        repository.ensureHomeBoard()
        assertTrue(repository.observeLostBoards().first())
    }

    @Test
    fun `an unacknowledged alert survives the next launches`() = runTest {
        preferences.edit { it[CORE_VOCABULARY_SEEDED] = true }
        val dao = FakeBoardDao()
        BoardRepository(dao, preferences, context).ensureHomeBoard()

        val nextLaunch = BoardRepository(dao, preferences, context)
        nextLaunch.ensureHomeBoard()

        assertTrue(nextLaunch.observeLostBoards().first())
    }

    @Test
    fun `a second launch raises no alert`() = runTest {
        val dao = FakeBoardDao()
        BoardRepository(dao, preferences, context).ensureHomeBoard()

        val repository = BoardRepository(dao, preferences, context)
        repository.ensureHomeBoard()

        assertEquals(1, dao.insertCount)
        assertFalse(repository.observeLostBoards().first())
    }

    @Test
    fun `a loss is recorded before the core vocabulary goes back in`() = runTest {
        preferences.edit { it[CORE_VOCABULARY_SEEDED] = true }
        val dao = FakeBoardDao().apply { insertFailure = IllegalStateException("killed") }
        val repository = BoardRepository(dao, preferences, context)

        runCatching { repository.ensureHomeBoard() }

        assertTrue(repository.observeLostBoards().first())
    }

    @Test
    fun `a kept copy of a corrupted database means a loss even without marker`() = runTest {
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-copy").mkdirs()
        val repository = BoardRepository(FakeBoardDao(), preferences, context)

        repository.ensureHomeBoard()

        assertTrue(repository.observeLostBoards().first())
    }

    @Test
    fun `a copy made while opening the database is seen`() = runTest {
        // Préférences perdues avec la base : seule la copie, faite à l'ouverture, signale la perte.
        val dao = FakeBoardDao().apply { onCountBoards = { File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-copy").mkdirs() } }
        val repository = BoardRepository(dao, preferences, context)

        repository.ensureHomeBoard()

        assertTrue(repository.observeLostBoards().first())
    }

    @Test
    fun `unreadable preferences open the core vocabulary without any alert`() = runTest {
        val unreadable = FakePreferences().apply { readFailure = IOException("unreadable") }
        val dao = FakeBoardDao()
        val repository = BoardRepository(dao, unreadable, context)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())

        assertEquals(1, dao.insertCount)
        // Lu sur le faux lui-même : `observeLostBoards` réessaie sans fin sur une lecture ratée.
        assertNull(unreadable.current[BOARDS_LOST_AT])
    }

    @Test
    fun `an existing installation without marker gets one, so a later loss is noticed`() = runTest {
        val dao = FakeBoardDao(boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "mine", rowCount = 1, columnCount = 1)))
        val repository = BoardRepository(dao, preferences, context)

        repository.ensureHomeBoard()
        assertEquals(0, dao.insertCount)
        assertFalse(repository.observeLostBoards().first())

        dao.boards.value = emptyList()
        repository.ensureHomeBoard()

        assertTrue(repository.observeLostBoards().first())
    }

    @Test
    fun `a failed insertion leaves no marker`() = runTest {
        val dao = FakeBoardDao().apply { insertFailure = IllegalStateException("disk full") }
        val repository = BoardRepository(dao, preferences, context)

        try {
            repository.ensureHomeBoard()
            fail("the insertion failure must surface")
        } catch (expected: IllegalStateException) {
            assertNull(preferences.data.first()[CORE_VOCABULARY_SEEDED])
        }
    }

    @Test
    fun `a corrupted preferences file is replaced and the marker set again`() = runTest {
        // 0xFF répété : varint sans fin, jamais un protobuf valide.
        preferencesFile.writeBytes(ByteArray(64) { 0xFF.toByte() })
        val dao = FakeBoardDao(boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "mine", rowCount = 1, columnCount = 1)))
        val repository = BoardRepository(dao, preferences, context)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())

        assertEquals(true, preferences.data.first()[CORE_VOCABULARY_SEEDED])
        assertFalse(repository.observeLostBoards().first())
    }

    @Test
    fun `reset preferences next to a kept copy raise the alert again`() = runTest {
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-copy").mkdirs()
        val dao = FakeBoardDao(boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "core", rowCount = 1, columnCount = 1)))
        val repository = BoardRepository(dao, preferences, context)

        repository.ensureHomeBoard()

        assertTrue(repository.observeLostBoards().first())
    }

    @Test
    fun `a failed preferences read does not end the alert stream`() = runTest {
        val flaky = FakePreferences().apply { readFailure = IOException("busy") }
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { BoardRepository(FakeBoardDao(), flaky, context).observeLostBoards().collect { seen += it } }
        runCurrent()

        flaky.readFailure = null
        flaky.updateData { it.toMutablePreferences().apply { set(BOARDS_LOST_AT, 1L) } }
        advanceTimeBy(60_000)

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `a loss that could not be saved is still shown for the session`() = runTest {
        val failing = FakePreferences().apply {
            updateData { it.toMutablePreferences().apply { set(CORE_VOCABULARY_SEEDED, true) } }
            writeFailure = IOException("disk full")
        }
        val repository = BoardRepository(FakeBoardDao(), failing, context)

        repository.ensureHomeBoard()

        assertTrue(repository.observeLostBoards().first())
        repository.acknowledgeLostBoards()
        assertFalse(repository.observeLostBoards().first())
    }

    @Test
    fun `a preferences write failure never keeps the board from opening`() = runTest {
        val failing = FakePreferences().apply { writeFailure = IOException("disk full") }
        val repository = BoardRepository(FakeBoardDao(), failing, context)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        // Premier lancement : rien de perdu, même si rien n'a pu s'écrire.
        assertFalse(repository.observeLostBoards().first())
    }

    @Test
    fun `a copy proves a loss even when the preferences cannot be read`() = runTest {
        val unreadable = FakePreferences().apply { readFailure = IOException("unreadable") }
        val dao = FakeBoardDao().apply { onCountBoards = { File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-copy").mkdirs() } }
        val repository = BoardRepository(dao, unreadable, context)

        repository.ensureHomeBoard()

        unreadable.readFailure = null
        assertTrue(repository.observeLostBoards().first())
    }

    private val photoOfMamie = "0a1b2c3d-0000-4000-8000-00000000000a.jpg"
    private val orphanPhoto = "0a1b2c3d-0000-4000-8000-00000000000b.jpg"

    /** Une image écrite il y a une heure (ou il y a [ageMs]). */
    private fun image(directory: String, name: String, ageMs: Long = 3_600_000L): File =
        File(folder.root, "$directory/$name").apply {
            parentFile?.mkdirs()
            writeText("image")
            setLastModified(System.currentTimeMillis() - ageMs)
        }

    /** Pictos 11 (deux cases) et 12 visés, une photo visée ; à côté, des images que plus rien ne vise. */
    private fun boardsWithImages(): FakeBoardDao {
        image("pictograms", "11.png")
        image("pictograms", "12.png")
        image("pictograms", "13.png")
        image("photos", photoOfMamie)
        image("photos", orphanPhoto)
        fun cell(id: Long, column: Int, pictogramId: Long?, photo: String?) = CellEntity(
            id = id, boardId = HOME_BOARD_ID, row = 0, column = column, label = "c$id", linkedBoardId = null,
            pictogramId = pictogramId, category = null, photoFile = photo, spokenText = null,
        )
        return FakeBoardDao(
            boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 1, columnCount = 4)),
            cells = listOf(cell(1, 0, 11, null), cell(2, 1, 11, null), cell(3, 2, 12, photoOfMamie)),
        )
    }

    private fun remaining(directory: String): Set<String> = File(folder.root, directory).list().orEmpty().toSet()

    @Test
    fun `the sweep deletes only the images no cell points to`() = runTest {
        val dao = boardsWithImages()
        image("pictograms", "notes.txt")
        // Des noms que l'appli n'écrit pas dans ce dossier : jamais les siens.
        image("photos", "autre.jpg")
        image("photos", "13.png")
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()

        repository.sweepOrphanImages()

        assertEquals(setOf("11.png", "12.png", "notes.txt"), remaining("pictograms"))
        assertEquals(setOf(photoOfMamie, "autre.jpg", "13.png"), remaining("photos"))
    }

    @Test
    fun `an image written since the start is never swept`() = runTest {
        val dao = boardsWithImages()
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()
        image("pictograms", "14.png", ageMs = 0)
        // Dans la marge de 2 s : gardé ; au-delà : orphelin comme un autre.
        image("pictograms", "15.png", ageMs = 1_000)
        image("pictograms", "16.png", ageMs = 10_000)
        // Date 0 : illisible, donc inconnue.
        image("pictograms", "17.png").setLastModified(0)

        repository.sweepOrphanImages()

        assertEquals(setOf("11.png", "12.png", "14.png", "15.png", "17.png"), remaining("pictograms"))
    }

    @Test
    fun `the sweep runs once per process`() = runTest {
        val repository = BoardRepository(boardsWithImages(), FakePreferences(), context)
        repository.ensureHomeBoard()
        repository.sweepOrphanImages()
        assertFalse("13.png" in remaining("pictograms"))
        image("pictograms", "13.png")

        repository.sweepOrphanImages()

        assertTrue("13.png" in remaining("pictograms"))
    }

    private fun assertNothingSwept() {
        assertEquals(setOf("11.png", "12.png", "13.png"), remaining("pictograms"))
        assertEquals(setOf(photoOfMamie, orphanPhoto), remaining("photos"))
    }

    @Test
    fun `nothing is swept while the lost boards alert is up, even raised during the read`() = runTest {
        val preferences = FakePreferences()
        val dao = boardsWithImages()
        val repository = BoardRepository(dao, preferences, context)
        repository.ensureHomeBoard()
        // L'alerte levée pendant la lecture des références : c'est après elle qu'on la vérifie.
        dao.onCountBoards = { runBlocking { preferences.edit { it[BOARDS_LOST_AT] = 1L } } }

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    @Test
    fun `nothing is swept while a corrupted database copy exists, even made during the read`() = runTest {
        val dao = boardsWithImages()
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()
        // Corruption détectée par la lecture des références : la copie arrive pendant celle-ci.
        dao.onCountBoards = { File(folder.root, CORRUPTED_DATABASES_DIRECTORY).mkdirs() }

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    @Test
    fun `nothing is swept when the preferences cannot be read`() = runTest {
        val preferences = FakePreferences()
        val repository = BoardRepository(boardsWithImages(), preferences, context)
        repository.ensureHomeBoard()
        preferences.readFailure = IOException("unreadable")

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    @Test
    fun `nothing is swept in a process that found the database empty`() = runTest {
        val images = boardsWithImages()
        val dao = FakeBoardDao()
        val repository = BoardRepository(dao, FakePreferences(), context)
        // Vide au lancement (perte que rien n'a retenue), puis grilles revenues.
        repository.ensureHomeBoard()
        dao.boards.value = images.boards.value
        dao.cells.value = images.cells.value

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    @Test
    fun `nothing is swept before the home board was opened`() = runTest {
        val repository = BoardRepository(boardsWithImages(), FakePreferences(), context)

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    @Test
    fun `nothing is swept when the references cannot be read`() = runTest {
        val dao = boardsWithImages()
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()
        dao.onCountBoards = { throw SQLiteException("disk I/O error") }

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    @Test
    fun `nothing is swept when the database is emptied while being read`() = runTest {
        val dao = boardsWithImages()
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()
        // Corruption détectée à la lecture : la base est copiée puis rouverte vide.
        dao.onCountBoards = {
            dao.cells.value = emptyList()
            dao.boards.value = emptyList()
        }

        repository.sweepOrphanImages()

        assertNothingSwept()
    }

    private val hasUnsavedChanges = booleanPreferencesKey("has_unsaved_changes")

    private fun homeOnly() = FakeBoardDao(boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 1, columnCount = 2)))

    @Test
    fun `boards made before the flag existed count as unsaved`() = runTest {
        val preferences = FakePreferences()
        preferences.edit { it[CORE_VOCABULARY_SEEDED] = true }
        val repository = BoardRepository(homeOnly(), preferences, context)
        repository.ensureHomeBoard()

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `a fresh install has nothing to save`() = runTest {
        val repository = BoardRepository(FakeBoardDao(), FakePreferences(), context)
        repository.ensureHomeBoard()

        assertFalse(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `an edit marks the boards unsaved before writing`() = runTest {
        val preferences = FakePreferences()
        preferences.edit { it[hasUnsavedChanges] = false }
        val dao = homeOnly()
        val repository = BoardRepository(dao, preferences, context)
        dao.insertFailure = IllegalStateException("disk full")

        try {
            repository.replaceAll(BackupContent(dao.profiles.value, dao.boards.value, emptyList()))
            fail("The insert should fail")
        } catch (exception: IllegalStateException) {
            // Échec après le drapeau : un faux « non sauvegardé » ne coûte rien.
        }

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `a flag that cannot be written never fails the edit`() = runTest {
        val preferences = FakePreferences().apply { writeFailure = IOException("disk full") }
        val dao = homeOnly()

        BoardRepository(dao, preferences, context).addWord(HOME_BOARD_ID, 0, 0, CellContent("eau", null, null, null, null))

        assertEquals(listOf("eau"), dao.cells.value.map { it.label })
    }

    @Test
    fun `a backup marks the boards saved, unless an edit came after its snapshot`() = runTest {
        val repository = BoardRepository(homeOnly(), FakePreferences(), context)
        val (_, before) = repository.snapshot()
        repository.addWord(HOME_BOARD_ID, 0, 0, CellContent("eau", null, null, null, null))

        repository.markSaved(before)
        assertTrue(repository.observeUnsavedChanges().first())

        val (_, after) = repository.snapshot()
        repository.markSaved(after)
        assertFalse(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `a restore replaces every board, clears the alert and counts`() = runTest {
        val preferences = FakePreferences()
        preferences.edit { it[BOARDS_LOST_AT] = 1L }
        val dao = homeOnly()
        val repository = BoardRepository(dao, preferences, context)
        val restored = BackupContent(
            listOf(ProfileEntity(FIRST_PROFILE_ID, "Léo", startBoardId = null)),
            listOf(
                BoardEntity(id = HOME_BOARD_ID, name = "restored", rowCount = 2, columnCount = 2, profileId = FIRST_PROFILE_ID, coreBoardId = HOME_BOARD_ID),
                BoardEntity(id = 9, name = "nine", rowCount = 1, columnCount = 1, profileId = FIRST_PROFILE_ID, coreBoardId = null),
            ),
            listOf(CellEntity(id = 77, boardId = 9, row = 0, column = 0, label = "x", linkedBoardId = null, pictogramId = null, category = null)),
        )

        repository.replaceAll(restored)

        assertEquals(listOf("restored", "nine"), dao.boards.value.map { it.name })
        assertEquals(listOf("x"), dao.cells.value.map { it.label })
        assertFalse(repository.observeLostBoards().first())
        assertFalse(repository.observeUnsavedChanges().first())
        assertEquals(1, repository.replacements.value)
    }

    @Test
    fun `a restore that cannot protect the copies writes nothing`() = runTest {
        // Une copie, et un marqueur impossible à écrire (un dossier occupe son nom).
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-x").mkdirs()
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY.edited/occupied").mkdirs()
        val dao = homeOnly()
        val repository = BoardRepository(dao, FakePreferences(), context)

        try {
            repository.replaceAll(BackupContent(dao.profiles.value, listOf(BoardEntity(id = HOME_BOARD_ID, name = "restored", rowCount = 1, columnCount = 1, profileId = FIRST_PROFILE_ID)), emptyList()))
            fail("The restore should fail")
        } catch (exception: IOException) {
            // Le marqueur d'abord : rien n'est écrit sans lui.
        }

        assertEquals(listOf("home"), dao.boards.value.map { it.name })
        assertEquals(0, repository.replacements.value)
    }

    @Test
    fun `boards found lost count as unsaved`() = runTest {
        val preferences = FakePreferences()
        preferences.edit { it[CORE_VOCABULARY_SEEDED] = true }
        val repository = BoardRepository(FakeBoardDao(), preferences, context)
        repository.ensureHomeBoard()

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `a first launch next to a corrupted copy counts as unsaved`() = runTest {
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-x").mkdirs()
        val repository = BoardRepository(FakeBoardDao(), FakePreferences(), context)
        repository.ensureHomeBoard()

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `every kind of edit marks the boards unsaved`() = runTest {
        val word = CellContent("eau", null, null, null, null)
        val edits: List<suspend (BoardRepository, FakeBoardDao) -> Unit> = listOf(
            { repository, _ -> repository.addWord(HOME_BOARD_ID, 0, 0, word) },
            { repository, _ -> repository.addFolder(HOME_BOARD_ID, 0, 0, word, BoardSize.STANDARD) },
            { repository, dao ->
                val cell = dao.cells.value.single().let { Cell(it.id, it.row, it.column, it.label, it.linkedBoardId) }
                repository.updateCell(cell, word.copy(label = "lait"))
            },
            { repository, dao ->
                val cell = dao.cells.value.single().let { Cell(it.id, it.row, it.column, it.label, it.linkedBoardId) }
                repository.deleteCell(cell, HOME_BOARD_ID, HOME_BOARD_ID)
            },
        )
        for ((index, edit) in edits.withIndex()) {
            val preferences = FakePreferences()
            preferences.edit { it[hasUnsavedChanges] = false }
            val dao = FakeBoardDao(
                boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 2)),
                cells = if (index < 2) emptyList() else listOf(CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 1, column = 1, label = "x", linkedBoardId = null, pictogramId = null, category = null)),
            )
            val repository = BoardRepository(dao, preferences, context)

            edit(repository, dao)

            assertTrue("edit $index", repository.observeUnsavedChanges().first())
        }
    }

    @Test
    fun `an edit whose flag cannot be written still shows as unsaved`() = runTest {
        val preferences = FakePreferences()
        preferences.edit { it[hasUnsavedChanges] = false }
        val repository = BoardRepository(homeOnly(), preferences, context)
        preferences.writeFailure = IOException("disk full")

        repository.addWord(HOME_BOARD_ID, 0, 0, CellContent("eau", null, null, null, null))

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `a backup whose snapshot was read during an edit stays unsaved`() = runTest {
        val dao = homeOnly()
        val repository = BoardRepository(dao, FakePreferences(), context)
        dao.insertGate = kotlinx.coroutines.CompletableDeferred()
        val edit = launch { repository.addWord(HOME_BOARD_ID, 0, 0, CellContent("eau", null, null, null, null)) }
        // L'écriture a commencé (drapeau, compte) mais la base ne l'a pas encore.
        runCurrent()
        val (content, count) = repository.snapshot()
        assertTrue(content.cells.isEmpty())
        checkNotNull(dao.insertGate).complete(Unit)
        edit.join()

        repository.markSaved(count)

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `a backup finishing while an edit is being written stays unsaved`() = runTest {
        val dao = homeOnly()
        val repository = BoardRepository(dao, FakePreferences(), context)
        val (_, count) = repository.snapshot()
        dao.insertGate = kotlinx.coroutines.CompletableDeferred()
        val edit = launch { repository.addWord(HOME_BOARD_ID, 0, 0, CellContent("eau", null, null, null, null)) }
        runCurrent()

        repository.markSaved(count)

        assertTrue(repository.observeUnsavedChanges().first())
        checkNotNull(dao.insertGate).complete(Unit)
        edit.join()
    }

    @Test
    fun `boards inserted before an interrupted first launch count as unsaved`() = runTest {
        // Base remplie, marqueur jamais écrit (arrêt entre les deux) : pas une installation neuve.
        val repository = BoardRepository(homeOnly(), FakePreferences(), context)
        repository.ensureHomeBoard()

        assertTrue(repository.observeUnsavedChanges().first())
    }
}
