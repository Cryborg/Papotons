package com.wobblebot.papotons.data.board

import androidx.annotation.StringRes
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.HOME_BOARD_ID

class DemoSeed(val boards: List<BoardEntity>, val cells: List<CellEntity>)

/**
 * Grilles insérées au premier lancement, dans la langue de l'appareil, en attendant le
 * vocabulaire de base. Les libellés viennent des ressources par [label] (`Context::getString`),
 * ce qui garde la construction testable sans Android.
 */
object DemoBoards {
    private const val EAT_BOARD_ID = 2L
    private const val PLAY_BOARD_ID = 3L
    private const val HOME_ROW_COUNT = 3
    private const val HOME_COLUMN_COUNT = 4
    private const val FOLDER_ROW_COUNT = 2
    private const val FOLDER_COLUMN_COUNT = 3

    fun build(label: (Int) -> String): DemoSeed {
        val boards = listOf(
            BoardEntity(HOME_BOARD_ID, label(R.string.demo_board_home), HOME_ROW_COUNT, HOME_COLUMN_COUNT),
            BoardEntity(EAT_BOARD_ID, label(R.string.demo_board_eat), FOLDER_ROW_COUNT, FOLDER_COLUMN_COUNT),
            BoardEntity(PLAY_BOARD_ID, label(R.string.demo_board_play), FOLDER_ROW_COUNT, FOLDER_COLUMN_COUNT),
        )
        fun word(boardId: Long, row: Int, column: Int, @StringRes text: Int) =
            CellEntity(boardId = boardId, row = row, column = column, label = label(text), linkedBoardId = null)
        fun folder(row: Int, column: Int, @StringRes text: Int, target: Long) =
            CellEntity(boardId = HOME_BOARD_ID, row = row, column = column, label = label(text), linkedBoardId = target)

        val cells = listOf(
            word(HOME_BOARD_ID, 0, 0, R.string.demo_word_i),
            word(HOME_BOARD_ID, 0, 1, R.string.demo_word_want),
            word(HOME_BOARD_ID, 0, 2, R.string.demo_word_not),
            word(HOME_BOARD_ID, 0, 3, R.string.demo_word_more),
            word(HOME_BOARD_ID, 1, 0, R.string.demo_word_yes),
            word(HOME_BOARD_ID, 1, 1, R.string.demo_word_no),
            word(HOME_BOARD_ID, 1, 2, R.string.demo_word_help),
            word(HOME_BOARD_ID, 1, 3, R.string.demo_word_finished),
            word(HOME_BOARD_ID, 2, 0, R.string.demo_word_you),
            folder(2, 2, R.string.demo_board_eat, EAT_BOARD_ID),
            folder(2, 3, R.string.demo_board_play, PLAY_BOARD_ID),
            word(EAT_BOARD_ID, 0, 0, R.string.demo_word_apple),
            word(EAT_BOARD_ID, 0, 1, R.string.demo_word_bread),
            word(EAT_BOARD_ID, 0, 2, R.string.demo_word_water),
            word(EAT_BOARD_ID, 1, 0, R.string.demo_word_cake),
            word(EAT_BOARD_ID, 1, 1, R.string.demo_word_yogurt),
            word(PLAY_BOARD_ID, 0, 0, R.string.demo_word_ball),
            word(PLAY_BOARD_ID, 0, 1, R.string.demo_word_puzzle),
            word(PLAY_BOARD_ID, 0, 2, R.string.demo_word_drawing),
            word(PLAY_BOARD_ID, 1, 0, R.string.demo_word_music),
            word(PLAY_BOARD_ID, 1, 1, R.string.demo_word_outside),
        )
        return DemoSeed(boards, cells)
    }
}
