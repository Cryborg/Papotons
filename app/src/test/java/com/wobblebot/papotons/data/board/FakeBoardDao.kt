package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteConstraintException
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.NO_PROFILE
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.data.profile.legacyCoreBoardId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Hérite des corps des transactions (`seedIfEmpty`, `insertFolder`, `deleteCellAndOrphanBoards`,
 * `insertProfileWithCoreVocabulary`, `deleteProfile`), sans leur annulation en cas d'échec.
 * Imite l'index unique des positions, la CASCADE et le RESTRICT des cases, et la clé étrangère des
 * grilles vers leur utilisateur, uniquement sur les nouvelles écritures ; la vraie Room se teste
 * dans BoardDaoTest.
 *
 * Commodité de départ : une grille de [boards] sans utilisateur est rangée chez l'utilisateur 1 (créé
 * s'il n'y a aucun [profiles]), avec l'id d'origine de la migration 3 → 4 ; si aucune grille n'est un
 * écran, chaque utilisateur reçoit son premier écran comme dans la migration 4 → 5. Les écritures, elles,
 * ne reçoivent aucune commodité. L'index unique des rangs d'écran est imité.
 */
class FakeBoardDao(
    boards: List<BoardEntity> = emptyList(),
    cells: List<CellEntity> = emptyList(),
    profiles: List<ProfileEntity> = if (boards.isEmpty()) emptyList() else listOf(ProfileEntity(FIRST_PROFILE_ID, "Utilisateur", startBoardId = null)),
) : BoardDao() {
    val profiles = MutableStateFlow(profiles)
    val boards = MutableStateFlow(
        boards.map { board ->
            if (board.profileId != NO_PROFILE) {
                board
            } else {
                val isLinked = cells.any { it.linkedBoardId == board.id }
                board.copy(profileId = FIRST_PROFILE_ID, coreBoardId = legacyCoreBoardId(board.id, isLinked))
            }
        }.let { profiled -> if (profiled.any { it.screenPosition != null }) profiled else withFirstScreens(profiles, profiled, cells) }
    )

    /** Comme l'index unique `(profile_id, screen_position)`. */
    private fun checkScreenPositions(allBoards: List<BoardEntity>) {
        val places = allBoards.mapNotNull { board -> board.screenPosition?.let { board.profileId to it } }
        if (places.size != places.toSet().size) throw SQLiteConstraintException("UNIQUE constraint failed: boards.profile_id, boards.screen_position")
    }
    val cells = MutableStateFlow(cells)

    /** Comme la clé étrangère `boards.profile_id` : une grille vise un utilisateur qui existe. */
    private fun checkProfiles(newBoards: List<BoardEntity>) {
        val profileIds = profiles.value.map { it.id }.toSet()
        newBoards.firstOrNull { it.profileId !in profileIds }?.let {
            throw SQLiteConstraintException("FOREIGN KEY constraint failed (board ${it.id}, profile ${it.profileId})")
        }
    }
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

    /** Levée par `boardExists` et `firstScreenOf` (base illisible en cours d'usage). */
    var existsFailure: RuntimeException? = null

    override suspend fun boardExists(boardId: Long): Boolean {
        existsFailure?.let { throw it }
        return boards.value.any { it.id == boardId }
    }

    override suspend fun largestBoardId(): Long? = boards.value.maxOfOrNull { it.id }

    /** Levée par l'insertion (disque plein, contrainte violée). */
    var insertFailure: RuntimeException? = null

    override suspend fun insertAll(boards: List<BoardEntity>, cells: List<CellEntity>) {
        insertGate?.await()
        insertFailure?.let { throw it }
        checkProfiles(boards)
        checkScreenPositions(this.boards.value + boards)
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
        // Un id donné est gardé (un doublon lève, comme ABORT) ; 0 prend max + 1, là où AUTOINCREMENT ne
        // réutiliserait jamais l'id d'une grille supprimée (plus aucun chemin n'insère avec 0).
        val id = if (board.id != 0L) board.id else (boards.value.maxOfOrNull { it.id } ?: 0) + 1
        if (boards.value.any { it.id == id }) throw SQLiteConstraintException("UNIQUE constraint failed: boards.id")
        checkProfiles(listOf(board))
        checkScreenPositions(boards.value + board)
        boards.value += board.copy(id = id)
        return id
    }

    override suspend fun updateCellContent(cellId: Long, label: String, pictogramId: Long?, category: String?, photoFile: String?, spokenText: String?): Int {
        val before = cells.value
        cells.value = before.map {
            if (it.id == cellId) it.copy(label = label, pictogramId = pictogramId, category = category, photoFile = photoFile, spokenText = spokenText) else it
        }
        return before.count { it.id == cellId }
    }

    override suspend fun renameBoard(boardId: Long, name: String): Int {
        val before = boards.value
        boards.value = before.map { if (it.id == boardId) it.copy(name = name) else it }
        return before.count { it.id == boardId }
    }

    override suspend fun cell(cellId: Long): CellEntity? = cells.value.firstOrNull { it.id == cellId }

    override suspend fun deleteCell(cellId: Long): Int {
        val before = cells.value.size
        cells.value = cells.value.filterNot { it.id == cellId }
        return before - cells.value.size
    }

    override suspend fun linkedBoardIds(boardId: Long): List<Long> =
        cells.value.filter { it.boardId == boardId }.mapNotNull { it.linkedBoardId }

    override suspend fun allBoards(): List<BoardEntity> = boards.value.sortedBy { it.id }

    override suspend fun allCells(): List<CellEntity> = cells.value.sortedWith(compareBy({ it.boardId }, { it.row }, { it.column }))

    override suspend fun deleteAllCells() {
        cells.value = emptyList()
    }

    override suspend fun deleteAllBoards() {
        boards.value = emptyList()
    }

    override suspend fun deleteAllProfiles() {
        profiles.value = emptyList()
    }

    /** Comme Room, qui n'émet qu'après la transaction : jamais une base vide entre les deux. */
    override suspend fun replaceAll(profiles: List<ProfileEntity>, boards: List<BoardEntity>, cells: List<CellEntity>) {
        val wereReadsHeld = areReadsHeld.value
        areReadsHeld.value = true
        try {
            super.replaceAll(profiles, boards, cells)
        } finally {
            areReadsHeld.value = wereReadsHeld
        }
    }

    override suspend fun referencedPictogramIds(): List<Long> = cells.value.mapNotNull { it.pictogramId }.distinct()

    override suspend fun referencedPhotoFiles(): List<String> = cells.value.mapNotNull { it.photoFile }.distinct()

    override suspend fun countLinksTo(boardId: Long): Int = cells.value.count { it.linkedBoardId == boardId }

    override suspend fun photoFilesOf(boardId: Long): List<String> = cells.value.filter { it.boardId == boardId }.mapNotNull { it.photoFile }

    override suspend fun deleteBoard(boardId: Long): Int {
        if (countLinksTo(boardId) != 0) throw SQLiteConstraintException("FOREIGN KEY constraint failed (RESTRICT)")
        val before = boards.value.size
        boards.value = boards.value.filterNot { it.id == boardId }
        cells.value = cells.value.filterNot { it.boardId == boardId }
        return before - boards.value.size
    }

    override suspend fun insertProfile(profile: ProfileEntity): Long {
        val id = if (profile.id != 0L) profile.id else (profiles.value.maxOfOrNull { it.id } ?: 0) + 1
        if (profiles.value.any { it.id == id }) throw SQLiteConstraintException("UNIQUE constraint failed: profiles.id")
        profiles.value += profile.copy(id = id)
        return id
    }

    override suspend fun insertProfiles(profiles: List<ProfileEntity>) {
        profiles.forEach { insertProfile(it) }
    }

    override suspend fun allProfiles(): List<ProfileEntity> = profiles.value.sortedBy { it.id }

    override fun observeProfiles(): Flow<List<ProfileEntity>> = profiles.map { all -> all.sortedBy { it.id } }

    /** Retient la prochaine lecture d'un utilisateur (ouverture lente, une fois l'utilisateur choisi), une seule fois. */
    var nextProfileReadGate: CompletableDeferred<Unit>? = null

    override suspend fun profile(profileId: Long): ProfileEntity? {
        nextProfileReadGate?.let { gate ->
            nextProfileReadGate = null
            gate.await()
        }
        return profiles.value.firstOrNull { it.id == profileId }
    }

    override suspend fun countProfiles(): Int = profiles.value.size

    override suspend fun smallestProfileId(): Long? = profiles.value.minOfOrNull { it.id }

    override suspend fun renameProfile(profileId: Long, name: String): Int = updateProfile(profileId) { it.copy(name = name) }

    override suspend fun setExitLocked(profileId: Long, isLocked: Boolean): Int = updateProfile(profileId) { it.copy(isExitLocked = isLocked) }

    private fun updateProfile(profileId: Long, change: (ProfileEntity) -> ProfileEntity): Int {
        val before = profiles.value
        profiles.value = before.map { if (it.id == profileId) change(it) else it }
        return before.count { it.id == profileId }
    }

    override suspend fun deleteProfileRow(profileId: Long): Int {
        if (boards.value.any { it.profileId == profileId }) throw SQLiteConstraintException("FOREIGN KEY constraint failed (RESTRICT)")
        val before = profiles.value.size
        profiles.value = profiles.value.filterNot { it.id == profileId }
        return before - profiles.value.size
    }

    override suspend fun hasBoards(profileId: Long): Boolean = boards.value.any { it.profileId == profileId }

    override suspend fun smallestProfileWithBoards(): Long? = boards.value.minOfOrNull { it.profileId }

    override suspend fun smallestOtherProfileWithBoards(profileId: Long): Long? =
        boards.value.filter { it.profileId != profileId }.minOfOrNull { it.profileId }

    override suspend fun profileOf(boardId: Long): Long? = boards.value.firstOrNull { it.id == boardId }?.profileId

    override suspend fun smallestBoardIdOf(profileId: Long): Long? = boards.value.filter { it.profileId == profileId }.minOfOrNull { it.id }

    override suspend fun isScreenOf(boardId: Long, profileId: Long): Boolean =
        boards.value.any { it.id == boardId && it.profileId == profileId && it.screenPosition != null }

    override suspend fun firstScreenOf(profileId: Long): Long? {
        existsFailure?.let { throw it }
        return screensOfNow(profileId).firstOrNull()?.id
    }

    override suspend fun allScreenIds(): List<Long> = boards.value.filter { it.screenPosition != null }.map { it.id }

    override suspend fun screensOf(profileId: Long): List<BoardEntity> = screensOfNow(profileId)

    private fun screensOfNow(profileId: Long) = boards.value.filter { it.profileId == profileId && it.screenPosition != null }.sortedBy { it.screenPosition }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeScreensBesides(boardId: Long): Flow<List<BoardEntity>> = areReadsHeld.flatMapLatest { isHeld ->
        if (isHeld) {
            emptyFlow()
        } else {
            boards.map { all ->
                val profileId = all.firstOrNull { it.id == boardId }?.profileId
                all.filter { it.profileId == profileId && it.screenPosition != null }.sortedBy { it.screenPosition }
            }
        }
    }

    override suspend fun setScreenPosition(boardId: Long, position: Int) {
        val changed = boards.value.map { if (it.id == boardId) it.copy(screenPosition = position) else it }
        checkScreenPositions(changed)
        boards.value = changed
    }

    override suspend fun renameScreen(profileId: Long, boardId: Long, name: String): Int {
        if (boards.value.none { it.id == boardId && it.profileId == profileId && it.screenPosition != null }) return 0
        return renameBoard(boardId, name)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCoreBoardId(boardId: Long): Flow<Long?> = areReadsHeld.flatMapLatest { isHeld ->
        if (isHeld) emptyFlow() else boards.map { all -> all.firstOrNull { it.id == boardId }?.coreBoardId }
    }

    override suspend fun photoFilesOfProfile(profileId: Long): List<String> {
        val boardIds = boards.value.filter { it.profileId == profileId }.map { it.id }.toSet()
        return cells.value.filter { it.boardId in boardIds }.mapNotNull { it.photoFile }
    }

    override suspend fun deleteCellsOfProfile(profileId: Long): Int {
        val boardIds = boards.value.filter { it.profileId == profileId }.map { it.id }.toSet()
        val before = cells.value.size
        cells.value = cells.value.filterNot { it.boardId in boardIds }
        return before - cells.value.size
    }

    override suspend fun deleteBoardsOfProfile(profileId: Long): Int {
        val doomed = boards.value.filter { it.profileId == profileId }.map { it.id }.toSet()
        if (cells.value.any { it.linkedBoardId in doomed }) throw SQLiteConstraintException("FOREIGN KEY constraint failed (RESTRICT)")
        boards.value = boards.value.filterNot { it.id in doomed }
        cells.value = cells.value.filterNot { it.boardId in doomed }
        return doomed.size
    }

    override suspend fun deleteProfilesWithoutBoards(): Int {
        val withBoards = boards.value.map { it.profileId }.toSet()
        val before = profiles.value.size
        profiles.value = profiles.value.filter { it.id in withBoards }
        return before - profiles.value.size
    }
}
