package com.wobblebot.papotons.data.board

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BoardDao {
    @Query("SELECT COUNT(*) FROM boards")
    abstract suspend fun countBoards(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM boards WHERE id = :boardId)")
    abstract suspend fun boardExists(boardId: Long): Boolean

    @Query("SELECT MIN(id) FROM boards")
    abstract suspend fun smallestBoardId(): Long?

    // ABORT seulement, jamais REPLACE : REPLACE supprime puis réinsère la ligne, et la
    // clé étrangère en CASCADE effacerait au passage toutes les cases de la grille.
    // Les grilles d'abord : les clés étrangères des cases sont vérifiées à chaque ligne.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAll(boards: List<BoardEntity>, cells: List<CellEntity>)

    /** Insère [boards] et [cells] si aucune grille n'existe encore. Vrai si l'insertion a eu lieu. */
    @Transaction
    open suspend fun seedIfEmpty(boards: List<BoardEntity>, cells: List<CellEntity>): Boolean {
        if (countBoards() > 0) return false
        insertAll(boards, cells)
        return true
    }

    @Transaction
    @Query("SELECT * FROM boards WHERE id = :boardId")
    abstract fun observeBoard(boardId: Long): Flow<BoardWithCells?>
}
