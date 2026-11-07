package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.wobblebot.papotons.data.backup.MAX_BOARDS
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import com.wobblebot.papotons.data.profile.ProfileEntity
import kotlinx.coroutines.flow.Flow

private const val TAG = "BoardDao"

/** Ce qu'une suppression a emporté : les photos de ses cases n'ont plus de raison d'exister. */
data class DeletedCells(val boardCount: Int, val photoFiles: List<String>)

/** La case à modifier a disparu entre l'ouverture de l'éditeur et l'enregistrement. */
class CellNotFoundException(cellId: Long) : SQLiteException("Cell $cellId not found")

/**
 * La photo de la case a changé depuis l'ouverture de l'éditeur (case pas encore relue après un
 * enregistrement) : l'écrire remettrait une photo déjà effacée et ferait effacer la nouvelle.
 */
class CellChangedException(cellId: Long) : SQLiteException("Cell $cellId changed since it was read")

/**
 * Les grilles créées par l'adulte, et les copies du vocabulaire de base des utilisateurs ajoutés,
 * commencent ici : 1 à 999 restent aux ids de `CoreVocabulary` (la copie de la graine ou de la
 * migration), qui peut encore ajouter des grilles à id fixe après les premières installations.
 */
const val FIRST_USER_BOARD_ID = 1000L

/** Supprimer le dernier utilisateur qui a des grilles viderait la base : refusé. */
class LastProfileException(profileId: Long) : SQLiteException("Profile $profileId is the last one with boards")

/** L'utilisateur visé a disparu entre la lecture et l'écriture. */
class ProfileNotFoundException(profileId: Long) : SQLiteException("Profile $profileId not found")

/** Déjà [MAX_PROFILES] utilisateurs, ou trop de grilles : la sauvegarde refuserait le suivant. */
class TooManyProfilesException : SQLiteException("Too many profiles")

/** La grille parente d'un nouveau dossier a disparu : on ne sait pas à quel utilisateur le rattacher. */
class BoardNotFoundException(boardId: Long) : SQLiteException("Board $boardId not found")

/** Toute la base, lue d'un coup : une sauvegarde cohérente. */
data class DatabaseSnapshot(val profiles: List<ProfileEntity>, val boards: List<BoardEntity>, val cells: List<CellEntity>)

/** Les pictos (embarqués ou téléchargés) et les photos visés par les cases, lus d'un coup avec le nombre de grilles. */
data class ReferencedImages(val boardCount: Int, val pictogramIds: Set<Long>, val photoFiles: Set<String>)

@Dao
abstract class BoardDao {
    @Query("SELECT COUNT(*) FROM boards")
    abstract suspend fun countBoards(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM boards WHERE id = :boardId)")
    abstract suspend fun boardExists(boardId: Long): Boolean

    @Query("SELECT MAX(id) FROM boards")
    abstract suspend fun largestBoardId(): Long?

    // ABORT seulement, jamais REPLACE : REPLACE supprime puis réinsère la ligne, et la
    // clé étrangère en CASCADE effacerait au passage toutes les cases de la grille.
    // Les grilles d'abord : les clés étrangères des cases sont vérifiées à chaque ligne.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAll(boards: List<BoardEntity>, cells: List<CellEntity>)

    /**
     * Insère [seed] (ids fixes de `CoreVocabulary`) si aucune grille n'existe encore, chez le plus
     * petit utilisateur, créé sous le nom [defaultProfileName] s'il n'y en a aucun. Vrai si
     * l'insertion a eu lieu.
     */
    @Transaction
    open suspend fun seedIfEmpty(seed: VocabularySeed, defaultProfileName: String): Boolean {
        if (countBoards() > 0) return false
        val profileId = smallestProfileId() ?: insertProfile(ProfileEntity(FIRST_PROFILE_ID, defaultProfileName, startBoardId = null))
        insertAll(seed.boards.map { it.copy(profileId = profileId, coreBoardId = it.id) }, seed.cells)
        // Les autres, restés sans grille (base abîmée), ne s'ouvriraient jamais et rendraient l'export impossible.
        val removed = deleteProfilesWithoutBoards()
        if (removed > 0) Log.w(TAG, "$removed profiles without boards removed while reseeding")
        return true
    }

