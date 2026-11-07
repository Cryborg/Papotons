package com.wobblebot.papotons.data.profile

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wobblebot.papotons.data.board.FIRST_USER_BOARD_ID
import com.wobblebot.papotons.domain.BoardSize

/**
 * Les grilles existantes deviennent celles d'un premier utilisateur, nommé [firstProfileName].
 * Par ajout de colonnes, jamais en recréant `boards` : un `DROP TABLE boards` avec les clés
 * étrangères actives viderait `cells` par la CASCADE. Aucun id ne bouge (invariant 4).
 */
class Migration3To4(private val firstProfileName: String) : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `start_board_id` INTEGER, `is_exit_locked` INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("INSERT INTO `profiles` (`id`, `name`, `start_board_id`) VALUES (?, ?, NULL)", arrayOf<Any>(FIRST_PROFILE_ID, firstProfileName))
        // Valeur par défaut non nulle avec REFERENCES : SQLite ne l'accepte que clés étrangères
        // désactivées, ce que fait Room pendant une migration. Sinon : échec, base intacte.
        db.execSQL(
            "ALTER TABLE `boards` ADD COLUMN `profile_id` INTEGER NOT NULL DEFAULT $FIRST_PROFILE_ID " +
                "REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT"
        )
        db.execSQL("ALTER TABLE `boards` ADD COLUMN `core_board_id` INTEGER")
        // La règle de [legacyCoreBoardId], en SQL.
        val sizeHomeIds = SIZE_HOMES_THAT_WERE_FOLDER_IDS.joinToString()
        db.execSQL(
            "UPDATE `boards` SET `core_board_id` = `id` WHERE `id` < $FIRST_USER_BOARD_ID AND NOT (`id` IN ($sizeHomeIds) " +
                "AND `id` IN (SELECT `linked_board_id` FROM `cells` WHERE `linked_board_id` IS NOT NULL))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_boards_profile_id` ON `boards` (`profile_id`)")
    }
}

/** Avant les tailles, un dossier de l'adulte pouvait recevoir l'id de l'accueil d'une taille. */
private val SIZE_HOMES_THAT_WERE_FOLDER_IDS = BoardSize.entries.filter { it != BoardSize.STANDARD }.map { it.homeBoardId }

/**
 * La grille du vocabulaire de base dont une grille d'avant les utilisateurs est la copie : son id sous
 * [FIRST_USER_BOARD_ID], sauf un accueil de taille visé par une case (c'était un dossier de l'adulte).
 * Même règle pour la migration 3 → 4 et pour une sauvegarde au format 1.
 */
fun legacyCoreBoardId(boardId: Long, isLinked: Boolean): Long? =
    boardId.takeIf { it < FIRST_USER_BOARD_ID && !(isLinked && it in SIZE_HOMES_THAT_WERE_FOLDER_IDS) }
