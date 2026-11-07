package com.wobblebot.papotons.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridGeometryTest {
    // 2 lignes × 3 colonnes, marge 10, espacement 6 : cases de 100 × 50 (3 × 100 + 2 × 6 + 2 × 10 = 332).
    private val geometry = GridGeometry(Size(332f, 126f), rowCount = 2, columnCount = 3, padding = 10f, spacing = 6f)

    @Test
    fun `cells share the space left by the padding and the spacing`() {
        assertEquals(Size(100f, 50f), geometry.cellSize)
        assertEquals(Offset(10f + 2 * 106f, 10f + 56f), geometry.originOf(GridSlot(1, 2)))
    }

    @Test
    fun `a point inside a cell is that cell, rows and columns not swapped`() {
        assertEquals(GridSlot(0, 0), geometry.slotAt(Offset(11f, 11f)))
        assertEquals(GridSlot(1, 2), geometry.slotAt(Offset(320f, 110f)))
        assertEquals(GridSlot(0, 2), geometry.slotAt(Offset(320f, 20f)))
    }

    /** Entre deux cases : la plus proche, jamais la case d'après. */
    @Test
    fun `the gap between two cells goes to the nearest`() {
        // Gap des colonnes 0 et 1 : de 110 à 116.
        assertEquals(GridSlot(0, 0), geometry.slotAt(Offset(112f, 20f)))
        assertEquals(GridSlot(0, 1), geometry.slotAt(Offset(114f, 20f)))
    }

    @Test
    fun `the gap between two rows goes to the nearest`() {
        // Gap des lignes 0 et 1 : de 60 à 66.
        assertEquals(GridSlot(0, 0), geometry.slotAt(Offset(20f, 62f)))
        assertEquals(GridSlot(1, 0), geometry.slotAt(Offset(20f, 64f)))
    }

    @Test
    fun `the padding and beyond are no slot`() {
        assertNull(geometry.slotAt(Offset(322f, 20f)))
        assertNull(geometry.slotAt(Offset(20f, 5f)))
        assertNull(geometry.slotAt(Offset(5f, 20f)))
        assertNull(geometry.slotAt(Offset(20f, 120f)))
        assertNull(geometry.slotAt(Offset(400f, 20f)))
        assertNull(geometry.slotAt(Offset(-3f, -3f)))
    }
}
