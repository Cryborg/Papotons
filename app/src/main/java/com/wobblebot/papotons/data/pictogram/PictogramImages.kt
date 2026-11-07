package com.wobblebot.papotons.data.pictogram

import android.content.Context
import android.graphics.Bitmap
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PictogramImages"
private const val DIRECTORY = "pictograms"
private const val EXTENSION = ".png"

/**
 * Les pictos sont livrés en 300 px et s'affichent autour de 150 px sur les tablettes visées :
 * décodés à moitié, ils pèsent quatre fois moins en mémoire (ARGB_8888, transparence oblige).
 */
private const val SAMPLE_SIZE = 2

/** Le dossier des pictos téléchargés depuis ARASAAC, dans `files/` (sauvegardé avec la base). */
private const val DOWNLOADED_DIRECTORY = "pictograms"
private const val PENDING_PREFIX = ".pending-"
private const val MAX_DOWNLOADED_SIDE_PX = 1024

/** Ce que l'édition demande aux pictos : décoder une vignette, garder un picto choisi. Couture de test. */
interface PictogramStore {
    /** `null` si [bytes] n'est pas une image lisible. */
    suspend fun decode(bytes: ByteArray): ImageBitmap?

    /** Écrit le picto sur l'appareil et le publie. Faux si l'image est illisible ou l'écriture impossible. */
    suspend fun store(pictogramId: Long, bytes: ByteArray): Boolean
}

/**
 * Les pictos embarqués (`assets/pictograms/<id>.png`), décodés une fois au démarrage, pendant le
 * carton du studio, puis publiés d'un bloc : une seule recomposition des cases, pas une par image.
 * Les pictos téléchargés (`files/pictograms/<id>.png`) se décodent à la demande, quand une case les
 * affiche : seuls ceux qui servent coûtent de la mémoire. Toute publication passe par [mutex] et
 * fusionne : aucune ne peut en effacer une autre.
 */
@Singleton
class PictogramImages @Inject constructor(@ApplicationContext private val context: Context) : PictogramStore {
    private var images by mutableStateOf<Map<Long, ImageBitmap>>(emptyMap())
    private val mutex = Mutex()
    private val downloadedDirectory = File(context.filesDir, DOWNLOADED_DIRECTORY)

    /**
     * Les ids embarqués : « embarqué » se décide par la liste des assets, pas par « déjà décodé ».
     * Attendu, jamais lu vide : une case affichée sous le carton du studio, avant la fin du
     * listage, retrouve son picto téléchargé dès que la liste est connue.
     */
    private val bundledIds = CompletableDeferred<Set<Long>>()

    /** `null` tant que les pictos ne sont pas décodés, ou pour un picto absent ou illisible. */
    fun image(pictogramId: Long): ImageBitmap? = images[pictogramId]

    /** Décode un picto téléchargé s'il ne l'est pas encore. Rien tant que les embarqués ne sont pas listés. */
    suspend fun ensureLoaded(pictogramId: Long) {
        if (pictogramId in bundledIds.await() || images.containsKey(pictogramId)) return
        val image = withContext(Dispatchers.IO) {
            val file = File(downloadedDirectory, "$pictogramId$EXTENSION")
            if (file.isFile) {
                decodeFile(file)
            } else {
                // Ni embarqué ni téléchargé : la case a perdu son image (restauration partielle, import).
                Log.w(TAG, "Pictogram $pictogramId is neither bundled nor downloaded")
                null
            }
        } ?: return
        publish(mapOf(pictogramId to image))
    }

    /** Hors du thread principal : jusqu'à trente vignettes par recherche, sur une tablette modeste. */
    override suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        decodeBounded { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
    }

    /**
     * Décodé d'abord (jamais une page d'erreur enregistrée en `.png`), puis écrit dans un temporaire
     * au nom unique, synchronisé, renommé : jamais d'image à moitié écrite sous son vrai nom.
     */
    override suspend fun store(pictogramId: Long, bytes: ByteArray): Boolean {
        if (pictogramId in bundledIds.await()) return true
        val image = decode(bytes) ?: return false
        val isWritten = withContext(Dispatchers.IO) {
            val pending = File(downloadedDirectory, "$PENDING_PREFIX${UUID.randomUUID()}")
            try {
                downloadedDirectory.mkdirs()
                FileOutputStream(pending).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                pending.renameTo(File(downloadedDirectory, "$pictogramId$EXTENSION")).also { isRenamed ->
                    if (isRenamed) syncDirectory() else Log.e(TAG, "Pictogram $pictogramId could not be renamed into place")
                }
            } catch (exception: IOException) {
                Log.e(TAG, "Pictogram $pictogramId could not be written", exception)
                false
            } finally {
                if (pending.exists()) pending.delete()
            }
        }
        if (isWritten) publish(mapOf(pictogramId to image))
        return isWritten
    }

