package com.wobblebot.papotons.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.wobblebot.papotons.data.board.BoardDao
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.CellEntity

/**
 * Toute montée de [version] passe par une migration qui garde les grilles (invariant 4) :
 * jamais de `fallbackToDestructiveMigration`. Le schéma de chaque version est exporté
 * dans `app/schemas/` et versionné.
 */
@Database(
    entities = [BoardEntity::class, CellEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class PapotonsDatabase : RoomDatabase() {
    abstract fun boardDao(): BoardDao

    companion object {
        const val NAME = "papotons.db"
    }
}

/**
 * La seule façon d'ouvrir la base, partagée par l'appli et ses tests instrumentés : la copie de
 * secours d'une base corrompue ([CorruptionCopyingOpenHelperFactory]) ne peut pas être oubliée.
 */
fun buildDatabase(context: Context, name: String): PapotonsDatabase =
    Room.databaseBuilder(context, PapotonsDatabase::class.java, name)
        .openHelperFactory(CorruptionCopyingOpenHelperFactory(context))
        .build()
