package com.wobblebot.papotons.domain

/**
 * Une taille de cases = un jeu de grilles à part, inséré avec le vocabulaire de base : son accueil a
 * un id fixe (vérifié par le générateur et par CoreVocabularyTest), et ses dossiers ont leur taille,
 * reprise par les dossiers que l'adulte y crée. Persistée par l'id de son accueil (écran de départ),
 * jamais par son nom.
 */
enum class BoardSize(val homeBoardId: Long, val folderRowCount: Int, val folderColumnCount: Int) {
    LARGE_CELLS(homeBoardId = 13, folderRowCount = 2, folderColumnCount = 3),
    MEDIUM(homeBoardId = 7, folderRowCount = 3, folderColumnCount = 4),
    STANDARD(homeBoardId = HOME_BOARD_ID, folderRowCount = 4, folderColumnCount = 6);

    companion object {
        /** La taille dont [boardId] est l'accueil, `null` pour toute autre grille. */
        fun withHome(boardId: Long?): BoardSize? = entries.firstOrNull { it.homeBoardId == boardId }
    }
}
