package com.wobblebot.papotons.data.board

/**
 * Le vocabulaire de base d'un utilisateur : [seed] (ids fixes de `CoreVocabulary`) renuméroté en ids
 * contigus à partir de [firstBoardId], dans l'ordre de la graine. Les dossiers suivent la
 * renumérotation, chaque grille retient son id d'origine (`coreBoardId`), les positions ne bougent
 * pas (invariant 3) et les cases repartent à l'id 0 (la base en donne de neufs).
 */
fun VocabularySeed.forProfile(profileId: Long, firstBoardId: Long): VocabularySeed {
    val newIds = boards.withIndex().associate { (index, board) -> board.id to firstBoardId + index }
    fun newId(coreBoardId: Long) = checkNotNull(newIds[coreBoardId]) { "Seed cell in unknown board $coreBoardId" }
    return VocabularySeed(
        boards = boards.map { board -> board.copy(id = newId(board.id), profileId = profileId, coreBoardId = board.id) },
        cells = cells.map { cell -> cell.copy(id = 0, boardId = newId(cell.boardId), linkedBoardId = cell.linkedBoardId?.let(::newId)) },
    )
}
