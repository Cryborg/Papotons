package com.wobblebot.papotons.data.board

import android.util.Log
import androidx.room.Embedded
import androidx.room.Relation
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.WordCategory
import kotlinx.collections.immutable.toImmutableList
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BoardWithCells"

/** Un seul `Log.w` par nom inconnu : chaque émission de la grille relit toutes les cases. */
private val reportedUnknownCategories = ConcurrentHashMap.newKeySet<String>()

data class BoardWithCells(
    @Embedded val board: BoardEntity,
    @Relation(parentColumn = "id", entityColumn = "board_id")
    val cells: List<CellEntity>,
)

fun BoardWithCells.toDomain(): Board = Board(
    id = board.id,
    name = board.name,
    rowCount = board.rowCount,
    columnCount = board.columnCount,
    cells = cells.map { cell ->
        Cell(
            id = cell.id,
            row = cell.row,
            column = cell.column,
            label = cell.label,
            linkedBoardId = cell.linkedBoardId,
            pictogramId = cell.pictogramId,
            category = categoryOf(cell),
        )
    }.toImmutableList(),
)

private fun categoryOf(cell: CellEntity): WordCategory? {
    val storedName = cell.category ?: return null
    val category = WordCategory.fromStoredName(storedName)
    if (category == null && reportedUnknownCategories.add(storedName)) {
        // Base écrite par une version plus récente : la case perd sa couleur, rien d'autre.
        Log.w(TAG, "Unknown word category $storedName")
    }
    return category
}
