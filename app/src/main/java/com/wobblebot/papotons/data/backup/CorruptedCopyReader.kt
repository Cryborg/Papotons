package com.wobblebot.papotons.data.backup

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val TAG = "CorruptedCopyReader"

/** Ids relus d'une requête : une tranche qui lève est relue id par id, ce qui la rend chère. */
private const val RECOVERY_CHUNK_IDS = 512L

/**
 * Plus grand id cherché, quand aucun ne se lit ou qu'un `max(id)` brouillé en annonce davantage. Les
 * ids de cases ne reviennent jamais en arrière (chaque restauration en donne de neufs), mais dix
 * millions demandent des centaines de restaurations des plus grosses grilles. Une tranche saine et
 * vide coûte une requête : 20 000 au pire, quelques secondes.
 */
private const val MAX_RECOVERY_ID = 10_000_000L

/**
 * La lecture s'arrête après autant de tranches abîmées de suite dont aucun id ne se relit : au-delà
 * du vrai dernier id, chaque recherche descend dans la dernière feuille abîmée, et chaque tranche
 * coûterait 512 requêtes jusqu'à [MAX_RECOVERY_ID]. Une page compte quelques dizaines de lignes :
 * 32 768 ids illisibles d'affilée ne sont plus une page, c'est la fin de la table.
 */
private const val DAMAGED_CHUNKS_TO_STOP = 64

private const val CURSOR_WINDOW_ALLOCATION_EXCEPTION = "android.database.CursorWindowAllocationException"

/**
 * La base et son journal WAL, qui porte les dernières écritures. Ni `-shm` (SQLite le rebâtit depuis
 * le WAL, une copie prise à un autre instant que lui le contredirait) ni `-journal` : en lecture
 * seule, un journal à rejouer rendrait la base illisible ; sans lui, une transaction à moitié écrite
 * se relit, et la validation l'attend au tournant comme toute ligne abîmée.
 */
private val COPIED_SUFFIXES = listOf("", "-wal")

/**
 * Relit ligne par ligne la base [databaseName] du dossier de copie [copyDirectory], recopiée dans
 * [workDirectory] (vidé avant et après) : SQLite n'ouvre jamais la copie elle-même, qui reste
 * intacte pour une autre tentative et pour la sauvegarde. `null` si rien ne s'ouvre ou que la base
 * n'est pas d'un schéma de Papotons. [IOException] si la copie ne se recopie pas, ou si le
 * stockage ou la mémoire empêchent de l'ouvrir ou de la lire. [checkCancelled] est appelé entre deux
 * tranches : une lecture abandonnée lève, rend son verrou et vide le dossier.
 */
fun readCorruptedCopy(copyDirectory: File, databaseName: String, workDirectory: File, checkCancelled: () -> Unit = {}): CopyTables? {
    if (workDirectory.exists() && !workDirectory.deleteRecursively()) throw IOException("Recovery work directory could not be emptied")
    if (!workDirectory.mkdirs()) throw IOException("Recovery work directory could not be created")
    try {
        val database = File(workDirectory, databaseName)
        for (suffix in COPIED_SUFFIXES) {
            val source = File(copyDirectory, databaseName + suffix)
            if (source.isFile) source.copyTo(File(database.path + suffix))
        }
        if (!database.isFile) {
            Log.w(TAG, "Copy ${copyDirectory.name} without its database")
            return null
        }
        // Lecture seule : Android ne touche ni au journal ni à la langue. Le gestionnaire muet :
        // celui par défaut supprimerait le fichier à la première page abîmée.
        val opened = readOrNull(isEnvironmentFatal = true) {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS, DatabaseErrorHandler { })
        } ?: run {
            Log.w(TAG, "Copy ${copyDirectory.name} could not be opened")
            return null
        }
        return opened.use { readTables(it, copyDirectory.name, checkCancelled) }
    } finally {
        if (!workDirectory.deleteRecursively()) Log.w(TAG, "Recovery work directory could not be deleted")
    }
}

