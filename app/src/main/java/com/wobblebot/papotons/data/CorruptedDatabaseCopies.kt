package com.wobblebot.papotons.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "CorruptedDatabaseCopies"

/** Dossier de `files/` où atterrissent les copies, une par corruption. */
const val CORRUPTED_DATABASES_DIRECTORY = "corrupted-databases"

/**
 * Les copies partent dans la sauvegarde Android, plafonnée à 25 Mo (au-delà, elle échoue en
 * entier) : sur un stockage qui flanche, une copie par lancement finirait par la bloquer.
 */
private const val MAX_KEPT_COPIES = 3

/** Numéro d'ordre en tête du nom, complété de zéros : l'ordre des noms est l'ordre des copies. */
private const val SEQUENCE_DIGITS = 6

/** Nom d'une copie en cours, jamais comptée parmi les gardées. */
const val PENDING_COPY_PREFIX = ".pending-"

/**
 * Les copies de bases corrompues (`files/corrupted-databases/<numéro>-<suffixe>/`), partagées par
 * la fabrique qui les fait et le dépôt qui en déduit une perte. Le marqueur de modification vit
 * HORS du dossier : un nom sans numéro y compterait comme la plus ancienne copie.
 */
class CorruptedDatabaseCopies(filesDirectory: File) {
    val directory = File(filesDirectory, CORRUPTED_DATABASES_DIRECTORY)
    private val editedMarker = File(filesDirectory, "$CORRUPTED_DATABASES_DIRECTORY.edited")

    /** `null` du système (dossier absent, le cas courant, ou illisible) : aucune copie. */
    fun hasCopies(): Boolean = copyNames().isNotEmpty()

    fun nextName(suffix: String): String = "${(highestSequence() + 1).toString().padStart(SEQUENCE_DIGITS, '0')}-$suffix"

    /**
     * À la première modification des grilles après une copie : retient la dernière copie existante.
     * La première copie faite ensuite portera les grilles refaites depuis la perte : l'élagage la garde.
     */
    @Throws(IOException::class)
    fun markEdited() {
        val highest = highestSequence()
        if (highest == 0 || (editedSequence() ?: 0) >= highest) return
        // Remonte l'échec : une modification sans son marqueur ne serait pas protégée. Temporaire
        // puis renommage : un marqueur à moitié écrit protégerait une mauvaise copie.
        val pending = File(editedMarker.path + ".pending")
        // Synchronisé avant le renommage : après une coupure de courant, un marqueur vide rendrait
        // l'élagage impossible jusqu'à la modification suivante.
        FileOutputStream(pending).use { output ->
            output.write(highest.toString().toByteArray())
            output.fd.sync()
        }
        if (!pending.renameTo(editedMarker)) throw IOException("Edit marker could not be renamed")
    }

    /**
     * Garde la première copie (les grilles d'avant la première perte), la première copie faite
     * après une modification (les grilles refaites depuis), et les plus récentes, dans la limite de
     * [MAX_KEPT_COPIES]. Les noms sans numéro (restes d'une version de développement) comptent
     * comme les plus anciens.
     */
    fun prune() {
        val ordered = directory.listFiles().orEmpty()
            .filterNot { it.name.startsWith(PENDING_COPY_PREFIX) }
            .sortedWith(compareBy({ sequenceOf(it.name) }, { it.name }))
        // Marqueur présent mais illisible : on ne sait plus quelle copie protéger, on n'en supprime aucune.
        val edited = editedSequence() ?: run {
            Log.e(TAG, "Edit marker unreadable, no copy pruned")
            return
        }
        val kept = LinkedHashSet<File>()
        ordered.firstOrNull()?.let(kept::add)
        if (edited > 0) ordered.firstOrNull { sequenceOf(it.name) > edited }?.let(kept::add)
        for (copy in ordered.asReversed()) {
            if (kept.size >= MAX_KEPT_COPIES) break
            kept.add(copy)
        }
        for (copy in ordered - kept) {
            if (copy.deleteRecursively()) Log.w(TAG, "Copy $copy deleted") else Log.w(TAG, "Copy $copy could not be deleted")
        }
    }

    private fun copyNames(): List<String> = directory.list().orEmpty().filterNot { it.startsWith(PENDING_COPY_PREFIX) }

    private fun highestSequence(): Int = copyNames().maxOfOrNull(::sequenceOf) ?: 0

    /** 0 sans marqueur ; `null` s'il est illisible, jamais une exception qui traverserait `onCorruption`. */
    private fun editedSequence(): Int? = try {
        if (editedMarker.isFile) editedMarker.readText().trim().toIntOrNull() else 0
    } catch (exception: IOException) {
        Log.e(TAG, "Edit marker could not be read", exception)
        null
    }

    private fun sequenceOf(name: String): Int = name.substringBefore('-').toIntOrNull() ?: 0
}
