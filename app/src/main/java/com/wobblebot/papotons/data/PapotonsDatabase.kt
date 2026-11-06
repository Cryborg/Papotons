package com.wobblebot.papotons.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wobblebot.papotons.data.board.BoardDao
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.CellEntity

/**
 * Toute montée de [version] passe par une migration qui garde les grilles (invariant 4) :
 * jamais de `fallbackToDestructiveMigration`. Le schéma de chaque version est exporté
 * dans `app/schemas/` et versionné.
 */
@Database(entities = [BoardEntity::class, CellEntity::class], version = 1, exportSchema = true)
abstract class PapotonsDatabase : RoomDatabase() {
    abstract fun boardDao(): BoardDao

    companion object {
        const val NAME = "papotons.db"
    }
}