    /**
     * Le renommage n'est durable qu'une fois le dossier synchronisé : sans cela, après une coupure
     * de courant, la base (synchronisée) pourrait viser un fichier dont l'entrée n'existe plus.
     */
    private fun syncDirectory() {
        try {
            val descriptor = Os.open(downloadedDirectory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        } catch (exception: ErrnoException) {
            Log.w(TAG, "Pictogram directory could not be synced", exception)
        }
    }

    private suspend fun publish(decoded: Map<Long, ImageBitmap>) {
        mutex.withLock { images = images + decoded }
    }

    private fun decodeFile(file: File): ImageBitmap? = decodeBounded { options -> BitmapFactory.decodeFile(file.path, options) }

    /**
     * Dimensions lues d'abord : un PNG de quelques Ko peut annoncer des dizaines de milliers de
     * pixels de côté et épuiser la mémoire au décodage. Les pictos d'ARASAAC font 300 px.
     */
    private fun decodeBounded(decode: (BitmapFactory.Options) -> Bitmap?): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > MAX_DOWNLOADED_SIDE_PX) {
            Log.w(TAG, "Image refused: ${bounds.outWidth} x ${bounds.outHeight}")
            null
        } else {
            decode(BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE })?.asImageBitmap()
        }
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Image could not be decoded", exception)
        null
    }

    suspend fun loadBundled() {
        try {
            loadBundledImages()
        } finally {
            // Quoi qu'il arrive (même une erreur imprévue) : `store` et `ensureLoaded` attendent cette liste.
            bundledIds.complete(emptySet())
        }
    }

    private suspend fun loadBundledImages() = withContext(Dispatchers.IO) {
        val fileNames = try {
            context.assets.list(DIRECTORY).orEmpty()
        } catch (exception: IOException) {
            Log.e(TAG, "Bundled pictograms could not be listed", exception)
            bundledIds.complete(emptySet())
            return@withContext
        }
        // Avant que les écritures ne puissent commencer (elles attendent la liste) : le ménage ne
        // peut pas emporter le temporaire d'une écriture en cours.
        downloadedDirectory.listFiles { file -> file.name.startsWith(PENDING_PREFIX) }.orEmpty().forEach { it.delete() }
        val decoded = HashMap<Long, ImageBitmap>(fileNames.size)
        val options = BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE }
        try {
            for (fileName in fileNames) {
                val pictogramId = fileName.removeSuffix(EXTENSION).toLongOrNull()
                if (pictogramId == null) {
                    Log.w(TAG, "Unexpected file $fileName among the pictograms")
                    continue
                }
                // Une image décorative ne doit jamais empêcher de parler : tout échec est journalisé
                // et la case garde son libellé. ARGB_8888 plutôt que HARDWARE, en panne sur Android 8.
                try {
                    val bitmap = context.assets.open("$DIRECTORY/$fileName").use { BitmapFactory.decodeStream(it, null, options) }
                    if (bitmap == null) {
                        Log.w(TAG, "Pictogram $pictogramId is not a readable image")
                    } else {
                        decoded[pictogramId] = bitmap.asImageBitmap()
                    }
                } catch (exception: IOException) {
                    Log.w(TAG, "Pictogram $pictogramId could not be read", exception)
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Pictogram $pictogramId could not be decoded", exception)
                }
            }
        } catch (error: OutOfMemoryError) {
            // Inutile d'insister : on garde ce qui est déjà décodé.
            Log.e(TAG, "Out of memory after ${decoded.size} pictograms", error)
        } finally {
            // Publié quoi qu'il arrive : un échec en route ne fait pas perdre les images déjà lues.
            // Fusionné : un picto choisi pendant le carton n'est pas effacé.
            publish(decoded)
            bundledIds.complete(fileNames.mapNotNull { it.removeSuffix(EXTENSION).toLongOrNull() }.toSet())
        }
        // Aucun fichier (APK mal empaqueté) est aussi un échec : jamais un « 0 sur 0 » en simple info.
        if (decoded.isEmpty() || decoded.size < fileNames.size) {
            Log.e(TAG, "Only ${decoded.size} of ${fileNames.size} bundled pictograms decoded")
        } else {
            Log.i(TAG, "${decoded.size} bundled pictograms decoded")
        }
    }
}
