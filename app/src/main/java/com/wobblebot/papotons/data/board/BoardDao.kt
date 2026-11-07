package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** La case à modifier a disparu entre l'ouverture de l'éditeur et l'enregistrement. */
class CellNotFoundException(cellId: Long) : SQLiteException("Cell $cellId not found")

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCell(cell: CellEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBoard(board: BoardEntity): Long

    /** Le contenu seulement, jamais la position (invariant 3). Rend le nombre de cases modifiées. */
    @Query("UPDATE cells SET label = :label, pictogram_id = :pictogramId, word_category = :category WHERE id = :cellId")
    abstract suspend fun updateCellContent(cellId: Long, label: String, pictogramId: Long?, category: String?): Int

    @Query("UPDATE boards SET name = :name WHERE id = :boardId")
    abstract suspend fun renameBoard(boardId: Long, name: String)

    @Query("SELECT * FROM cells WHERE id = :cellId")
    abstract suspend fun cell(cellId: Long): CellEntity?

    @Query("DELETE FROM cells WHERE id = :cellId")
    abstract suspend fun deleteCell(cellId: Long): Int

    @Query("SELECT linked_board_id FROM cells WHERE board_id = :boardId AND linked_board_id IS NOT NULL")
    abstract suspend fun linkedBoardIds(boardId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM cells WHERE linked_board_id = :boardId")
    abstract suspend fun countLinksTo(boardId: Long): Int

    /** Ses cases partent avec elle (CASCADE) ; échoue si une case la vise encore (RESTRICT). */
    @Query("DELETE FROM boards WHERE id = :boardId")
    abstract suspend fun deleteBoard(boardId: Long): Int

    /** Contenu de la case et, pour un dossier, nom de sa grille, ensemble ou pas du tout. */
    @Transaction
    open suspend fun updateCellContentAndFolderName(cellId: Long, linkedBoardId: Long?, label: String, pictogramId: Long?, category: String?) {
        // Supprimée entre-temps : un échec d'écriture comme un autre, que l'éditeur affiche.
        if (updateCellContent(cellId, label, pictogramId, category) != 1) throw CellNotFoundException(cellId)
        if (linkedBoardId != null) renameBoard(linkedBoardId, label)
    }

    /** Une nouvelle grille et la case-dossier qui l'ouvre, ensemble ou pas du tout. Rend l'id de la grille. */
    @Transaction
    open suspend fun insertFolder(board: BoardEntity, folderCell: CellEntity): Long {
        val boardId = insertBoard(board)
        insertCell(folderCell.copy(linkedBoardId = boardId))
        return boardId
    }

    /**
     * Supprime une case et, si c'est un dossier, les grilles de son sous-arbre que plus rien ne
     * vise. RESTRICT se vérifie ligne à ligne : une grille n'est supprimée que quand aucune case ne
     * la vise, et la cascade retire ses cases, donc ses liens vers les grilles suivantes. Une grille
     * encore visée ailleurs, ou dans [protectedBoardIds], reste ; un cycle ne boucle pas. Rend le
     * nombre de grilles supprimées.
     */
    @Transaction
    open suspend fun deleteCellAndOrphanBoards(cellId: Long, protectedBoardIds: Set<Long>): Int {
        val folderBoardId = cell(cellId)?.linkedBoardId
        deleteCell(cellId)
        if (folderBoardId == null) return 0
        val candidates = LinkedHashSet<Long>()
        val toVisit = ArrayDeque(listOf(folderBoardId))
        while (toVisit.isNotEmpty()) {
            val boardId = toVisit.removeFirst()
            if (candidates.add(boardId)) toVisit.addAll(linkedBoardIds(boardId))
        }
        candidates.removeAll(protectedBoardIds)
        var deletedCount = 0
        while (true) {
            val orphans = candidates.filter { countLinksTo(it) == 0 }
            if (orphans.isEmpty()) return deletedCount
            for (orphan in orphans) {
                deleteBoard(orphan)
                candidates.remove(orphan)
                deletedCount++
            }
        }
    }
}
