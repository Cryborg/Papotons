package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Hérite des corps des transactions (`seedIfEmpty`, `insertFolder`, `deleteCellAndOrphanBoards`),
 * sans leur annulation en cas d'échec.
 * Imite l'index unique des positions, la CASCADE et le RESTRICT des cases uniquement sur les
 * nouvelles écritures ; la vraie Room se teste dans BoardDaoTest.
 */
class FakeBoardDao(
    boards: List<BoardEntity> = emptyList(),
    cells: List<CellEntity> = emptyList(),
) : BoardDao() {
    val boards = MutableStateFlow(boards)
    val cells = MutableStateFlow(cells)
    var insertCount = 0
        private set

    /** Retient l'insertion tant qu'il n'est pas complété (premier lancement lent). */
    var insertGate: CompletableDeferred<Unit>? = null

    /** Retient les lectures tant qu'il vaut vrai : Room lit sur son propre exécuteur, en différé. */
    val areReadsHeld = MutableStateFlow(false)

    /** Appelé à chaque `countBoards` : la vraie première requête ouvre la base (et copie une base corrompue). */
    var onCountBoards: () -> Unit = {}

    override suspend fun countBoards(): Int {
        onCountBoards()
        return boards.value.size
    }

    override suspend fun boardExists(boardId: Long): Boolean = boards.value.any { it.id == boardId }

    override suspend fun smallestBoardId(): Long? = boards.value.minOfOrNull { it.id }

    /** Levée par l'insertion (disque plein, contrainte violée). */
    var insertFailure: RuntimeException? = null

    override suspend fun insertAll(boards: List<BoardEntity>, cells: List<CellEntity>) {
        insertGate?.await()
        insertFailure?.let { throw it }
        insertCount++
        val firstCellId = this.cells.value.size + 1L
        this.boards.value += boards
        this.cells.value += cells.mapIndexed { index, cell -> cell.copy(id = firstCellId + index) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeBoard(boardId: Long): Flow<BoardWithCells?> = areReadsHeld.flatMapLatest { isHeld ->
        if (isHeld) {
            emptyFlow()
        } else {
            combine(boards, cells) { allBoards, allCells ->
                allBoards.firstOrNull { it.id == boardId }?.let { board ->
                    BoardWithCells(board, allCells.filter { it.boardId == boardId })
                }
            }
        }
    }

    override suspend fun insertCell(cell: CellEntity): Long {
        insertGate?.await()
        if (cells.value.any { it.boardId == cell.boardId && it.row == cell.row && it.column == cell.column }) {
            throw SQLiteConstraintException("UNIQUE constraint failed")
        }
        val id = (cells.value.maxOfOrNull { it.id } ?: 0) + 1
        cells.value += cell.copy(id = id)
        return id
    }

    override suspend fun insertBoard(board: BoardEntity): Long {
        val id = (boards.value.maxOfOrNull { it.id } ?: 0) + 1
        boards.value += board.copy(id = id)
        return id
    }

    override suspend fun updateCellContent(cellId: Long, label: String, pictogramId: Long?, category: String?): Int {
        val before = cells.value
        cells.value = before.map { if (it.id == cellId) it.copy(label = label, pictogramId = pictogramId, category = category) else it }
        return before.count { it.id == cellId }
    }

    override suspend fun renameBoard(boardId: Long, name: String) {
        boards.value = boards.value.map { if (it.id == boardId) it.copy(name = name) else it }
    }

    override suspend fun cell(cellId: Long): CellEntity? = cells.value.firstOrNull { it.id == cellId }

    override suspend fun deleteCell(cellId: Long): Int {
        val before = cells.value.size
        cells.value = cells.value.filterNot { it.id == cellId }
        return before - cells.value.size
    }

    override suspend fun linkedBoardIds(boardId: Long): List<Long> =
        cells.value.filter { it.boardId == boardId }.mapNotNull { it.linkedBoardId }

    override suspend fun countLinksTo(boardId: Long): Int = cells.value.count { it.linkedBoardId == boardId }

    override suspend fun deleteBoard(boardId: Long): Int {
        if (countLinksTo(boardId) != 0) throw SQLiteConstraintException("FOREIGN KEY constraint failed (RESTRICT)")
        val before = boards.value.size
        boards.value = boards.value.filterNot { it.id == boardId }
        cells.value = cells.value.filterNot { it.boardId == boardId }
        return before - boards.value.size
    }
}
