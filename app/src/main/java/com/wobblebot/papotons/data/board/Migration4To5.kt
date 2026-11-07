package com.wobblebot.papotons.data.board

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration4To5"

/**
 * Les écrans : l'écran de départ de chaque utilisateur devient son premier écran ([legacyFirstScreenOf]).
 * Par ajout de colonne, jamais en recréant `boards` (même raison que la migration 3 → 4) ; aucun id
 * ni aucune position de case ne bouge. Les accueils des autres tailles restent, sans rang.
 */
object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `boards` ADD COLUMN `screen_position` INTEGER")
        val linkedBoardIds = HashSet<Long>()
        db.query("SELECT `linked_board_id` FROM `cells` WHERE `linked_board_id` IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) linkedBoardIds += cursor.getLong(0)
        }
        val boardsByProfile = HashMap<Long, MutableList<BoardOrigin>>()
        db.query("SELECT `id`, `profile_id`, `core_board_id` FROM `boards`").use { cursor ->
            while (cursor.moveToNext()) {
                val coreBoardId = if (cursor.isNull(2)) null else cursor.getLong(2)
                boardsByProfile.getOrPut(cursor.getLong(1)) { mutableListOf() } += BoardOrigin(cursor.getLong(0), coreBoardId)
            }
        }
        db.query("SELECT `id`, `start_board_id` FROM `profiles`").use { cursor ->
            while (cursor.moveToNext()) {
                val profileId = cursor.getLong(0)
                val startBoardId = if (cursor.isNull(1)) null else cursor.getLong(1)
                val screenId = legacyFirstScreenOf(startBoardId, boardsByProfile[profileId].orEmpty(), linkedBoardIds)
                if (screenId == null) {
                    // Au lancement, cet utilisateur sans grille n'est jamais ouvert (repli sur un autre).
                    Log.w(TAG, "Profile $profileId has no board, no screen set")
                } else {
                    db.execSQL("UPDATE `boards` SET `screen_position` = ? WHERE `id` = ?", arrayOf<Any>(FIRST_SCREEN_POSITION, screenId))
                }
            }
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_boards_profile_id_screen_position` ON `boards` (`profile_id`, `screen_position`)")
    }
}
