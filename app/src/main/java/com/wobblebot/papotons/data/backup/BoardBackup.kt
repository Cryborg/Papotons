package com.wobblebot.papotons.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.CorruptedDatabaseCopies
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.photo.CELL_PHOTOS_DIRECTORY
import com.wobblebot.papotons.data.photo.CellPhotos
import com.wobblebot.papotons.data.pictogram.DOWNLOADED_PICTOGRAMS_DIRECTORY
import com.wobblebot.papotons.data.pictogram.MAX_DOWNLOADED_SIDE_PX
import com.wobblebot.papotons.data.pictogram.MAX_PICTOGRAM_BYTES
import com.wobblebot.papotons.data.pictogram.PictogramImages
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BoardBackup"
private const val RESTORE_FILE = "restore.obz"

/** Une copie de base corrompue (quelques centaines de Ko en pratique) lue en mémoire avant d'être écrite. */
private const val MAX_RECOVERY_FILE_BYTES = 64L * 1024 * 1024

private const val DEFLATE_GROWTH_DIVISOR = 1_000L

/** En-têtes locaux et centraux d'une entrée (nom compris), et réserve pour le répertoire central. */
private const val ENTRY_OVERHEAD_BYTES = 512L
private const val WRITABLE_ARCHIVE_BYTES = MAX_ARCHIVE_BYTES - MAX_ENTRIES * ENTRY_OVERHEAD_BYTES

/** Hors du manifeste : les autres lecteurs, et la restauration, les ignorent. Pour un expert. */
private const val CORRUPTED_COPIES_PREFIX = "papotons/corrupted-databases"
private const val UNREFERENCED_IMAGES_PREFIX = "papotons/unreferenced-images"

/** Une sauvegarde lue, validée, prête à remplacer les grilles. */
data class PendingRestore(val fileName: String?, val content: BackupContent) {
    val profileNames: List<String> get() = content.profiles.map { it.name }
    val boardCount: Int get() = content.boards.size
    val cellCount: Int get() = content.cells.size
}

/**
 * Ce que l'écran de sauvegarde demande. Couture de test : le ViewModel se teste sur la JVM. Les
 * échecs sortent en [BackupRefusedException] (archive refusée), [IOException] ou [SecurityException]
 * (document inaccessible).
 */
interface BackupStore {
    /**
     * Écrit toutes les grilles dans [destination] ; en cas d'échec, le document est supprimé. Rend le
     * nombre d'images présentes sur l'appareil mais laissées de côté parce qu'abîmées (leurs cases
     * gardent la référence) : la sauvegarde n'est alors pas complète.
     */
    suspend fun export(destination: Uri): Int

    /** Lit et valide une sauvegarde, sans rien écrire dans les grilles. */
    suspend fun analyze(source: Uri): PendingRestore

    /** Remet les images, puis remplace les grilles d'un coup. Va au bout même si l'écran disparaît. */
    suspend fun restore(pending: PendingRestore)

    /** Oublie la sauvegarde lue et pas restaurée. */
    suspend fun discard()

    /** Un document créé pour rien (sauvegarde revenue trop tard) : supprimé, au mieux. */
    suspend fun deleteDocument(document: Uri)
}

/**
 * Sauvegarde dans un `.obz` que l'adulte range où il veut (l'appli n'envoie rien, invariant 1), et
 * restauration d'une sauvegarde de Papotons. Le fichier choisi est copié dans `no_backup/` avant
 * d'être lu : `ZipFile` a besoin d'un fichier, et le document peut disparaître entre l'analyse et
 * la confirmation.
 */
