package com.wobblebot.papotons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridLayoutTest {
    private fun cell(id: Long, row: Int, column: Int) =
        Cell(id = id, row = row, column = column, label = "cell$id", linkedBoardId = null)

    @Test
    fun `each cell lands at its stored position whatever the list order`() {
        // Grille 2 × 3 non carrée et liste dans le désordre : une inversion ligne/colonne
        // ou un rangement par rang dans la liste déplace au moins une case.
        val cells = listOf(cell(1, row = 1, column = 2), cell(2, row = 0, column = 1), cell(3, row = 1, column = 0))

        val grid = layoutGrid(rowCount = 2, columnCount = 3, cells = cells)

        assertEquals(2, grid.size)
        assertEquals(listOf(3, 3), grid.map { it.size })
        assertEquals(1L, grid[1][2]?.id)
        assertEquals(2L, grid[0][1]?.id)
        assertEquals(3L, grid[1][0]?.id)
    }

    @Test
    fun `free slots stay empty`() {
        val grid = layoutGrid(rowCount = 2, columnCount = 3, cells = listOf(cell(1, row = 0, column = 1)))

        assertNull(grid[0][0])
        assertNull(grid[0][2])
        assertNull(grid[1][1])
    }

    @Test
    fun `a cell outside the grid is left out without breaking the others`() {
        val cells = listOf(cell(1, row = 2, column = 0), cell(2, row = 0, column = 3), cell(3, row = 1, column = 1))

        val grid = layoutGrid(rowCount = 2, columnCount = 3, cells = cells)

        assertEquals(listOf(3L), grid.flatten().mapNotNull { it?.id })
    }
}
