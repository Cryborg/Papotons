package com.wobblebot.papotons.data

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "CorruptionFixtures"
private const val DATABASE_NAME = "corruption-fixture.db"
private const val FIXTURES = "corrupted-databases"
private const val HEALTHY = "healthy"

/** Ids écrits par `tools/corruption/make_corrupted_fixtures.py`. */
private val IDS = mapOf(
    "profiles" to listOf(1L, 2L),
    "boards" to listOf(1L, 2L, 3L, 4L, 1000L, 1001L, 1002L),
    "cells" to (1L..224L).toList(),
)
private val ALL_ROWS = Rows(profiles = 2, boards = 7, cells = 224)
private val NO_ROW = Rows(0, 0, 0)

/** Ce que dit `PRAGMA integrity_check` sur la base analysée. */
private enum class Integrity { OK, LISTS_PROBLEMS, ABORTS }

private data class Rows(val profiles: Int, val boards: Int, val cells: Int)

/**
 * @property boardsAtOpen ce que rend la première requête de l'appli (`countBoards`), `null` si elle lève.
 * @property copiedAtOpen une copie existe après cette première requête.
 * @property fullRead ce que lit l'export (`snapshot`), `null` s'il lève.
 * @property copied une copie existe après la lecture complète.
 * @property boardsAfterwards ce que rend `countBoards` ensuite, sur la même instance de Room :
 *   `null` quand la corruption a été vue en cours d'usage (`onCorruption` a fermé la connexion,
 *   Room ne la rouvre pas).
 * @property integrity ce que dit `integrity_check` de la base analysée : la copie, ou la base
 *   restée en place si rien n'a été copié.
 * @property intact lignes relues une à une par leur id dans la base analysée, identiques à la base saine.
 * @property altered lignes relues sans erreur mais différentes de la base saine.
 */
private data class Observation(
    val boardsAtOpen: Int?,
    val copiedAtOpen: Boolean,
    val fullRead: Rows?,
    val copied: Boolean,
    val boardsAfterwards: Int?,
    val integrity: Integrity,
    val intact: Rows,
    val altered: Int,
)

