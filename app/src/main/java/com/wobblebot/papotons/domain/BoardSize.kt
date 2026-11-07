package com.wobblebot.papotons.domain

/**
 * Une taille de cases = un jeu de grilles à part dans `CoreVocabulary` : son accueil a un id fixe
 * ([homeBoardId], vérifié par le générateur et par CoreVocabularyTest), et ses dossiers ont leur
 * taille, reprise par les dossiers que l'adulte y crée. Un écran reçoit à sa création la copie du jeu
 * de sa taille ; on reconnaît sa taille au `core_board_id` de sa racine.
 */
enum class BoardSize(
    val homeBoardId: Long,
    /** Les dimensions de son accueil, que les Réglages dessinent (vérifiées par CoreVocabularyTest). */
    val homeRowCount: Int,
    val homeColumnCount: Int,
    val folderRowCount: Int,
    val folderColumnCount: Int,
) {
    LARGE_CELLS(homeBoardId = 13, homeRowCount = 3, homeColumnCount = 4, folderRowCount = 2, folderColumnCount = 3),
    MEDIUM(homeBoardId = 7, homeRowCount = 5, homeColumnCount = 6, folderRowCount = 3, folderColumnCount = 4),
    STANDARD(homeBoardId = HOME_BOARD_ID, homeRowCount = 7, homeColumnCount = 8, folderRowCount = 4, folderColumnCount = 6);

    companion object {
        /** La taille dont [coreBoardId] (l'id d'origine d'une grille) est l'accueil, `null` pour toute autre grille. */
        fun withHome(coreBoardId: Long?): BoardSize? = entries.firstOrNull { it.homeBoardId == coreBoardId }
    }
}
