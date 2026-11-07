package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.data.backup.MAX_BOARDS
import com.wobblebot.papotons.data.backup.MAX_CELLS
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileVocabularyTest {
    private val seed = CoreVocabulary.build { resourceId -> "label$resourceId" }

    @Test
    fun `boards get contiguous ids from the given first id and keep their origin`() {
        val copy = seed.forProfile(profileId = 3, firstBoardId = 2_350)

        assertEquals((2_350L until 2_350L + seed.boards.size).toList(), copy.boards.map { it.id })
        assertEquals(seed.boards.map { it.id }, copy.boards.map { it.coreBoardId })
        assertTrue(copy.boards.all { it.profileId == 3L })
        assertEquals(seed.boards.map { Triple(it.name, it.rowCount, it.columnCount) }, copy.boards.map { Triple(it.name, it.rowCount, it.columnCount) })
    }

    @Test
    fun `folders open the copy of the board they opened`() {
        val copy = seed.forProfile(profileId = 2, firstBoardId = 1_000)
        val copiedIds = copy.boards.map { it.id }.toSet()
        val coreIdOf = copy.boards.associate { it.id to it.coreBoardId }

        val folders = copy.cells.filter { it.linkedBoardId != null }
        assertEquals(seed.cells.count { it.linkedBoardId != null }, folders.size)
        for ((original, copied) in seed.cells.zip(copy.cells)) {
            assertEquals(original.boardId, coreIdOf[copied.boardId])
            original.linkedBoardId?.let { linked ->
                assertTrue(copied.linkedBoardId in copiedIds)
                assertEquals(linked, coreIdOf[copied.linkedBoardId])
            }
        }
    }

    /** Ce que l'appli crée, la sauvegarde doit l'accepter : le plafond d'utilisateurs tient dans ses bornes. */
    @Test
    fun `the largest number of profiles fits in a backup`() {
        assertTrue(MAX_PROFILES * seed.boards.size <= MAX_BOARDS)
        assertTrue(MAX_PROFILES * seed.cells.size <= MAX_CELLS)
    }

    @Test
    fun `cells keep their place and content and get fresh ids`() {
        val copy = seed.forProfile(profileId = 2, firstBoardId = 1_000)

        assertEquals(
            seed.cells.map { listOf(it.row, it.column, it.label, it.pictogramId, it.category) },
            copy.cells.map { listOf(it.row, it.column, it.label, it.pictogramId, it.category) },
        )
        assertTrue(copy.cells.all { it.id == 0L })
    }
}