    @Query("DELETE FROM profiles WHERE id NOT IN (SELECT profile_id FROM boards)")
    abstract suspend fun deleteProfilesWithoutBoards(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProfile(profile: ProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProfiles(profiles: List<ProfileEntity>)

    @Query("SELECT * FROM profiles ORDER BY id")
    abstract suspend fun allProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles ORDER BY id")
    abstract fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :profileId")
    abstract suspend fun profile(profileId: Long): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    abstract suspend fun countProfiles(): Int

    @Query("SELECT MIN(id) FROM profiles")
    abstract suspend fun smallestProfileId(): Long?

    @Query("UPDATE profiles SET name = :name WHERE id = :profileId")
    abstract suspend fun renameProfile(profileId: Long, name: String): Int

    @Query("UPDATE profiles SET start_board_id = :boardId WHERE id = :profileId")
    abstract suspend fun setStartBoard(profileId: Long, boardId: Long): Int

    @Query("DELETE FROM profiles WHERE id = :profileId")
    abstract suspend fun deleteProfileRow(profileId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM boards WHERE profile_id = :profileId)")
    abstract suspend fun hasBoards(profileId: Long): Boolean

    /** Le plus petit utilisateur qui a des grilles. */
    @Query("SELECT MIN(profile_id) FROM boards")
    abstract suspend fun smallestProfileWithBoards(): Long?

    /** Le plus petit utilisateur, autre que [profileId], qui a des grilles. */
    @Query("SELECT MIN(profile_id) FROM boards WHERE profile_id != :profileId")
    abstract suspend fun smallestOtherProfileWithBoards(profileId: Long): Long?

    @Query("SELECT profile_id FROM boards WHERE id = :boardId")
    abstract suspend fun profileOf(boardId: Long): Long?

    @Query("SELECT MIN(id) FROM boards WHERE profile_id = :profileId")
    abstract suspend fun smallestBoardIdOf(profileId: Long): Long?

    /** Un écran de départ de l'utilisateur : une grille à lui qu'aucune case n'ouvre. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM boards WHERE id = :boardId AND profile_id = :profileId " +
            "AND id NOT IN (SELECT linked_board_id FROM cells WHERE linked_board_id IS NOT NULL))"
    )
    abstract suspend fun isStartBoardOf(boardId: Long, profileId: Long): Boolean

    /** La copie de la grille [coreBoardId] du vocabulaire de base chez l'utilisateur, si aucune case ne l'ouvre. */
    @Query(
        "SELECT id FROM boards WHERE profile_id = :profileId AND core_board_id = :coreBoardId " +
            "AND id NOT IN (SELECT linked_board_id FROM cells WHERE linked_board_id IS NOT NULL) ORDER BY id LIMIT 1"
    )
    abstract suspend fun startBoardCopyOf(profileId: Long, coreBoardId: Long): Long?

    /** Les grilles, de tous les utilisateurs, copies de [coreBoardIds]. */
    @Query("SELECT id FROM boards WHERE core_board_id IN (:coreBoardIds)")
    abstract suspend fun copiesOf(coreBoardIds: Set<Long>): List<Long>

    /** `null` pour une grille de l'adulte ou absente. */
    @Query("SELECT core_board_id FROM boards WHERE id = :boardId")
    abstract fun observeCoreBoardId(boardId: Long): Flow<Long?>

    /**
     * Un nouvel utilisateur et sa copie du vocabulaire de base ([seed], ids de `CoreVocabulary`),
     * ensemble ou pas du tout ; jamais au-delà de [MAX_PROFILES]. Rend son id.
     */
    @Transaction
    open suspend fun insertProfileWithCoreVocabulary(name: String, seed: VocabularySeed): Long {
        // Au-delà, la sauvegarde refuserait la base : l'export doit rester possible (invariant 4).
        if (countProfiles() >= MAX_PROFILES || countBoards() + seed.boards.size > MAX_BOARDS) throw TooManyProfilesException()
        val profileId = insertProfile(ProfileEntity(name = name, startBoardId = null))
        val firstBoardId = maxOf((largestBoardId() ?: 0L) + 1, FIRST_USER_BOARD_ID)
        val copy = seed.forProfile(profileId, firstBoardId)
        insertAll(copy.boards, copy.cells)
        return profileId
    }

    @Query("SELECT photo_file FROM cells WHERE photo_file IS NOT NULL AND board_id IN (SELECT id FROM boards WHERE profile_id = :profileId)")
    abstract suspend fun photoFilesOfProfile(profileId: Long): List<String>

    @Query("DELETE FROM cells WHERE board_id IN (SELECT id FROM boards WHERE profile_id = :profileId)")
    abstract suspend fun deleteCellsOfProfile(profileId: Long): Int

    @Query("DELETE FROM boards WHERE profile_id = :profileId")
    abstract suspend fun deleteBoardsOfProfile(profileId: Long): Int

    /**
     * Un utilisateur et toutes ses grilles, ensemble ou pas du tout ; jamais le dernier qui a des
     * grilles (la base se viderait, et passerait pour perdue au lancement suivant). Ses cases
     * d'abord : un dossier vise sa grille en RESTRICT, et aucun dossier ne vise la grille d'un autre
     * utilisateur. Rend les grilles supprimées et les photos de ses cases, à effacer ensuite.
     */
    @Transaction
    open suspend fun deleteProfile(profileId: Long): DeletedCells {
        if (smallestOtherProfileWithBoards(profileId) == null) throw LastProfileException(profileId)
        val photoFiles = photoFilesOfProfile(profileId)
        deleteCellsOfProfile(profileId)
        val boardCount = deleteBoardsOfProfile(profileId)
        deleteProfileRow(profileId)
        return DeletedCells(boardCount = boardCount, photoFiles = photoFiles)
    }

    @Query("SELECT * FROM boards ORDER BY id")
    abstract suspend fun allBoards(): List<BoardEntity>

    @Query("SELECT * FROM cells ORDER BY board_id, grid_row, grid_column")
    abstract suspend fun allCells(): List<CellEntity>

    /** Tous les utilisateurs, toutes les grilles et toutes les cases, lus ensemble. */
    @Transaction
    open suspend fun snapshot(): DatabaseSnapshot = DatabaseSnapshot(allProfiles(), allBoards(), allCells())

    @Query("DELETE FROM cells")
    abstract suspend fun deleteAllCells()

    @Query("DELETE FROM boards")
    abstract suspend fun deleteAllBoards()

    @Query("DELETE FROM profiles")
    abstract suspend fun deleteAllProfiles()

    /**
     * Remplace toute la base, ensemble ou pas du tout : une restauration qui échoue laisse les grilles
     * d'avant. Les cases d'abord (un dossier vise sa grille en RESTRICT), puis les grilles (elles
     * visent leur utilisateur en RESTRICT) ; à l'insertion, l'ordre inverse.
     */
    @Transaction
    open suspend fun replaceAll(profiles: List<ProfileEntity>, boards: List<BoardEntity>, cells: List<CellEntity>) {
        deleteAllCells()
        deleteAllBoards()
        deleteAllProfiles()
        insertProfiles(profiles)
        insertAll(boards, cells)
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

    @Query("SELECT DISTINCT pictogram_id FROM cells WHERE pictogram_id IS NOT NULL")
    abstract suspend fun referencedPictogramIds(): List<Long>

    @Query("SELECT DISTINCT photo_file FROM cells WHERE photo_file IS NOT NULL")
    abstract suspend fun referencedPhotoFiles(): List<String>

    /** Une seule lecture cohérente : une base vidée entre deux requêtes ne passe pas pour une base sans image. */
    @Transaction
    open suspend fun referencedImages(): ReferencedImages =
        ReferencedImages(countBoards(), referencedPictogramIds().toSet(), referencedPhotoFiles().toSet())

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

    /**
     * Une nouvelle grille et la case-dossier qui l'ouvre, ensemble ou pas du tout. Rend l'id de la
     * grille, jamais sous [FIRST_USER_BOARD_ID]. La grille appartient à l'utilisateur de la grille qui
     * porte la case, lu ici, jamais à l'utilisateur ouvert : un dossier ne relie jamais deux
     * utilisateurs (la sauvegarde le refuserait), même écrit pendant un changement d'utilisateur.
     */
    @Transaction
    open suspend fun insertFolder(board: BoardEntity, folderCell: CellEntity): Long {
        val profileId = profileOf(folderCell.boardId) ?: throw BoardNotFoundException(folderCell.boardId)
        val newId = maxOf((largestBoardId() ?: 0L) + 1, FIRST_USER_BOARD_ID)
        val boardId = insertBoard(board.copy(id = newId, profileId = profileId, coreBoardId = null))
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