@Singleton
class BoardBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BoardRepository,
    private val pictograms: PictogramImages,
    private val photos: CellPhotos,
) : BackupStore {
    private val restoreFile = File(context.noBackupFilesDir, RESTORE_FILE)
    private val photosDirectory = File(context.filesDir, CELL_PHOTOS_DIRECTORY)
    private val downloadedDirectory = File(context.filesDir, DOWNLOADED_PICTOGRAMS_DIRECTORY)

    /** Une lecture, une restauration ou un oubli à la fois : jamais `restore.obz` effacé pendant qu'on le lit. */
    private val restoreFileLock = Mutex()

    // NonCancellable : un retour arrière qui détruit l'écran ne laisse pas une archive tronquée.
    override suspend fun export(destination: Uri): Int = withContext(NonCancellable + Dispatchers.IO) {
        var writeCount = 0L
        val leftOut = try {
            // Dans le `try` : une base illisible ne laisse pas un document vide nommé comme une sauvegarde.
            val (content, countAtSnapshot) = repository.snapshot()
            writeCount = countAtSnapshot
            val pictogramIds = content.cells.mapNotNull { it.pictogramId }.toSortedSet()
            val photoFiles = content.cells.mapNotNull { it.photoFile }.toSortedSet()
            // Seules les images que la restauration acceptera : une photo abîmée sur l'appareil ne
            // doit pas rendre toute la sauvegarde illisible. Sa case garde la référence.
            var damaged = 0
            val pictogramSizes = pictogramIds.mapNotNull { id ->
                val bytes = try {
                    val stream = pictograms.open(id) ?: return@mapNotNull null
                    stream.use { it.readAtMost(MAX_PICTOGRAM_BYTES.toLong()) }
                } catch (exception: IOException) {
                    // Un fichier illisible de l'appareil est une image abîmée, pas une sauvegarde impossible.
                    null
                }
                if (bytes != null && isRestorable(bytes, isPictogram = true)) {
                    id to bytes.size
                } else {
                    damaged++
                    null
                }
            }.toMap()
            val photoSizes = photoFiles.mapNotNull { name ->
                val file = photoFile(name) ?: return@mapNotNull null
                val bytes = try {
                    file.inputStream().use { it.readAtMost(MAX_ENTRY_BYTES) }
                } catch (exception: IOException) {
                    null
                }
                if (bytes != null && isRestorable(bytes, isPictogram = false)) {
                    name to bytes.size
                } else {
                    damaged++
                    null
                }
            }.toMap()
            val pictogramsToWrite = pictogramSizes.keys
            val photosToWrite = photoSizes.keys
            // La borne de la restauration, sur les images distinctes.
            val imageBytes = pictogramSizes.values.sumOf { it.toLong() } + photoSizes.values.sumOf { it.toLong() }
            if (imageBytes > MAX_IMAGE_BYTES) refuse(RefusalReason.TooLarge, "$imageBytes bytes of images")
            // Valide aussi : une sauvegarde que la restauration refuserait n'est pas écrite.
            val entries = backupEntries(content, pictogramsToWrite, photosToWrite)
            val output = CountingOutputStream(openForWriting(destination))
            val budget = ArchiveBudget(output)
            // La place des grilles et du manifeste, écrits après le secours, est gardée d'avance.
            budget.reserve(entries.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }, entries.size)
            output.use {
                ZipOutputStream(output).use { zip ->
                    for (id in pictogramsToWrite) {
                        val image = pictograms.open(id) ?: throw FileNotFoundException("Pictogram vanished")
                        zip.putEntry(pictogramImagePath(id), budget, image)
                    }
                    for (name in photosToWrite) zip.putEntry(photoImagePath(name), budget, File(photosDirectory, name).inputStream())
                    putRecoveryMaterial(zip, budget, pictogramIds, photoFiles)
                    budget.reserve(0, 0)
                    // Le manifeste en dernier : un lecteur en flux qui le trouve a tout le reste avant lui.
                    for ((path, text) in entries.entries.reversed()) zip.putEntry(path, budget, text.byteInputStream(Charsets.UTF_8))
                }
            }
            Log.i(TAG, "Backup written: ${content.boards.size} boards, ${content.cells.size} cells, ${pictogramsToWrite.size + photosToWrite.size} images")
            val absent = pictogramIds.size - pictogramsToWrite.size + photoFiles.size - photosToWrite.size - damaged
            // Image absente de l'appareil : la référence part, pas l'image, comme en base.
            if (absent > 0) Log.w(TAG, "$absent references without an image on the device")
            if (damaged > 0) Log.e(TAG, "$damaged damaged images left out of the backup")
            damaged
        } catch (throwable: Throwable) {
            // Jamais une archive partielle que l'adulte prendrait pour une sauvegarde, quoi qu'il arrive.
            deleteDocument(destination)
            if (throwable is OutOfMemoryError) throw OutOfMemoryWhileWriting()
            throw throwable
        }
        // Hors du `try` : la sauvegarde est écrite, rien ici ne doit la faire supprimer. Une image abîmée
        // laissée de côté manque à la sauvegarde : les grilles ne se disent pas sauvegardées.
        if (leftOut == 0) repository.markSaved(writeCount)
        leftOut
    }

    /** Un manque de mémoire passager n'est pas une sauvegarde trop grosse : on peut réessayer. */
    private class OutOfMemoryWhileWriting : IOException("Out of memory while writing")

    private fun photoFile(name: String): File? = File(photosDirectory, name).takeIf { it.isFile }

    private fun isReadablePhoto(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** La règle de la restauration (voir [imageRefusal]), appliquée avant d'écrire une image. */
    private suspend fun isRestorable(bytes: ByteArray, isPictogram: Boolean): Boolean = imageRefusal(bytes, isPictogram) == null

    /**
     * Ce que la restauration exige d'une image : lisible, et pour un picto, dans les bornes d'un
     * téléchargement et décodable en entier (il est écrit tel quel). Une photo est réencodée.
     */
    private suspend fun imageRefusal(bytes: ByteArray, isPictogram: Boolean): RefusalReason? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return RefusalReason.Unreadable
        if (!isPictogram) return null
        if (maxOf(bounds.outWidth, bounds.outHeight) > MAX_DOWNLOADED_SIDE_PX) return RefusalReason.TooLarge
        return if (pictograms.decode(bytes) == null) RefusalReason.Unreadable else null
    }

    /** `"wt"` tronque un document existant ; certains fournisseurs ne le connaissent pas, et il vient d'être créé. */
    private fun openForWriting(destination: Uri): OutputStream {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openOutputStream(destination, "wt")
        } catch (exception: IllegalArgumentException) {
            resolver.openOutputStream(destination, "w")
        } catch (exception: FileNotFoundException) {
            resolver.openOutputStream(destination, "w")
        }
        return stream ?: throw FileNotFoundException("No output stream")
    }

    /**
     * Après une perte (une copie corrompue existe) : les copies telles quelles, et les images
     * qu'aucune case de la base ne vise (celles des grilles perdues ; une photo prise à l'appareil
     * n'existe nulle part ailleurs).
     */
    private fun putRecoveryMaterial(zip: ZipOutputStream, budget: ArchiveBudget, pictogramIds: Set<Long>, photoFiles: Set<String>) {
        val copies = CorruptedDatabaseCopies(context.filesDir)
        if (!copies.directory.exists()) return
        val referencedPictogramFiles = pictogramIds.map { "$it.png" }.toSet()
        val material = copies.copyDirectories().flatMap { copy ->
            copy.listFiles().orEmpty().filter { it.isFile }.map { "$CORRUPTED_COPIES_PREFIX/${copy.name}/${it.name}" to it }
        } + downloadedDirectory.listFiles().orEmpty().filter { it.isFile && it.name !in referencedPictogramFiles }.map { "$UNREFERENCED_IMAGES_PREFIX/pictograms/${it.name}" to it } +
            photosDirectory.listFiles().orEmpty().filter { it.isFile && it.name !in photoFiles }.map { "$UNREFERENCED_IMAGES_PREFIX/photos/${it.name}" to it }
        var skipped = 0
        for ((path, file) in material) {
            // Le secours ne doit jamais rendre la sauvegarde impossible : au-delà des bornes, ou
            // illisible (le stockage qui a abîmé la base peut abîmer sa copie), il reste sur l'appareil.
            // Lu avant d'ouvrir l'entrée : une entrée commencée ne se retire pas.
            val bytes = if (budget.canAdd(file.length())) {
                try {
                    file.inputStream().use { it.readAtMost(MAX_RECOVERY_FILE_BYTES) }
                } catch (exception: IOException) {
                    null
                } catch (error: OutOfMemoryError) {
                    // Une grosse copie sur une tablette modeste : laissée sur l'appareil, la sauvegarde se fait.
                    null
                }
            } else {
                null
            }
            if (bytes == null) {
                skipped++
                continue
            }
            zip.putEntry(path, budget, bytes.inputStream())
        }
        if (skipped > 0) Log.w(TAG, "$skipped recovery files left out of the backup (archive limits or unreadable)")
        // Les pictos non visés voyagent hors du manifeste, donc sans déclaration : leur crédit (invariant 5).
        if (material.any { (path, _) -> path.startsWith("$UNREFERENCED_IMAGES_PREFIX/pictograms/") }) {
            zip.putEntry("$UNREFERENCED_IMAGES_PREFIX/pictograms/LICENCE.txt", budget, ARASAAC_CREDIT.byteInputStream(Charsets.UTF_8))
        }
    }

    override suspend fun analyze(source: Uri): PendingRestore = withContext(Dispatchers.IO) {
        restoreFileLock.withLock {
            val fileName = displayName(source)
            try {
                copyToRestoreFile(source)
                PendingRestore(fileName, readArchive())
            } catch (throwable: Throwable) {
                discardFile()
                // Une archive hostile (trop grosse, trop imbriquée) se refuse, elle ne fait pas planter l'appli.
                when (throwable) {
                    is OutOfMemoryError -> refuse(RefusalReason.TooLarge, "Out of memory while reading")
                    is StackOverflowError -> refuse(RefusalReason.Unreadable, "Nested too deeply")
                    // Une entrée au flux compressé abîmé : le fichier est abîmé, pas la restauration.
                    is ZipException -> refuse(RefusalReason.Unreadable, "Damaged zip entry")
                    else -> throw throwable
                }
            }
        }
    }

    /** Toutes les bornes comptées en lisant, jamais sur ce que l'archive annonce. */
    private suspend fun readArchive(): BackupContent = openArchive().use { zip ->
        if (zip.size() > MAX_ENTRIES) refuse(RefusalReason.TooLarge, "${zip.size()} entries")
        var jsonBytes = 0L
        fun text(path: String): String {
            val entry = zip.getEntry(path) ?: refuse(RefusalReason.Unreadable, "Missing entry")
            val bytes = zip.getInputStream(entry).use { it.readBounded(MAX_ENTRY_BYTES) }
            jsonBytes += bytes.size
            if (jsonBytes > MAX_JSON_BYTES) refuse(RefusalReason.TooLarge, "More than $MAX_JSON_BYTES bytes of JSON")
            return bytes.toString(Charsets.UTF_8)
        }
        val manifest = readManifest(text(MANIFEST_PATH))
        val boards = ArrayList<BoardEntity>(manifest.boardPaths.size)
        val cells = ArrayList<CellEntity>()
        for (path in manifest.boardPaths) {
            val (board, boardCells) = readBoard(text(path))
            boards += board
            cells += boardCells
            if (cells.size > MAX_CELLS) refuse(RefusalReason.TooLarge, "More than $MAX_CELLS cells")
        }
        if (manifest.profiles == null) Log.i(TAG, "Backup from before profiles, read as a single profile")
        val content = backupContent(manifest, boards, cells, context.getString(R.string.profile_default_name))
        validate(content)
        // Les images aussi, avant la confirmation : un refus après elle viendrait trop tard.
        // Chaque image distincte une fois, avec la règle même de l'export.
        var imageBytes = 0L
        val images = content.cells.mapNotNull { it.pictogramId }.toSet().map { pictogramImagePath(it) to true } +
            content.cells.mapNotNull { it.photoFile }.toSet().map { photoImagePath(it) to false }
        for ((path, isPictogram) in images) {
            imageBytes += checkImage(zip, path, isPictogram)
            if (imageBytes > MAX_IMAGE_BYTES) refuse(RefusalReason.TooLarge, "More than $MAX_IMAGE_BYTES bytes of images")
        }
        Log.i(TAG, "Backup read: ${content.profiles.size} profiles, ${content.boards.size} boards, ${content.cells.size} cells")
        content
    }

    override suspend fun restore(pending: PendingRestore): Unit = withContext(NonCancellable + Dispatchers.IO) {
        restoreFileLock.withLock { restoreLocked(pending) }
    }

    private suspend fun restoreLocked(pending: PendingRestore) {
        try {
            // Les images d'abord : une transaction réussie ne vise jamais une image absente. Un échec
            // ici arrête tout avant la base ; les images écrites en trop partent au ménage du lancement.
            openArchive().use { zip ->
                var withoutImage = 0
                for (id in pending.content.cells.mapNotNull { it.pictogramId }.toSet()) {
                    if (pictograms.isOnDevice(id)) continue
                    val entry = zip.getEntry(pictogramImagePath(id))
                    if (entry == null) {
                        withoutImage++
                        continue
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBounded(MAX_PICTOGRAM_BYTES.toLong()) }
                    if (!pictograms.restore(id, bytes)) throw IOException("Pictogram $id could not be restored")
                }
                for (name in pending.content.cells.mapNotNull { it.photoFile }.toSet()) {
                    // Déjà là et lisible (même nom unique, donc même photo) : ni réécrite ni recompressée.
                    // Abîmée sur l'appareil : la copie de l'archive est la seule qui reste, on la reprend.
                    val present = photoFile(name)
                    if (present != null && isReadablePhoto(present)) {
                        present.setLastModified(System.currentTimeMillis())
                        continue
                    }
                    val entry = zip.getEntry(photoImagePath(name))
                    if (entry == null) {
                        withoutImage++
                        continue
                    }
                    // Bornée à l'analyse (même fichier privé, relu ici). Illisible au décodage complet : la
                    // case revient sans sa photo plutôt que de refuser toute la sauvegarde.
                    if (!photos.restore(name) { zip.getInputStream(entry) }) withoutImage++
                }
                if (withoutImage > 0) Log.w(TAG, "$withoutImage references restored without an image")
            }
        } catch (error: OutOfMemoryError) {
            // Avant la base : un échec ordinaire, affiché, et les grilles n'ont pas changé.
            discardFile()
            throw IOException("Out of memory while restoring images")
        } catch (throwable: Throwable) {
            discardFile()
            throw throwable
        }
        // Hors du `catch` du manque de mémoire : après la transaction, ce ne serait plus « rien n'a changé ».
        try {
            repository.replaceAll(pending.content)
        } finally {
            discardFile()
        }
    }

    override suspend fun discard(): Unit = withContext(Dispatchers.IO) { restoreFileLock.withLock { discardFile() } }

    // NonCancellable : un écran détruit ne laisse pas un document vide pris pour une sauvegarde.
    override suspend fun deleteDocument(document: Uri) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (!DocumentsContract.deleteDocument(context.contentResolver, document)) Log.w(TAG, "Backup document not deleted")
            } catch (exception: Exception) {
                // Fournisseur sans suppression, document déjà parti : on aura essayé.
                Log.w(TAG, "Backup document could not be deleted: ${exception.javaClass.simpleName}")
            }
        }
    }

    private fun discardFile() {
        if (restoreFile.exists() && !restoreFile.delete()) Log.w(TAG, "Restore file could not be deleted")
    }

    private fun displayName(source: Uri): String? = try {
        context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Backup name unknown: ${exception.javaClass.simpleName}")
        null
    }

    private fun copyToRestoreFile(source: Uri) {
        val input = context.contentResolver.openInputStream(source) ?: throw FileNotFoundException("No input stream")
        input.use {
            FileOutputStream(restoreFile).use { output -> it.copyBounded(output, MAX_ARCHIVE_BYTES) }
        }
    }

    private fun openArchive(): ZipFile = try {
        ZipFile(restoreFile)
    } catch (exception: ZipException) {
        // Zip abîmé, ou nom d'entrée interdit (Android 14 et plus refusent `..` et `/` en tête).
        refuse(RefusalReason.Unreadable, "Not a zip: ${exception.javaClass.simpleName}")
    }

    /** Une image de l'archive. Rend sa taille (0 si l'archive ne la porte pas : la case garde sa référence sans image). */
    private suspend fun checkImage(zip: ZipFile, path: String, isPictogram: Boolean): Long {
        val entry = zip.getEntry(path) ?: return 0
        val bytes = zip.getInputStream(entry).use { it.readBounded(if (isPictogram) MAX_PICTOGRAM_BYTES.toLong() else MAX_ENTRY_BYTES) }
        imageRefusal(bytes, isPictogram)?.let { reason -> refuse(reason, if (isPictogram) "Pictogram image refused" else "Photo refused") }
        return bytes.size.toLong()
    }

    private fun InputStream.readBounded(maxBytes: Long): ByteArray =
        readAtMost(maxBytes) ?: refuse(RefusalReason.TooLarge, "More than $maxBytes bytes")

    private fun InputStream.copyBounded(output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) refuse(RefusalReason.TooLarge, "More than $maxBytes bytes")
            output.write(buffer, 0, read)
        }
    }
}

