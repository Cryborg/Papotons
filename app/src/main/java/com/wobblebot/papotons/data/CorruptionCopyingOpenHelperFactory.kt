package com.wobblebot.papotons.data

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.IOException
import java.util.UUID

private const val TAG = "CorruptionCopying"

/** Ce que `SQLiteDatabase.deleteDatabase` efface avec la base (hors `-mj*`, transitoires). */
private val COMPANION_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

/**
 * Le comportement par défaut d'`onCorruption` supprime la base abîmée, et avec elle les grilles de
 * l'utilisateur. Cette fabrique en garde d'abord une copie dans `files/corrupted-databases/`
 * (invariant 4), puis laisse la suppression se faire : sans elle, l'appli planterait à chaque
 * ouverture. Ne sert que tant que Room passe par un `SupportSQLiteOpenHelper` : un `SQLiteDriver`
 * l'ignorerait, et les drivers n'ont pas d'`onCorruption`. `allowDataLossOnRecovery` doit rester
 * faux : vrai, le helper supprimerait la base sans passer par `onCorruption`, donc sans copie.
 */
class CorruptionCopyingOpenHelperFactory(
    private val context: Context,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration(
                context = configuration.context,
                name = configuration.name,
                callback = CorruptionCopyingCallback(configuration.callback),
                useNoBackupDirectory = configuration.useNoBackupDirectory,
                allowDataLossOnRecovery = configuration.allowDataLossOnRecovery,
            )
        )

    /**
     * Délègue TOUT au callback de Room : son `onOpen` vérifie l'identité du schéma et démarre le
     * suivi des invalidations, sans lequel les `Flow` ne réémettraient plus après une écriture.
     * Classe abstraite, pas de délégation `by` : une méthode oubliée compilerait sans un mot.
     */
    private inner class CorruptionCopyingCallback(
        private val roomCallback: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(roomCallback.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = roomCallback.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = roomCallback.onCreate(db)

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = roomCallback.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            // Supprimée quoi qu'il arrive à la copie : gardée, la base ferait planter chaque ouverture.
            try {
                val path = db.path
                if (path == null) Log.e(TAG, "Corrupted database without path, nothing copied") else keepCopy(path)
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Corrupted database copy failed unexpectedly", exception)
            } finally {
                roomCallback.onCorruption(db)
            }
        }
    }

    /**
     * Copie au mieux, fichier par fichier : un échec est journalisé et la suppression a lieu quand
     * même. Sans son `-wal`, une base principale garde ce qui y avait déjà été reporté, pas les
     * dernières écritures : c'est toujours mieux que rien. Une corruption détectée en cours d'usage
     * copie des fichiers ouverts, donc peut-être incohérents.
     */
    private fun keepCopy(databasePath: String) {
        val copies = CorruptedDatabaseCopies(context.filesDir)
        // Le numéro, pas l'heure : une horloge qui recule (batterie vidée) ne doit pas faire passer
        // une copie neuve pour la première. Le suffixe départage deux corruptions simultanées.
        val suffix = UUID.randomUUID()
        val target = File(copies.directory, copies.nextName(suffix.toString()))
        // Copiée sous un nom provisoire puis renommée : une copie interrompue (processus tué) ne
        // prend jamais la place de la première.
        val pending = File(copies.directory, "$PENDING_COPY_PREFIX$suffix")
        val sources = COMPANION_SUFFIXES.map { File(databasePath + it) }.filter { it.isFile }
        if (sources.isEmpty()) {
            Log.e(TAG, "Corrupted database $databasePath: no file found to copy")
            return
        }
        val copied = sources.filter { source -> copyOrDropPartial(source, File(pending, source.name)) }
        if (copied.isEmpty() || !pending.renameTo(target)) {
            Log.e(TAG, "Corrupted database $databasePath: no copy kept (${copied.size} of ${sources.size} files copied)")
            if (pending.exists() && !pending.deleteRecursively()) Log.w(TAG, "Pending copy $pending could not be deleted")
            return
        }
        Log.e(TAG, "Corrupted database $databasePath: ${copied.size} of ${sources.size} files kept in $target")
        copies.prune()
    }

    /** Vrai quand [destination] est une copie complète ; un fichier tronqué est supprimé. */
    private fun copyOrDropPartial(source: File, destination: File): Boolean = try {
        source.copyTo(destination)
        true
    } catch (exception: IOException) {
        Log.e(TAG, "Could not copy $source to $destination", exception)
        if (destination.exists() && !destination.delete()) Log.w(TAG, "Partial file $destination could not be deleted")
        false
    } catch (exception: SecurityException) {
        Log.e(TAG, "Could not copy $source to $destination", exception)
        false
    }

}