private fun readTables(database: SQLiteDatabase, copyName: String, checkCancelled: () -> Unit): CopyTables? {
    val schemaVersion = readOrNull { database.version } ?: run {
        Log.w(TAG, "Copy $copyName: schema version unreadable")
        return null
    }
    if (schemaVersion !in RECOVERABLE_SCHEMAS) {
        Log.w(TAG, "Copy $copyName: schema $schemaVersion, not one of Papotons")
        return null
    }
    val profiles = if (schemaVersion >= FIRST_SCHEMA_WITH_PROFILES) readTable(database, "profiles", copyName, checkCancelled) else emptyList()
    val boards = readTable(database, "boards", copyName, checkCancelled)
    val cells = readTable(database, "cells", copyName, checkCancelled)
    // Par l'index : il survit parfois à la table, et dit combien de cases on n'a pas relues.
    val announced = readOrNull { database.rawQuery("SELECT count(*) FROM cells", null).use { if (it.moveToFirst()) it.getLong(0) else null } }
    Log.i(TAG, "Copy $copyName: schema $schemaVersion, ${profiles.size} profiles, ${boards.size} boards, ${cells.size} cells read, $announced cells announced")
    // Plus petit que ce qu'on a relu, ou au-delà de ce que la base peut porter : un index qui compte faux.
    return CopyTables(schemaVersion, profiles, boards, cells, announced?.takeIf { it in cells.size..MAX_CELLS }?.toInt())
}

/**
 * Les lignes de [table], dans l'ordre des ids. D'abord la table entière : une requête si elle est
 * saine, et un parcours qui suit les cellules sans comparer les clés (une clé brouillée en tête de
 * feuille ne cache rien). S'il lève, des tranches d'ids de 1 au plus grand : une tranche qui lève est
 * relue id par id, et seules ses lignes illisibles manquent (l'id est l'alias du rowid, seul l'arbre
 * de la table est lu). Pas de lecture jusqu'à la première erreur : la fenêtre du curseur se remplit
 * d'un bloc, l'erreur ferait perdre les lignes lues avant elle.
 */
