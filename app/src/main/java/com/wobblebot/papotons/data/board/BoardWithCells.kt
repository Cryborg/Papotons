package com.wobblebot.papotons.data.board

import androidx.room.Embedded
import androidx.room.Relation
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.Cell
import kotlinx.collections.immutable.toImmutableList

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
        )
    }.toImmutableList(),
)
