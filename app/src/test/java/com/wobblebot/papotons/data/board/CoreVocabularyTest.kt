package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.domain.BoardSize
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

    @Test
    fun `every size has its home at its fixed id, with its dimensions`() {
        val expected = mapOf(BoardSize.LARGE_CELLS to (3 to 4), BoardSize.MEDIUM to (5 to 6), BoardSize.STANDARD to (7 to 8))
        for ((size, dimensions) in expected) {
            val sizeHome = checkNotNull(boardsById[size.homeBoardId]) { "$size home missing" }
            assertEquals("$size", dimensions, sizeHome.rowCount to sizeHome.columnCount)
        }
    }

    /**
     * Chaque taille est un jeu fermé : ses dossiers restent dans sa taille, à la taille de ses
     * dossiers, aucune case n'ouvre un accueil, et chaque grille insérée appartient à un seul jeu.
     */
    @Test
    fun `the sets of the sizes are disjoint, closed, and cover the whole core vocabulary`() {
        val homes = BoardSize.entries.map { it.homeBoardId }.toSet()
        assertTrue(seed.cells.none { it.linkedBoardId in homes })
        val seen = mutableSetOf<Long>()
        for (size in BoardSize.entries) {
            val reached = mutableSetOf(size.homeBoardId)
            val queue = ArrayDeque(listOf(size.homeBoardId))
            while (queue.isNotEmpty()) {
                val boardId = queue.removeFirst()
                for (cell in seed.cells.filter { it.boardId == boardId }) {
                    val linked = cell.linkedBoardId ?: continue
                    val folder = checkNotNull(boardsById[linked])
                    assertEquals("${cell.label} in $size", size.folderRowCount to size.folderColumnCount, folder.rowCount to folder.columnCount)
                    if (reached.add(linked)) queue.addLast(linked)
                }
            }
            assertTrue("$size shares boards with another size", seen.intersect(reached).isEmpty())
            seen += reached
        }
        assertEquals(boardsById.keys, seen)
    }

    @Test
    fun `the last row of the medium home stays free under its first four columns`() {
        val mediumHome = checkNotNull(boardsById[BoardSize.MEDIUM.homeBoardId])
        val lastRow = mediumHome.rowCount - 1
        for (column in 0 until 4) {
            assertNull("column $column", seed.cells.firstOrNull { it.boardId == mediumHome.id && it.row == lastRow && it.column == column })
        }
    }

    /** Les Réglages écrivent les dimensions de chaque taille : elles doivent suivre les grilles générées. */
    @Test
    fun `the dimensions shown in the settings match the home of each size`() {
        val strings = File("src/main/res/values/strings_settings.xml").readText()
        val names = mapOf(BoardSize.LARGE_CELLS to "board_size_large_cells", BoardSize.MEDIUM to "board_size_medium", BoardSize.STANDARD to "board_size_standard")
        for ((size, name) in names) {
            val shown = checkNotNull(Regex("""<string name="$name">[^<]*\((\d+) × (\d+)\)</string>""").find(strings)) { "$name has no dimensions" }
            val (rows, columns) = shown.destructured
            val sizeHome = checkNotNull(boardsById[size.homeBoardId])
            assertEquals(name, sizeHome.rowCount to sizeHome.columnCount, rows.toInt() to columns.toInt())
        }
    }

    @Test
    fun `the core vocabulary keeps its ids below the boards of the adult`() {
        assertTrue(seed.boards.all { it.id < FIRST_USER_BOARD_ID })
    }
}

/** Les tests JVM tournent depuis le dossier du module. */
fun bundledPictogram(pictogramId: Long) = File("src/main/assets/pictograms/$pictogramId.png")