private fun readTable(database: SQLiteDatabase, table: String, copyName: String, checkCancelled: () -> Unit): List<CopyRow> {
    // Table absente ou schéma illisible : rien à relire, sans payer une requête par id.
    if (readOrNull { database.rawQuery("SELECT * FROM $table WHERE 0", null).close() } == null) {
        Log.w(TAG, "Copy $copyName: $table unreadable")
        return emptyList()
    }
    readOrNull { database.rows("SELECT * FROM $table") }?.let { whole -> return whole.sortedBy { it["id"] as? Long ?: Long.MAX_VALUE } }
    // `max(id)` suit le chemin le plus à droite de l'arbre : s'il ne se lit pas, la dernière feuille est
    // abîmée, et `sqlite_sequence` ne donne qu'une borne basse (une ligne brouillée ou absente dirait
    // moins). La lecture va alors jusqu'à la règle des tranches abîmées.
    val fromTable = readOrNull { database.rawQuery("SELECT max(id) FROM $table", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } }
    val fromSequence = readOrNull {
        database.rawQuery("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }
    // 0 : `max(id)` n'a rien trouvé, alors que le parcours de la table a levé. Pas une fin, une feuille vidée.
    val maxId = fromTable?.takeIf { it > 0 }?.let { maxOf(it, fromSequence ?: 0L) }
    Log.w(TAG, "Copy $copyName: $table damaged, read by chunks up to ${maxId ?: "the damaged chunks"} (max(id): $fromTable, sequence: $fromSequence)")
    val last = (maxId ?: MAX_RECOVERY_ID).coerceAtMost(MAX_RECOVERY_ID)
    val rows = ArrayList<CopyRow>()
    var damagedChunks = 0
    var unreadableIds = 0
    var damagedInARow = 0
    // De 1, pas de `min(id)` : la première clé de la feuille de gauche, brouillée, se lit sans erreur.
    var start = 1L
    while (start <= last && damagedInARow < DAMAGED_CHUNKS_TO_STOP) {
        checkCancelled()
        val end = minOf(start + RECOVERY_CHUNK_IDS - 1, last)
        val chunk = readOrNull { database.rows("SELECT * FROM $table WHERE id BETWEEN ? AND ? ORDER BY id", start, end) }
        if (chunk != null) {
            rows += chunk
            if (chunk.isNotEmpty()) damagedInARow = 0
        } else {
            damagedChunks++
            var environmentFailures = 0
            var lastEnvironmentFailure: SQLiteException? = null
            val probed = (start..end).flatMap { id ->
                readOrNull(onEnvironmentFailure = { environmentFailures++; lastEnvironmentFailure = it }) { database.rows("SELECT * FROM $table WHERE id = ?", id) }
                    ?: emptyList<CopyRow>().also { unreadableIds++ }
            }
            // Les 512 ids d'une tranche pleine en panne d'environnement : le stockage ou la mémoire, pas une page.
            // Pas une tranche courte : la seule ligne d'une petite table, abîmée, se lirait comme une panne.
            if (environmentFailures.toLong() == RECOVERY_CHUNK_IDS) throw IOException("Recovery read failed on every id of a chunk of $table", lastEnvironmentFailure)
            rows += probed
            damagedInARow = if (probed.isEmpty()) damagedInARow + 1 else 0
        }
        start = end + 1
    }
    if (damagedInARow >= DAMAGED_CHUNKS_TO_STOP) Log.w(TAG, "Copy $copyName: $table, reading stopped at id $start after $DAMAGED_CHUNKS_TO_STOP damaged chunks")
    Log.w(TAG, "Copy $copyName: $table, $damagedChunks damaged chunks, $unreadableIds ids unreadable")
    return rows
}

/** Une erreur qui peut venir du stockage ou de la mémoire de l'appareil plutôt que du contenu de la copie. */
private fun SQLiteException.mayBeEnvironment(): Boolean =
    this is SQLiteDiskIOException || this is SQLiteCantOpenDatabaseException || this is SQLiteOutOfMemoryException || this is SQLiteAccessPermException

/**
 * `null` quand la lecture bute sur la copie abîmée : une page illisible ou un schéma brouillé
 * ([SQLiteException]), ou une ligne à la taille brouillée que la fenêtre du curseur ne sait pas tenir
 * ([IllegalStateException] avant Android 9, `SQLiteBlobTooBigException` ensuite). Une erreur d'E/S ou
 * de mémoire peut aussi venir d'un pointeur brouillé (une page lue au-delà du fichier, une longueur
 * démesurée sur un vieux SQLite, y compris dans le schéma) : pendant la lecture, c'est une page
 * abîmée, signalée à [onEnvironmentFailure]. Avec [isEnvironmentFatal] (ouvrir le fichier), elle ne
 * vient pas du contenu : [IOException], la copie compte comme non relue au lieu de passer pour illisible.
 * `SQLiteFullException`, en lecture seule, ne vient que d'un numéro de page brouillé : une page abîmée.
 */
private inline fun <T> readOrNull(isEnvironmentFatal: Boolean = false, onEnvironmentFailure: (SQLiteException) -> Unit = {}, read: () -> T): T? = try {
    read()
} catch (exception: CancellationException) {
    // Une `IllegalStateException` : jamais avalée comme une ligne illisible.
    throw exception
} catch (exception: SQLiteException) {
    if (exception.mayBeEnvironment()) {
        if (isEnvironmentFatal) throw IOException("Recovery read failed: ${exception.javaClass.simpleName}", exception)
        onEnvironmentFailure(exception)
    }
    null
} catch (exception: IllegalStateException) {
    // Depuis Android 9, une ligne trop grosse lève `SQLiteBlobTooBigException` : ailleurs, c'est un bug.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) throw exception
    null
} catch (exception: RuntimeException) {
    // Publique seulement depuis Android 13 : reconnue par son nom, tout le reste est un bug. La mémoire
    // partagée de l'appareil manque : une panne, pas une page.
    if (exception.javaClass.name != CURSOR_WINDOW_ALLOCATION_EXCEPTION) throw exception
    throw IOException("Cursor window could not be allocated", exception)
}

private fun SQLiteDatabase.rows(sql: String, vararg ids: Long): List<CopyRow> =
    rawQuery(sql, ids.map(Long::toString).toTypedArray()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.row()) }
    }

/**
 * Chaque valeur avec le type que SQLite lui donne : un entier stocké à la place d'un texte se voit.
 * Un texte qui n'est pas de l'UTF-8 valide reste en octets, donc n'est pas du texte pour la
 * validation : `getString` ne le dirait pas (Android décode sans vérifier les octets de suite).
 */
private fun Cursor.row(): CopyRow = columnNames.withIndex().associate { (index, name) ->
    name to when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getBlob(index).let { bytes -> strictUtf8(bytes) ?: bytes }
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        else -> null
    }
}

/** La fenêtre du curseur range un texte avec son zéro final, que `getBlob` rend aussi. `null` si ce n'est pas de l'UTF-8. */
internal fun strictUtf8(bytes: ByteArray): String? {
    val length = if (bytes.isNotEmpty() && bytes.last() == 0.toByte()) bytes.size - 1 else bytes.size
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
            .toString()
    } catch (exception: CharacterCodingException) {
        null
    }
}
