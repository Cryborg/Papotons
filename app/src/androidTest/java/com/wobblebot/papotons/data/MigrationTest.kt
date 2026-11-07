package com.wobblebot.papotons.data

import androidx.room.testing.MigrationTestHelper
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.profile.ProfileEntity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DATABASE_NAME = "migration-test.db"

/** Invariant 4 : une base de la version 1 garde ses grilles en passant à la version 2. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PapotonsDatabase::class.java)

    @Test
    fun version1BoardsAndCellsSurviveTheMoveToVersion2() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL("INSERT INTO boards (id, name, row_count, column_count) VALUES (1, 'home', 2, 2), (2, 'folder', 1, 1)")
            execSQL(
                "INSERT INTO cells (id, board_id, grid_row, grid_column, label, linked_board_id) " +
                    "VALUES (1, 1, 0, 1, 'je', NULL), (2, 1, 1, 0, 'dossier', 2)"
            )
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = buildDatabase(context, DATABASE_NAME)
        try {
            val home = runBlocking { checkNotNull(database.boardDao().observeBoard(1).first()) }
            assertEquals("home", home.board.name)
            val word = home.cells.single { it.label == "je" }
            assertEquals(0 to 1, word.row to word.column)
            assertNull(word.pictogramId)
            assertNull(word.category)
            assertEquals(2L, home.cells.single { it.label == "dossier" }.linkedBoardId)
        } finally {
            database.close()
        }
    }

    @Test
    fun version2CellsGainEmptyPhotoAndSpokenText() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL("INSERT INTO boards (id, name, row_count, column_count) VALUES (1, 'home', 2, 2)")
            execSQL(
                "INSERT INTO cells (id, board_id, grid_row, grid_column, label, linked_board_id, pictogram_id, word_category) " +
                    "VALUES (1, 1, 1, 0, 'eau', NULL, 32464, 'NOUN')"
            )
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 3, true).close()

        val database = buildDatabase(InstrumentationRegistry.getInstrumentation().targetContext, DATABASE_NAME)
        try {
            val cell = runBlocking { checkNotNull(database.boardDao().observeBoard(1).first()) }.cells.single()
            assertEquals(Triple("eau", 1 to 0, 32464L), Triple(cell.label, cell.row to cell.column, cell.pictogramId))
            assertNull(cell.photoFile)
            assertNull(cell.spokenText)
        } finally {
            database.close()
        }
    }

    /**
     * Les grilles deviennent celles d'un premier utilisateur, sans qu'aucune case ni aucun lien ne
     * se perde. Chaîne de dossiers où le parent a un id plus grand que l'enfant ; un 13 visé par un
     * dossier (avant les tailles, un dossier de l'adulte) ne doit pas passer pour un accueil, un 7
     * libre si.
     */
    @Test
    fun version3BoardsBecomeTheFirstProfileWithoutLosingAnything() {
        helper.createDatabase(DATABASE_NAME, 3).apply {
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        helper.runMigrationsAndValidate(DATABASE_NAME, 4, true, migration3To4(context)).apply {
            query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            close()
        }

        val database = buildDatabase(context, DATABASE_NAME)
        try {
            val (profiles, boards, cells) = runBlocking { database.boardDao().snapshot() }
            // Protégé par la valeur par défaut de la colonne, comme un nouvel utilisateur.
            assertEquals(listOf(ProfileEntity(1, context.getString(R.string.profile_default_name), startBoardId = null, isExitLocked = true)), profiles)
            assertEquals(listOf(1L, 2L, 7L, 13L, 1000L), boards.map { it.id })
            assertTrue(boards.all { it.profileId == 1L })
            assertEquals(listOf(1L, 2L, 7L, null, null), boards.map { it.coreBoardId })
            assertEquals(6, cells.size)
            assertEquals(mapOf("manger" to 2L, "Mamie" to 13L, "animaux" to 1000L), cells.filter { it.linkedBoardId != null }.associate { it.label to it.linkedBoardId })
        } finally {
            database.close()
        }
    }
}
