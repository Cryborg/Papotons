package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.WordCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le plan moteur des utilisateurs : chaque case de l'instantané doit exister à l'identique
 * (même place, même mot, même picto, même catégorie). On ajoute des cases, on n'en déplace
 * jamais (invariant 3). L'instantané est écrit par tools/arasaac/fetch_pictograms.py, en ajouts seulement.
 */
class CoreVocabularyLayoutSnapshotTest {
    private val seed = CoreVocabulary.build { resourceId -> "label$resourceId" }

    private val snapshot: List<List<String>> = checkNotNull(javaClass.getResource("/core_vocabulary_layout.txt"))
        .readText()
        .lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("|") }

    @Test
    fun `every cell of the vocabulary is in the snapshot, once`() {
        val locations = snapshot.map { SnapshotLine(it).location }
        assertEquals(locations.size, locations.toSet().size)
        for (cell in seed.cells) {
            assertTrue("${cell.label} at (${cell.row}, ${cell.column})", Triple(cell.boardId, cell.row, cell.column) in locations)
        }
    }

    @Test
    fun `every shipped cell keeps its place, word, pictogram and category`() {
        for (line in snapshot.map(::SnapshotLine)) {
            val (boardId, row, column, stringName, pictogramId) = line
            val category = line.category
            val linkedBoardId = line.linkedBoardId
            val cell = seed.cells.singleOrNull { it.boardId == boardId && it.row == row && it.column == column }
            checkNotNull(cell) { "$stringName disappeared from board $boardId at ($row, $column)" }
            val resourceId = R.string::class.java.getField(stringName).getInt(null)
            assertEquals("$stringName at ($row, $column)", "label$resourceId", cell.label)
            assertEquals("$stringName pictogram", pictogramId, cell.pictogramId)
            assertEquals("$stringName category", category, WordCategory.fromStoredName(cell.category))
            assertEquals("$stringName opens", linkedBoardId, cell.linkedBoardId)
        }
    }

    @Test
    fun `every shipped pictogram is still bundled`() {
        for (line in snapshot.map(::SnapshotLine)) {
            assertTrue("${line.stringName}: ${line.pictogramId}.png", bundledPictogram(line.pictogramId).isFile)
        }
    }

    private data class SnapshotLine(
        val boardId: Long,
        val row: Int,
        val column: Int,
        val stringName: String,
        val pictogramId: Long,
        val category: WordCategory?,
        val linkedBoardId: Long?,
    ) {
        val location get() = Triple(boardId, row, column)

        constructor(fields: List<String>) : this(
            boardId = fields[0].toLong(),
            row = fields[1].toInt(),
            column = fields[2].toInt(),
            stringName = fields[3],
            pictogramId = fields[4].toLong(),
            category = WordCategory.fromStoredName(fields[5]),
            linkedBoardId = fields[6].toLongOrNull(),
        )
    }
}