/**
 * TODO-8 : de vraies bases Papotons abîmées (`androidTest/assets/corrupted-databases/`), ouvertes
 * comme l'appli les ouvre ([buildDatabase]). Fixe, pour chaque panne, si la base est déclarée
 * corrompue (donc copiée), à quel moment, ce qu'en dit `integrity_check` et ce qu'on en relit.
 * Constats et conséquences : `docs/designs/TODO-8.md`.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseCorruptionFixturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
    private val copies = CorruptedDatabaseCopies(context.filesDir)
    private val analysis = File(context.cacheDir, "corruption-analysis")

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
        copies.directory.deleteRecursively()
        analysis.deleteRecursively()
    }

    @Test
    fun healthy() = check(
        HEALTHY,
        Observation(
            boardsAtOpen = 7, copiedAtOpen = false, fullRead = ALL_ROWS, copied = false, boardsAfterwards = 7,
            integrity = Integrity.OK, intact = ALL_ROWS, altered = 0,
        ),
    )

    /** Seuls les 16 octets de « SQLite format 3 » : rien ne se lit, copiée et vidée dès l'ouverture. */
    @Test
    fun headerZeroed() = check(
        "header-zeroed",
        Observation(
            boardsAtOpen = 0, copiedAtOpen = true, fullRead = NO_ROW, copied = true, boardsAfterwards = 0,
            integrity = Integrity.ABORTS, intact = NO_ROW, altered = 0,
        ),
    )

    /** La taille écrite dans l'en-tête dépasse le fichier : SQLite refuse tout, dès l'ouverture. */
    @Test
    fun fileCutInHalf() = check(
        "file-cut-in-half",
        Observation(
            boardsAtOpen = 0, copiedAtOpen = true, fullRead = NO_ROW, copied = true, boardsAfterwards = 0,
            integrity = Integrity.ABORTS, intact = NO_ROW, altered = 0,
        ),
    )

    /** `COUNT(*)` passe par un index des grilles : la première requête ne voit rien, l'export si. */
    @Test
    fun boardsPageRandom() = check(
        "boards-page-random",
        Observation(
            boardsAtOpen = 7, copiedAtOpen = false, fullRead = null, copied = true, boardsAfterwards = null,
            integrity = Integrity.ABORTS, intact = Rows(profiles = 2, boards = 0, cells = 224), altered = 0,
        ),
    )

    /** Un index seul : toutes les lignes sont là, mais le contrôle s'arrête net. */
    @Test
    fun cellsIndexPageRandom() = check(
        "cells-index-page-random",
        Observation(
            boardsAtOpen = 7, copiedAtOpen = false, fullRead = null, copied = true, boardsAfterwards = null,
            integrity = Integrity.ABORTS, intact = ALL_ROWS, altered = 0,
        ),
    )

    /** Les cases 83 à 125 perdues ; la 126 relue, son nom de photo coupé par les zéros. */
    @Test
    fun cellsPageHalfZeroed() = check(
        "cells-page-half-zeroed",
        Observation(
            boardsAtOpen = 7, copiedAtOpen = false, fullRead = null, copied = true, boardsAfterwards = null,
            integrity = Integrity.LISTS_PROBLEMS, intact = Rows(profiles = 2, boards = 7, cells = 180), altered = 1,
        ),
    )

    /** La case 181 perdue ; les 166 et 201 relues sans erreur, libellé, picto, catégorie ou photo brouillés. */
    @Test
    fun cellsPageRandomBytes() = check(
        "cells-page-random-bytes",
        Observation(
            boardsAtOpen = 7, copiedAtOpen = false, fullRead = null, copied = true, boardsAfterwards = null,
            integrity = Integrity.LISTS_PROBLEMS, intact = Rows(profiles = 2, boards = 7, cells = 221), altered = 2,
        ),
    )

    private fun check(fixture: String, expected: Observation) {
        copyFixture(fixture, context.getDatabasePath(DATABASE_NAME))

        val database = buildDatabase(context, DATABASE_NAME)
        val observed = try {
            val boardsAtOpen = countBoards(fixture, database)
            val copiedAtOpen = copies.copyDirectories().isNotEmpty()
            val fullRead = runCatching { runBlocking { database.boardDao().snapshot() } }
                .onFailure { Log.w(TAG, "$fixture: snapshot failed: $it") }.getOrNull()
                ?.let { Rows(it.profiles.size, it.boards.size, it.cells.size) }
            val copied = copies.copyDirectories().isNotEmpty()
            val boardsAfterwards = countBoards(fixture, database)
            database.close()

            val reference = rowsOf(File(analysis, "reference.db").also { copyFixture(HEALTHY, it) })
            val analysed = analysedCopy()
            val rows = rowsOf(analysed)
            Observation(
                boardsAtOpen, copiedAtOpen, fullRead, copied, boardsAfterwards,
                integrityOf(fixture, analysed),
                intact = Rows(
                    profiles = rows.intactCount("profiles", reference),
                    boards = rows.intactCount("boards", reference),
                    cells = rows.intactCount("cells", reference),
                ),
                altered = IDS.keys.sumOf { table -> rows.getValue(table).count { (id, row) -> reference.getValue(table)[id] != row } },
            )
        } finally {
            database.close()
        }
        assertEquals(fixture, expected, observed)
    }

    private fun countBoards(fixture: String, database: PapotonsDatabase): Int? =
        runCatching { runBlocking { database.boardDao().countBoards() } }
            .onFailure { Log.w(TAG, "$fixture: countBoards failed: $it") }.getOrNull()

    private fun copyFixture(fixture: String, target: File) {
        target.parentFile?.mkdirs()
        fixtures.open("$FIXTURES/$fixture.db").use { input -> target.outputStream().use(input::copyTo) }
    }

    /**
     * La copie de la fabrique, ou la base restée en place, recopiée à part avec son journal : ni
     * l'analyse ni SQLite ne touchent l'original.
     */
    private fun analysedCopy(): File {
        val source = copies.copyDirectories().singleOrNull()?.let { File(it, DATABASE_NAME) } ?: context.getDatabasePath(DATABASE_NAME)
        val target = File(analysis, "analysed")
        target.deleteRecursively()
        target.mkdirs()
        for (suffix in listOf("", "-wal", "-journal")) {
            val companion = File(source.path + suffix)
            if (companion.isFile) companion.copyTo(File(target, DATABASE_NAME + suffix))
        }
        return File(target, DATABASE_NAME)
    }

    private fun integrityOf(fixture: String, file: File): Integrity = try {
        openForAnalysis(file).use { database ->
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                val lines = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
                Log.i(TAG, "$fixture: integrity_check, ${lines.size} lines: ${lines.take(3)}")
                if (lines == listOf("ok")) Integrity.OK else Integrity.LISTS_PROBLEMS
            }
        }
    } catch (exception: SQLiteException) {
        Log.i(TAG, "$fixture: integrity_check aborted: $exception")
        Integrity.ABORTS
    }

    /** Par table, les lignes relues une à une par leur id (chaque colonne en texte) ; vide si la base ne s'ouvre pas. */
    private fun rowsOf(file: File): Map<String, Map<Long, List<String?>>> = try {
        openForAnalysis(file).use { database ->
            IDS.mapValues { (table, ids) -> ids.mapNotNull { id -> database.row(table, id)?.let { id to it } }.toMap() }
        }
    } catch (exception: SQLiteException) {
        Log.i(TAG, "${file.path} could not be opened: $exception")
        IDS.mapValues { emptyMap() }
    }

    /** L'id est l'alias du rowid : seul l'arbre de la table est lu, aucun index. */
    private fun SQLiteDatabase.row(table: String, id: Long): List<String?>? = try {
        rawQuery("SELECT * FROM $table WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) (0 until cursor.columnCount).map(cursor::getString) else null
        }
    } catch (exception: SQLiteException) {
        null
    }

    private fun Map<String, Map<Long, List<String?>>>.intactCount(table: String, reference: Map<String, Map<Long, List<String?>>>): Int =
        getValue(table).count { (id, row) -> reference.getValue(table)[id] == row }

    /** Lecture seule, et surtout pas le gestionnaire par défaut, qui supprimerait la base abîmée. */
    private fun openForAnalysis(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        DatabaseErrorHandler { },
    )
}
