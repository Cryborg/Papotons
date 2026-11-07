package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Un emplacement de la grille. */
@Immutable
data class GridSlot(val row: Int, val column: Int)

/**
 * Où tombent les cases d'une grille de [rowCount] × [columnCount] dans [size] (pixels, marge
 * [padding] tout autour, [spacing] entre deux cases) : la disposition de [CommunicationGrid], où
 * chaque case a la même part. Sert au glisser-déposer de l'édition.
 */
@Immutable
data class GridGeometry(val size: Size, val rowCount: Int, val columnCount: Int, val padding: Float, val spacing: Float) {
    val cellSize: Size = Size(
        width = ((size.width - 2 * padding - spacing * (columnCount - 1)) / columnCount).coerceAtLeast(0f),
        height = ((size.height - 2 * padding - spacing * (rowCount - 1)) / rowCount).coerceAtLeast(0f),
    )

    fun originOf(slot: GridSlot): Offset =
        Offset(padding + slot.column * (cellSize.width + spacing), padding + slot.row * (cellSize.height + spacing))

    /**
     * L'emplacement sous [position], `null` hors des cases (dans la marge ou au-delà). L'espace entre
     * deux cases va pour moitié à chacune : un doigt qui lâche entre deux cases vise la plus proche.
     */
    fun slotAt(position: Offset): GridSlot? {
        if (rowCount <= 0 || columnCount <= 0) return null
        val x = position.x - padding
        val y = position.y - padding
        val contentWidth = size.width - 2 * padding
        val contentHeight = size.height - 2 * padding
        if (x < 0 || y < 0 || x >= contentWidth || y >= contentHeight) return null
        val column = ((x + spacing / 2) / (cellSize.width + spacing)).toInt().coerceIn(0, columnCount - 1)
        val row = ((y + spacing / 2) / (cellSize.height + spacing)).toInt().coerceIn(0, rowCount - 1)
        return GridSlot(row, column)
    }
}
