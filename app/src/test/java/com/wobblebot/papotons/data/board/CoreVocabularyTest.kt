package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.domain.WordCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Une faute dans le vocabulaire casserait le premier lancement ou laisserait une case sans image. */
class CoreVocabularyTest {
    private val seed = CoreVocabulary.build { resourceId -> "label$resourceId" }
    private val boardsById = seed.boards.associateBy { it.id }
    private val home = checkNotNull(boardsById[HOME_BOARD_ID])

    @Test
    fun `board ids are unique and the home board exists`() {
        assertEquals(seed.boards.size, boardsById.size)
        assertTrue(HOME_BOARD_ID in boardsById)
    }

    @Test
    fun `every cell lies inside its board`() {
        for (cell in seed.cells) {
            val board = checkNotNull(boardsById[cell.boardId]) { "${cell.label} in unknown board ${cell.boardId}" }
            assertTrue("${cell.label} at (${cell.row}, ${cell.column})", cell.row in 0 until board.rowCount)
            assertTrue("${cell.label} at (${cell.row}, ${cell.column})", cell.column in 0 until board.columnCount)
        }
    }

    @Test
    fun `no two cells share a slot`() {
        val slots = seed.cells.map { Triple(it.boardId, it.row, it.column) }
        assertEquals(slots.size, slots.toSet().size)
    }

    @Test
    fun `every folder opens another existing board`() {
        val folders = seed.cells.filter { it.linkedBoardId != null }
        assertTrue(folders.isNotEmpty())
        for (folder in folders) {
            assertTrue(folder.label, folder.linkedBoardId in boardsById)
            assertTrue(folder.label, folder.linkedBoardId != folder.boardId)
        }
    }

    @Test
    fun `every cell has a known category`() {
        for (cell in seed.cells) {
            assertTrue(cell.label, WordCategory.fromStoredName(cell.category) != null)
        }
    }

    @Test
    fun `every pictogram is bundled`() {
        for (cell in seed.cells) {
            val pictogramId = checkNotNull(cell.pictogramId) { "${cell.label} has no pictogram" }
            assertTrue("${cell.label}: $pictogramId.png", bundledPictogram(pictogramId).isFile)
        }
    }

    /**
     * Les mots ajoutés plus tard (édition, MVP-3) se rangent en bas de leur colonne de fonction sans
     * rien déplacer : la dernière ligne de l'accueil est réservée à ça (docs/designs/MVP-2.md).
     */
    @Test
    fun `the last row of every home column stays free for later vocabulary`() {
        val lastRow = home.rowCount - 1
        for (column in 0 until home.columnCount) {
            assertNull(
                "column $column",
                seed.cells.firstOrNull { it.boardId == HOME_BOARD_ID && it.row == lastRow && it.column == column },
            )
        }
    }
}

/** Les tests JVM tournent depuis le dossier du module. */
fun bundledPictogram(pictogramId: Long) = File("src/main/assets/pictograms/$pictogramId.png")
