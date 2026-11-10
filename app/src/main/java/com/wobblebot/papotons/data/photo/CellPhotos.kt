package com.wobblebot.papotons.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CellPhotos"
/** Dossier de `files/` des photos validées, `<uuid>.jpg`. */
const val CELL_PHOTOS_DIRECTORY = "photos"

/** Le seul nom qu'une photo validée peut porter (`<uuid>.jpg`, comme [CellPhotos.prepare] l'écrit). */
val CELL_PHOTO_NAME = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jpg""")
private const val PENDING_DIRECTORY = "photos-pending"
private const val CAMERA_DIRECTORY = "camera"
private const val CAMERA_FILE = "capture.jpg"
private const val EXTENSION = ".jpg"

/** Côté de la photo gardée : carrée, environ 40 Ko en JPEG (la sauvegarde Android plafonne à 25 Mo). */
private const val STORED_SIDE_PX = 480
private const val JPEG_QUALITY = 80

/** Divise le côté gardé à l'affichage (240 px) : une case ou un mot de la phrase n'en demandent pas plus. */
private const val DISPLAY_SAMPLE_SIZE = 2

/**
 * Photos gardées décodées (environ 115 Ko chacune) : au-delà, la plus anciennement décodée sort, et
 * se redécode si une case l'affiche encore. Plus que le plus grand écran (accueil 7 × 8 et la
 * phrase), pour que les photos d'un même écran ne se chassent pas entre elles.
 */
private const val MAX_DECODED_PHOTOS = 96

/**
 * Ce que l'édition demande aux photos. Couture de test : le ViewModel se teste sur la JVM.
 * Les noms sont uniques (`<uuid>.jpg`) : une photo n'appartient qu'à une case.
 */
interface PhotoStore {
    /** Une photo prête mais pas encore validée (aperçu possible), ou `null` si l'image est inutilisable. */
    suspend fun prepare(source: Uri): String?

    /** Valide une photo préparée. Idempotent : déjà validée, c'est un succès. */
    suspend fun commit(name: String): Boolean

    /** Efface la photo, préparée ou validée. Une photo retirée est vraiment retirée (vie privée). */
    suspend fun delete(name: String)

    /** Le fichier où l'appli photo du système écrit, exposé par le `FileProvider`, vidé d'avance. */
    fun freshCameraCapture(): Uri

    fun discardCameraCapture()
}

/**
 * Les photos des cases : copiées dans l'appli (jamais visées par leur adresse d'origine, qui
 * disparaîtrait avec la galerie), recadrées en carré, réduites, orientées. L'appli ne les envoie
 * nulle part (invariant 1) : seules la sauvegarde Android et un fichier `.obz` rangé par l'adulte
 * les emportent. Les journaux ne disent ni leur nom ni leur adresse d'origine.
 */
@Singleton
class CellPhotos @Inject constructor(@ApplicationContext private val context: Context) : PhotoStore {
    private val photosDirectory = File(context.filesDir, CELL_PHOTOS_DIRECTORY)
    // Hors sauvegarde, sur la partition des photos : le déplacement à la validation est un simple renommage.
    private val pendingDirectory = File(context.noBackupFilesDir, PENDING_DIRECTORY)
    private val cameraFile = File(File(context.cacheDir, CAMERA_DIRECTORY), CAMERA_FILE)
    private var images by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
    private val mutex = Mutex()
    private val missingReported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Illisibles pour de bon : on ne les redécode pas à chaque affichage. */
    private val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** `null` tant que la photo n'est pas décodée, ou si elle est absente. */
    fun image(name: String): ImageBitmap? = images[name]

    /** Décode à la demande la photo qu'une case affiche (validée, ou préparée pour l'aperçu). */
    suspend fun ensureLoaded(name: String) {
        if (name in unreadable) return
        if (images.containsKey(name)) return
        val image = withContext(Dispatchers.IO) {
            // La préparée d'abord : `commit` la renomme vers les validées, et lue dans l'autre ordre, un
            // renommage entre les deux tests la ferait passer pour absente, sans nouvel essai.
            val file = listOf(File(pendingDirectory, name), File(photosDirectory, name)).firstOrNull { it.isFile }
            if (file == null) {
                if (missingReported.add(name)) Log.w(TAG, "A cell photo is missing")
                null
            } else {
                // Illisible : signalé une fois, pas redécodé à chaque affichage. Une absence, elle, peut
                // n'être que passagère (renommage en cours) : on réessaiera.
                // Un fichier parti pendant le décodage (validé, donc renommé) n'est pas illisible.
                // Sans mémoire, la case reste sans photo pour cette fois, sans être marquée illisible.
                val decoded = try {
                    decodeForDisplay(file)
                } catch (error: OutOfMemoryError) {
                    Log.w(TAG, "No memory to decode a cell photo")
                    return@withContext null
                }
                decoded.also { if (it == null && file.isFile && unreadable.add(name)) Log.w(TAG, "A cell photo is unreadable") }
            }
        } ?: return
        mutex.withLock {
            // Ordre d'arrivée : les plus anciennes sortent au-delà de la borne.
            val updated = LinkedHashMap(images)
            updated[name] = image
            while (updated.size > MAX_DECODED_PHOTOS) updated.remove(updated.keys.first())
            images = updated
        }
    }

    /** Au démarrage : les photos préparées et jamais validées, et la prise de vue, ne servent plus. */
    suspend fun cleanUp() = withContext(Dispatchers.IO) {
        pendingDirectory.listFiles().orEmpty().forEach { it.delete() }
        cameraFile.delete()
    }

    // IO : le ContentResolver peut lire une image hébergée en ligne et bloquer longtemps.
    override suspend fun prepare(source: Uri): String? = withContext(Dispatchers.IO) {
        val name = "${UUID.randomUUID()}$EXTENSION"
        val target = File(pendingDirectory, name)
        try {
            val square = readSquare { context.contentResolver.openInputStream(source) } ?: return@withContext null
            pendingDirectory.mkdirs()
            val isCompressed = FileOutputStream(target).use { output ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output).also { output.fd.sync() }
            }
            if (isCompressed) name else reportImportFailure(IOException("compression failed"), target)
        } catch (exception: IOException) {
            reportImportFailure(exception, target)
        } catch (exception: RuntimeException) {
            // SecurityException (accès retiré), IllegalArgumentException, ou un EXIF malformé.
            reportImportFailure(exception, target)
        } catch (error: OutOfMemoryError) {
            reportImportFailure(error, target)
        }
    }

    /**
     * Une photo d'une sauvegarde, réencodée comme une photo choisie (480 px, JPEG : ni métadonnées
     * géantes ni image hostile) et validée sous son nom [name] (déjà contrôlé). [open] rend un flux
     * neuf à chaque appel. Faux si l'image est illisible ; une écriture impossible (disque plein)
     * lève une [IOException] : ce n'est pas la photo qui est en cause.
     */
    suspend fun restore(name: String, open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        val pending = File(pendingDirectory, name)
        try {
            val square = readSquare(open) ?: return@withContext false
            pendingDirectory.mkdirs()
            photosDirectory.mkdirs()
            val isCompressed = FileOutputStream(pending).use { output ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output).also { output.fd.sync() }
            }
            if (!isCompressed || !pending.renameTo(File(photosDirectory, name))) throw IOException("Restored photo not written")
            syncDirectory()
            // Une version d'avant, décodée ou jugée illisible, ne doit pas masquer celle-ci.
            unreadable.remove(name)
            missingReported.remove(name)
            mutex.withLock { images = images - name }
            true
        } catch (exception: IOException) {
            reportImportFailure(exception, pending)
            throw exception
        } catch (exception: RuntimeException) {
            reportImportFailure(exception, pending)
            false
        } catch (error: OutOfMemoryError) {
            reportImportFailure(error, pending)
            false
        }
    }

    override suspend fun commit(name: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(photosDirectory, name)
        if (target.isFile) return@withContext true
        photosDirectory.mkdirs()
        val isMoved = File(pendingDirectory, name).renameTo(target)
        if (isMoved) syncDirectory() else Log.e(TAG, "A prepared photo could not be committed")
        isMoved
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) {
            for (file in listOf(File(pendingDirectory, name), File(photosDirectory, name))) {
                if (file.exists() && !file.delete()) Log.w(TAG, "A photo could not be deleted")
            }
        }
        mutex.withLock { images = images - name }
    }

    override fun freshCameraCapture(): Uri {
        cameraFile.parentFile?.mkdirs()
        // Certaines applis photo rendent « OK » sans écrire : jamais la photo d'avant.
        cameraFile.delete()
        return FileProvider.getUriForFile(context, "${context.packageName}$CAMERA_AUTHORITY_SUFFIX", cameraFile)
    }

    override fun discardCameraCapture() {
        cameraFile.delete()
    }

    /**
     * Dimensions d'abord (une photo de 12 Mpx ne se décode pas en entier), sous-échantillonnage,
     * orientation EXIF (que `BitmapFactory` n'applique jamais), recadrage carré au centre, fond
     * blanc sous une image transparente, réduction à [STORED_SIDE_PX].
     */
    private fun readSquare(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds : decodeStream rend toujours null, seules les dimensions comptent.
        val boundsStream = open() ?: return unreadable("no stream")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val smallestSide = minOf(bounds.outWidth, bounds.outHeight)
        if (smallestSide <= 0) return unreadable("not an image")
        var sampleSize = 1
        while (smallestSide / (sampleSize * 2) >= STORED_SIDE_PX) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return unreadable("not decodable")
        val orientation = open()?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val oriented = oriented(decoded, orientation)
        val side = minOf(oriented.width, oriented.height)
        val cropped = Bitmap.createBitmap(oriented, (oriented.width - side) / 2, (oriented.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, STORED_SIDE_PX, STORED_SIDE_PX, true)
        val square = Bitmap.createBitmap(STORED_SIDE_PX, STORED_SIDE_PX, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        return square
    }

    private fun unreadable(reason: String): Bitmap? {
        Log.w(TAG, "Photo import refused: $reason")
        return null
    }

    private fun decodeForDisplay(file: File): ImageBitmap? = try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = DISPLAY_SAMPLE_SIZE
            // JPEG sans transparence : deux fois moins de mémoire qu'en ARGB.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    } catch (exception: RuntimeException) {
        Log.w(TAG, "A cell photo could not be decoded: ${exception.javaClass.simpleName}")
        null
    }

    /** Le renommage n'est durable qu'une fois le dossier synchronisé (coupure de courant). */
    private fun syncDirectory() {
        try {
            val descriptor = Os.open(photosDirectory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        } catch (exception: ErrnoException) {
            Log.w(TAG, "Photo directory could not be synced: ${exception.javaClass.simpleName}")
        }
    }

    /**
     * La classe de l'erreur seulement : son message contient souvent le nom de la photo (« IMG_Mamie »).
     * Un fichier à moitié écrit est effacé tout de suite : une photo d'enfant ne reste pas en clair.
     */
    private fun reportImportFailure(throwable: Throwable, partial: File): String? {
        Log.e(TAG, "Photo import failed: ${throwable.javaClass.simpleName}")
        partial.delete()
        return null
    }
}

/** L'autorité du `FileProvider` de la prise de vue (manifeste : `${applicationId}.photos`). */
private const val CAMERA_AUTHORITY_SUFFIX = ".photos"

/** Orientation EXIF appliquée, miroirs compris. */
internal fun oriented(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
