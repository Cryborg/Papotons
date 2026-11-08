package com.wobblebot.papotons.data.backup

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.CORRUPTED_DATABASES_DIRECTORY
import com.wobblebot.papotons.data.ClosableDatabases
import com.wobblebot.papotons.data.CorruptedDatabaseCopies
import com.wobblebot.papotons.data.OpenedDatabase
import com.wobblebot.papotons.data.PapotonsDatabase
import com.wobblebot.papotons.data.PapotonsDatabases
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.createPreferences
import com.wobblebot.papotons.data.photo.CellPhotos
import com.wobblebot.papotons.data.pictogram.PictogramImages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

private const val FIXTURES = "corrupted-databases"
private const val IN_USE_DATABASE = "recovery-in-use.db"

/** Une lecture qui s'arrête d'elle-même tient en quelques secondes ; sans arrêt, des heures. */
private const val TABLE_END_TIMEOUT_MS = 60_000L

/** Ce qui revient d'une copie : utilisateurs, grilles, cases gardées, cases annoncées par l'index. */
private data class Recovered(val profiles: Int, val boards: Int, val cells: Int, val announced: Int?)

/**
 * TODO-8 : les vraies bases abîmées des fixtures (`androidTest/assets/corrupted-databases/`, voir
 * `DatabaseCorruptionFixturesTest`) posées comme des copies de la fabrique, puis relues par
 * [BoardBackup] comme l'adulte le ferait. Le bilan de chaque panne fixe ce que la récupération sait
 * en tirer ; `docs/designs/TODO-8.md` dit pourquoi.
 */
