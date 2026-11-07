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

/**
 * La grille [rootId] de la graine et tout ce que ses dossiers ouvrent, avec leurs cases, dans l'ordre
 * de la graine : le vocabulaire d'un écran, d'une seule taille.
 */
fun VocabularySeed.subtree(rootId: Long): VocabularySeed {
    val reached = LinkedHashSet<Long>()
    val toVisit = ArrayDeque(listOf(rootId))
    while (toVisit.isNotEmpty()) {
        val boardId = toVisit.removeFirst()
        if (reached.add(boardId)) toVisit.addAll(cells.filter { it.boardId == boardId }.mapNotNull { it.linkedBoardId })
    }
    return VocabularySeed(boards = boards.filter { it.id in reached }, cells = cells.filter { it.boardId in reached })
}

/**
 * La copie de [rootCoreBoardId] (reconnue à son `coreBoardId`) devient un écran au rang [position],
 * nommée [name] si donné ; les autres grilles restent des dossiers.
 */
fun List<BoardEntity>.asScreen(rootCoreBoardId: Long, position: Int, name: String?): List<BoardEntity> {
    check(count { it.coreBoardId == rootCoreBoardId } == 1) { "Screen root $rootCoreBoardId not found once" }
    return map { board ->
        if (board.coreBoardId == rootCoreBoardId) board.copy(screenPosition = position, name = name ?: board.name) else board.copy(screenPosition = null)
    }
}