/** Crédit des pictos ARASAAC sans adresse (aucune URL dans le code, invariant 1). */
private const val ARASAAC_CREDIT = "Pictogrammes : Sergio Palao. Origine : ARASAAC. Licence : CC BY-NC-SA 4.0. Propriété : Gouvernement d'Aragon (Espagne).\n"

/**
 * Les bornes de la restauration, tenues à l'écriture : une sauvegarde que Papotons refuserait de
 * relire ne s'écrit pas. Compte les octets réellement écrits (compressés, en-têtes compris).
 */
private class ArchiveBudget(private val written: CountingOutputStream) {
    private var entries = 0
    private var reservedBytes = 0L
    private var reservedEntries = 0

    /** Garde la place de ce qui s'écrira plus tard (les grilles et le manifeste, après le secours). */
    fun reserve(bytes: Long, entryCount: Int) {
        reservedBytes = bytes + entryCount * ENTRY_OVERHEAD_BYTES
        reservedEntries = entryCount
    }

    /**
     * Une entrée de [size] octets tiendrait, en-têtes et place réservée compris (une image ne se
     * compresse pas). Seulement le total : la restauration ne lit que les entrées du manifeste, bornées
     * par le validateur.
     */
    fun canAdd(size: Long) = entries + 1 + reservedEntries <= MAX_ENTRIES &&
        // Un fichier incompressible grossit un peu en deflate (quelques octets par bloc) : marge d'un pour mille.
        written.count + size + size / DEFLATE_GROWTH_DIVISOR + ENTRY_OVERHEAD_BYTES + reservedBytes <= WRITABLE_ARCHIVE_BYTES

    fun count() {
        entries++
        if (entries > MAX_ENTRIES || written.count > WRITABLE_ARCHIVE_BYTES) {
            refuse(RefusalReason.TooLarge, "Backup over the archive limits")
        }
    }
}

/** Octets réellement écrits dans le document : c'est la taille du fichier que la restauration bornera. */
private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(byte: Int) {
        out.write(byte)
        count++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count += length
    }
}

/** `null` au-delà de [maxBytes] : rien n'est gardé en mémoire au-delà de la borne. */
private fun InputStream.readAtMost(maxBytes: Long): ByteArray? {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return bytes.toByteArray()
        total += read
        if (total > maxBytes) return null
        bytes.write(buffer, 0, read)
    }
}

private fun ZipOutputStream.putEntry(path: String, budget: ArchiveBudget, input: InputStream) {
    putNextEntry(ZipEntry(path))
    input.use { it.copyTo(this) }
    closeEntry()
    budget.count()
}
