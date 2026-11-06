package com.wobblebot.papotons.data.board

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Hérite de la vraie `seedIfEmpty`. N'applique ni index ni clés étrangères (voir BoardDaoTest). */
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

    override suspend fun countBoards(): Int = boards.value.size

    override suspend fun boardExists(boardId: Long): Boolean = boards.value.any { it.id == boardId }

    override suspend fun smallestBoardId(): Long? = boards.value.minOfOrNull { it.id }

    override suspend fun insertAll(boards: List<BoardEntity>, cells: List<CellEntity>) {
        insertGate?.await()
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
}
