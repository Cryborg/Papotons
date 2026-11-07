package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Ce qu'une suppression a emporté : les photos de ses cases n'ont plus de raison d'exister. */
data class DeletedCells(val boardCount: Int, val photoFiles: List<String>)

/** La case à modifier a disparu entre l'ouverture de l'éditeur et l'enregistrement. */
class CellNotFoundException(cellId: Long) : SQLiteException("Cell $cellId not found")

/**
 * La photo de la case a changé depuis l'ouverture de l'éditeur (case pas encore relue après un
 * enregistrement) : l'écrire remettrait une photo déjà effacée et ferait effacer la nouvelle.
 */
class CellChangedException(cellId: Long) : SQLiteException("Cell $cellId changed since it was read")

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
    @Query(
        "UPDATE cells SET label = :label, pictogram_id = :pictogramId, word_category = :category, " +
            "photo_file = :photoFile, spoken_text = :spokenText WHERE id = :cellId"
    )
    abstract suspend fun updateCellContent(cellId: Long, label: String, pictogramId: Long?, category: String?, photoFile: String?, spokenText: String?): Int

    @Query("UPDATE boards SET name = :name WHERE id = :boardId")
    abstract suspend fun renameBoard(boardId: Long, name: String)

    @Query("SELECT * FROM cells WHERE id = :cellId")
    abstract suspend fun cell(cellId: Long): CellEntity?

    @Query("DELETE FROM cells WHERE id = :cellId")
    abstract suspend fun deleteCell(cellId: Long): Int

    @Query("SELECT linked_board_id FROM cells WHERE board_id = :boardId AND linked_board_id IS NOT NULL")
    abstract suspend fun linkedBoardIds(boardId: Long): List<Long>

    @Query("SELECT photo_file FROM cells WHERE board_id = :boardId AND photo_file IS NOT NULL")
    abstract suspend fun photoFilesOf(boardId: Long): List<String>

    @Query("SELECT COUNT(*) FROM cells WHERE linked_board_id = :boardId")
    abstract suspend fun countLinksTo(boardId: Long): Int

    /** Ses cases partent avec elle (CASCADE) ; échoue si une case la vise encore (RESTRICT). */
    @Query("DELETE FROM boards WHERE id = :boardId")
    abstract suspend fun deleteBoard(boardId: Long): Int

    /**
     * Contenu de la case et, pour un dossier, nom de sa grille, ensemble ou pas du tout. Rend la photo
     * d'avant. Refusé si la case ne porte plus [expectedPhoto], la photo lue par l'éditeur.
     */
    @Transaction
    open suspend fun updateCellContentAndFolderName(
        cellId: Long,
        expectedPhoto: String?,
        linkedBoardId: Long?,
        label: String,
        pictogramId: Long?,
        category: String?,
        photoFile: String?,
        spokenText: String?,
    ): String? {
        val current = cell(cellId)
        val previousPhoto = current?.photoFile
        if (current != null && previousPhoto != expectedPhoto) throw CellChangedException(cellId)
        // Supprimée entre-temps : un échec d'écriture comme un autre, que l'éditeur affiche.
        val isUpdated = updateCellContent(cellId, label, pictogramId, category, photoFile, spokenText) == 1
        if (!isUpdated) throw CellNotFoundException(cellId)
        if (linkedBoardId != null) renameBoard(linkedBoardId, label)
        return previousPhoto
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
     * encore visée ailleurs, ou dans [protectedBoardIds], reste ; un cycle ne boucle pas. Rend les
     * grilles supprimées et les photos des cases parties avec elles, à effacer ensuite.
     */
    @Transaction
    open suspend fun deleteCellAndOrphanBoards(cellId: Long, protectedBoardIds: Set<Long>): DeletedCells {
        val deletedCell = cell(cellId)
        val photoFiles = listOfNotNull(deletedCell?.photoFile).toMutableList()
        val folderBoardId = deletedCell?.linkedBoardId
        deleteCell(cellId)
        if (folderBoardId == null) return DeletedCells(boardCount = 0, photoFiles = photoFiles)
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
            if (orphans.isEmpty()) return DeletedCells(boardCount = deletedCount, photoFiles = photoFiles)
            for (orphan in orphans) {
                photoFiles += photoFilesOf(orphan)
                deleteBoard(orphan)
                candidates.remove(orphan)
                deletedCount++
            }
        }
    }
}