@RunWith(AndroidJUnit4::class)
class CorruptedCopyRecoveryFixturesTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PapotonsDatabase::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
    private val copies = CorruptedDatabaseCopies(context.filesDir)
    private val preferencesFile = File(context.cacheDir, "test-recovery-${System.nanoTime()}.preferences_pb")
    private lateinit var database: PapotonsDatabase
    private lateinit var repository: BoardRepository
    private lateinit var backup: BoardBackup

    @Before
    fun setUp(): Unit = runBlocking {
        cleanUp()
        database = Room.inMemoryDatabaseBuilder(context, PapotonsDatabase::class.java).build()
        val dao = database.boardDao()
        repository = BoardRepository(PapotonsDatabases { _, _ -> OpenedDatabase(dao, isLost = { false }, isReplaced = { false }) }, createPreferences { preferencesFile }, context)
        val pictograms = PictogramImages(context).also { it.loadBundled() }
        backup = BoardBackup(context, repository, pictograms, CellPhotos(context))
        repository.ensureHomeBoard()
    }

    @After
    fun tearDown() {
        database.close()
        preferencesFile.delete()
        cleanUp()
    }

    private fun cleanUp() {
        copies.directory.deleteRecursively()
        File(context.filesDir, "$CORRUPTED_DATABASES_DIRECTORY.edited").delete()
        context.deleteDatabase(IN_USE_DATABASE)
    }

    @Test
    fun healthy() = check("healthy", Recovered(profiles = 2, boards = 7, cells = 224, announced = 224))

    /** Rien ne se lit par SQLite : aucune copie proposée. */
    @Test
    fun headerZeroed() = check("header-zeroed", null)

    @Test
    fun fileCutInHalf() = check("file-cut-in-half", null)

    /** Les grilles perdues, leurs cases ne visent plus rien : rebâtir des grilles sans nom ni taille est hors champ. */
    @Test
    fun boardsPageRandom() = check("boards-page-random", null)

    /** L'index unique seul est abîmé : tout revient, et le compte passe par l'autre index. */
    @Test
    fun cellsIndexPageRandom() = check("cells-index-page-random", Recovered(profiles = 2, boards = 7, cells = 224, announced = 224))

    /** Les cases 83 à 125 perdues ; la 126 revient sans sa photo, au nom coupé par les zéros. */
    @Test
    fun cellsPageHalfZeroed() = check("cells-page-half-zeroed", Recovered(profiles = 2, boards = 7, cells = 181, announced = 224))

    /**
     * La 181 perdue ; la 201, au libellé brouillé, écartée ; la 166 revient avec son libellé, son picto
     * et sa catégorie, sans sa photo ni sa phrase, brouillées.
     */
    @Test
    fun cellsPageRandomBytes() = check("cells-page-random-bytes", Recovered(profiles = 2, boards = 7, cells = 222, announced = 224))

    /**
     * Ni `max(id)` ni `sqlite_sequence` ne se lisent : la lecture va jusqu'à une longue suite de
     * tranches vides. Les cases 164 à 224 sont perdues ; la 163, qu'un parcours de la table ne
     * dépasse pas, se relit par son id.
     */
    @Test(timeout = TABLE_END_TIMEOUT_MS)
    fun cellsLastPageAndSequenceRandom() = check("cells-last-page-and-sequence-random", Recovered(profiles = 2, boards = 7, cells = 163, announced = 224))

    /** Confirmée, la récupération remplace les grilles par celles relues, et ne les dit pas sauvegardées. */
    @Test
    fun aRecoveredCopyReplacesTheBoardsAndStaysUnsaved(): Unit = runBlocking {
        placeCopy("cells-page-half-zeroed")
        repository.markSaved(repository.snapshot().second)
        val pending = backup.analyzeCorruptedCopies().single()

        backup.restore(pending)

        val (profiles, boards, cells) = repository.snapshot().first
        assertEquals(pending.content.profiles, profiles)
        assertEquals(pending.content.boards, boards)
        assertEquals(pending.content.cells.map { it.copy(id = 0) }, cells.map { it.copy(id = 0) })
        assertTrue(repository.observeUnsavedChanges().first())
        // La copie reste, intacte : une autre tentative, et la sauvegarde pour un expert.
        assertTrue(File(copies.directory, "000001-test/${PapotonsDatabase.NAME}").readBytes().contentEquals(fixtureBytes("cells-page-half-zeroed")))
        assertEquals(1, backup.analyzeCorruptedCopies().size)
    }

    /**
     * La clé de la case 1 réécrite en 127, sans erreur : une recherche par id se perd dans la feuille
     * (`BETWEEN 1 AND 100` ne rend rien), la lecture de la table entière rend tout. Les deux cases
     * d'id 127 reviennent, chacune à sa place.
     */
    @Test
    fun cellsFirstKeyGarbled() = check("cells-first-key-garbled", Recovered(profiles = 2, boards = 7, cells = 224, announced = 224))

    /** Au prochain schéma, la récupération doit apprendre à le lire : ce test le rappelle. */
    @Test
    fun theRecoveryReadsTheCurrentSchema() {
        assertEquals(database.openHelper.readableDatabase.version, RECOVERABLE_SCHEMAS.last)
    }

    /** Une séquence brouillée vers le haut, sur une table saine : lue d'un coup, la séquence n'y change rien. */
    @Test(timeout = TABLE_END_TIMEOUT_MS)
    fun aScrambledSequenceDoesNotMakeTheReadingEndless() = checkEdited("UPDATE sqlite_sequence SET seq = 9000000000000000000 WHERE name = 'cells'", Recovered(2, 7, 224, 224))

    /** Une séquence en retard sur la table : le plus grand des deux. */
    @Test
    fun aSequenceBehindTheTableDoesNotCutTheReading() = checkEdited("UPDATE sqlite_sequence SET seq = 100 WHERE name = 'cells'", Recovered(2, 7, 224, 224))

    /** Une base d'une version future de Papotons, ou d'autre chose : rien n'est proposé. */
    @Test
    fun aSchemaPapotonsNeverWroteIsNotRead() {
        checkEdited("PRAGMA user_version = 6", null)
        checkEdited("PRAGMA user_version = 0", null)
    }

    /** Une copie que le stockage ne rend pas n'empêche pas les autres ; seule, c'est un échec, pas « rien de lisible ». */
    @Test
    fun aCopyThatCannotBeReadDoesNotStopTheOthers(): Unit = runBlocking {
        placeCopy("healthy", "000001-a")
        placeCopy("healthy", "000002-b")
        assertTrue(File(copies.directory, "000002-b/${PapotonsDatabase.NAME}").setReadable(false, false))

        assertEquals(listOf("000001-a"), backup.analyzeCorruptedCopies().map { it.recovery?.copyName })

        File(copies.directory, "000001-a").deleteRecursively()
        assertThrows(IOException::class.java) { runBlocking { backup.analyzeCorruptedCopies() } }
    }

    /** La copie de travail porte des prénoms : elle ne reste pas après la lecture. */
    @Test
    fun theWorkingCopyIsGoneAfterTheReading(): Unit = runBlocking {
        placeCopy("cells-page-half-zeroed")

        backup.analyzeCorruptedCopies()

        assertFalse(File(context.noBackupFilesDir, "recovery").exists())
    }

    /** La plus récente d'abord ; une copie illisible n'empêche pas les autres. */
    @Test
    fun severalCopiesComeNewestFirst(): Unit = runBlocking {
        placeCopy("healthy", "000001-a")
        placeCopy("header-zeroed", "000002-b")
        placeCopy("cells-page-random-bytes", "000003-c")

        assertEquals(listOf(222, 224), backup.analyzeCorruptedCopies().map { it.cellCount })
    }

    /**
     * Une vraie copie de la fabrique : base ouverte par Room en WAL, une écriture restée dans le
     * `-wal`, la corruption vue en cours d'usage. Ce que le `-wal` portait revient.
     */
    @Test
    fun aCopyTakenInUseBringsBackWhatOnlyTheWalHeld(): Unit = runBlocking {
        val target = context.getDatabasePath(IN_USE_DATABASE)
        target.parentFile?.mkdirs()
        fixtures.open("$FIXTURES/cells-page-half-zeroed.db").use { input -> target.outputStream().use(input::copyTo) }
        val opened = ClosableDatabases(context, IN_USE_DATABASE)
        try {
            opened.databases.boardDao.countBoards()
            opened.instances.single().openHelper.writableDatabase.apply {
                assertEquals("wal", query("PRAGMA journal_mode").use { it.moveToFirst(); it.getString(0) })
                execSQL("INSERT INTO profiles (id, name, start_board_id, is_exit_locked) VALUES (3, 'Zoé', NULL, 0)")
                execSQL("INSERT INTO boards (id, name, row_count, column_count, profile_id, core_board_id, screen_position) VALUES (2000, 'Zoé', 1, 2, 3, NULL, 0)")
                execSQL("INSERT INTO cells (board_id, grid_row, grid_column, label, linked_board_id) VALUES (2000, 0, 1, 'coucou', NULL)")
            }
            // Lit la feuille abîmée : la fabrique copie la base et son journal, puis la remplace.
            runCatching { opened.databases.boardDao.snapshot() }
        } finally {
            opened.close()
        }
        val copy = copies.copyDirectories().single()
        assertTrue("The WAL is part of the copy", File(copy, "$IN_USE_DATABASE-wal").length() > 0)

        val tables = readCorruptedCopy(copy, IN_USE_DATABASE, File(context.noBackupFilesDir, "recovery-test"))!!
        val recovered = recoverContent(tables, { false }, { false }, "Moi", "Grille retrouvée")!!.content

        assertEquals(listOf("Léo", "Inès", "Zoé"), recovered.profiles.map { it.name })
        assertEquals(listOf(false), recovered.profiles.filter { it.id == 3L }.map { it.isExitLocked })
        assertEquals(listOf("coucou"), recovered.cells.filter { it.boardId == 2000L }.map { it.label })
        assertEquals(182, recovered.cells.size)
    }

    /** Une copie d'avant les utilisateurs revient comme la migration 3 → 4 l'aurait faite. */
    @Test
    fun aCopyFromBeforeTheProfilesComesBackLikeTheMigration(): Unit = runBlocking {
        helper.createDatabase(IN_USE_DATABASE, 3).apply {
            execSQL(
                "INSERT INTO boards (id, name, row_count, column_count) VALUES " +
                    "(1, 'home', 2, 2), (2, 'food', 1, 1), (7, 'medium', 1, 1), (13, 'Mamie', 1, 2), (1000, 'animaux', 1, 1)"
            )
            execSQL(
                "INSERT INTO cells (id, board_id, grid_row, grid_column, label, linked_board_id) VALUES " +
                    "(1, 1, 0, 0, 'je', NULL), (2, 1, 0, 1, 'manger', 2), (3, 1, 1, 0, 'Mamie', 13), " +
                    "(4, 13, 0, 0, 'animaux', 1000), (5, 1000, 0, 0, 'chat', NULL), (6, 13, 0, 1, 'bisou', NULL)"
            )
            close()
        }
        val copy = File(copies.directory, "000001-old").apply { mkdirs() }
        context.getDatabasePath(IN_USE_DATABASE).copyTo(File(copy, PapotonsDatabase.NAME))

        val content = backup.analyzeCorruptedCopies().single().content

        assertEquals(listOf(1L to context.getString(R.string.profile_default_name)), content.profiles.map { it.id to it.name })
        assertEquals(listOf(1L, 2L, 7L, 13L, 1000L), content.boards.map { it.id })
        assertEquals(listOf(1L, 2L, 7L, null, null), content.boards.map { it.coreBoardId })
        assertEquals(listOf(0, null, null, null, null), content.boards.map { it.screenPosition })
        assertEquals(mapOf("manger" to 2L, "Mamie" to 13L, "animaux" to 1000L), content.cells.filter { it.linkedBoardId != null }.associate { it.label to it.linkedBoardId })
        assertEquals(6, content.cells.size)
    }

    private fun check(fixture: String, expected: Recovered?): Unit = runBlocking {
        placeCopy(fixture)

        val observed = backup.analyzeCorruptedCopies().singleOrNull()?.let { pending ->
            Recovered(pending.content.profiles.size, pending.boardCount, pending.cellCount, pending.recovery?.announcedCellCount)
        }

        assertEquals(fixture, expected, observed)
        // Ni modifiée ni supprimée par la lecture.
        assertTrue(File(copies.directory, "000001-test/${PapotonsDatabase.NAME}").readBytes().contentEquals(fixtureBytes(fixture)))
    }

    /** La base saine retouchée par [sql] (ouverte à part, en écriture), posée comme une copie. */
    private fun checkEdited(sql: String, expected: Recovered?) {
        val edited = File(context.cacheDir, "recovery-edited.db")
        edited.writeBytes(fixtureBytes("healthy"))
        SQLiteDatabase.openDatabase(edited.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
        val copy = File(copies.directory, "000001-test").apply { deleteRecursively(); mkdirs() }
        edited.copyTo(File(copy, PapotonsDatabase.NAME))
        edited.delete()
        val observed = runBlocking { backup.analyzeCorruptedCopies() }.singleOrNull()?.let { pending ->
            Recovered(pending.content.profiles.size, pending.boardCount, pending.cellCount, pending.recovery?.announcedCellCount)
        }
        assertEquals(sql, expected, observed)
    }

    private fun placeCopy(fixture: String, name: String = "000001-test") {
        val copy = File(copies.directory, name).apply { mkdirs() }
        File(copy, PapotonsDatabase.NAME).writeBytes(fixtureBytes(fixture))
    }

    private fun fixtureBytes(fixture: String): ByteArray = fixtures.open("$FIXTURES/$fixture.db").use { it.readBytes() }
}
