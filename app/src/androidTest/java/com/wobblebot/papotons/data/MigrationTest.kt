package com.wobblebot.papotons.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
