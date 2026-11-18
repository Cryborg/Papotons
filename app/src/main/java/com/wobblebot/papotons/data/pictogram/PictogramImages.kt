package com.wobblebot.papotons.data.pictogram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wobblebot.papotons.data.plusEvictingOldest
import com.wobblebot.papotons.data.syncDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PictogramImages"
private const val DIRECTORY = "pictograms"
/** L'extension d'un fichier de picto, embarqué ou téléchargé : l'export la relit pour reconnaître les pictos visés. */
const val PICTOGRAM_FILE_EXTENSION = ".png"

/**
 * Les pictos sont livrés en 300 px et s'affichent autour de 150 px sur les tablettes visées :
 * décodés à moitié, ils pèsent quatre fois moins en mémoire (ARGB_8888, transparence oblige).
 */
private const val SAMPLE_SIZE = 2

/** Le dossier des pictos téléchargés depuis ARASAAC, `<id>.png` dans `files/` (sauvegardé avec la base). */
const val DOWNLOADED_PICTOGRAMS_DIRECTORY = "pictograms"
private const val PENDING_PREFIX = ".pending-"
/** Côté maximal d'un picto téléchargé (ou restauré) : au-delà, refusé. */
const val MAX_DOWNLOADED_SIDE_PX = 1024

/** Poids maximal d'un picto téléchargé ou restauré ; un picto ARASAAC en pèse quelques dizaines de Ko. */
const val MAX_PICTOGRAM_BYTES = 1024 * 1024

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
 * affiche : seuls ceux qui servent coûtent de la mémoire, et ils sont bornés comme les photos (une
 * grosse restauration en vise des centaines). Toute publication passe par [mutex] et fusionne :
 * aucune ne peut en effacer une autre.
 */
@Singleton
class PictogramImages @Inject constructor(@ApplicationContext private val context: Context) : PictogramStore {
    private var bundledImages by mutableStateOf<Map<Long, ImageBitmap>>(emptyMap())
    private var downloadedImages by mutableStateOf<Map<Long, ImageBitmap>>(emptyMap())
    private val mutex = Mutex()
    private val downloadedDirectory = File(context.filesDir, DOWNLOADED_PICTOGRAMS_DIRECTORY)

    /**
     * Les ids embarqués : « embarqué » se décide par la liste des assets, pas par « déjà décodé ».
     * Attendu, jamais lu vide : une case affichée sous le carton du studio, avant la fin du
     * listage, retrouve son picto téléchargé dès que la liste est connue.
     */
    private val bundledIds = CompletableDeferred<Set<Long>>()

    /** `null` tant que les pictos ne sont pas décodés, ou pour un picto absent ou illisible. */
    fun image(pictogramId: Long): ImageBitmap? = bundledImages[pictogramId] ?: downloadedImages[pictogramId]

    /** Décode un picto téléchargé s'il ne l'est pas encore. Rien tant que les embarqués ne sont pas listés. */
    suspend fun ensureLoaded(pictogramId: Long) {
        if (pictogramId in bundledIds.await() || downloadedImages.containsKey(pictogramId)) return
        val image = withContext(Dispatchers.IO) {
            val file = File(downloadedDirectory, "$pictogramId$PICTOGRAM_FILE_EXTENSION")
            if (file.isFile) {
                // Une image décorative ne doit jamais empêcher de parler : sans mémoire, la case reste sans picto.
                try {
                    decodeFile(file)
                } catch (error: OutOfMemoryError) {
                    Log.w(TAG, "No memory to decode pictogram $pictogramId")
                    null
                }
            } else {
                // Ni embarqué ni téléchargé : la case a perdu son image (restauration partielle, import).
                Log.w(TAG, "Pictogram $pictogramId is neither bundled nor downloaded")
                null
            }
        } ?: return
        publishDownloaded(pictogramId, image)
    }

    /**
     * Vrai si le picto est déjà sur l'appareil et lisible, embarqué ou téléchargé : une restauration
     * ne le réécrit pas (une archive ne remplace pas l'image de cases existantes). Un téléchargé
     * abîmé ne compte pas : la copie de l'archive est la seule bonne qui reste, comme pour une photo.
     * Un téléchargé gardé est daté de maintenant, comme s'il venait d'être écrit (le ménage du
     * lancement ne vise que les anciens).
     */
    suspend fun isOnDevice(pictogramId: Long): Boolean {
        if (pictogramId in bundledIds.await()) return true
        return withContext(Dispatchers.IO) {
            val file = File(downloadedDirectory, "$pictogramId$PICTOGRAM_FILE_EXTENSION")
            // Sans la mémoire pour le vérifier, il est gardé : la restauration ne bute pas dessus.
            val isReadable = file.isFile && try {
                decodeFile(file) != null
            } catch (error: OutOfMemoryError) {
                true
            }
            isReadable.also { if (it) file.setLastModified(System.currentTimeMillis()) }
        }
    }

    /** L'image d'un picto pour une sauvegarde ; `null` s'il n'est pas sur l'appareil. À fermer par l'appelant. */
    suspend fun open(pictogramId: Long): InputStream? {
        if (pictogramId in bundledIds.await()) return withContext(Dispatchers.IO) { context.assets.open("$DIRECTORY/$pictogramId$PICTOGRAM_FILE_EXTENSION") }
        return withContext(Dispatchers.IO) { File(downloadedDirectory, "$pictogramId$PICTOGRAM_FILE_EXTENSION").takeIf { it.isFile }?.inputStream() }
    }

    /** Hors du thread principal : jusqu'à trente vignettes par recherche, sur une tablette modeste. */
    override suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        decodeBounded { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
    }

    /**
     * Décodé d'abord (jamais une page d'erreur enregistrée en `.png`), puis écrit dans un temporaire
     * au nom unique, synchronisé, renommé : jamais d'image à moitié écrite sous son vrai nom.
     */
    override suspend fun store(pictogramId: Long, bytes: ByteArray): Boolean = write(pictogramId, bytes, publish = true)

    /**
     * Un picto d'une sauvegarde : écrit comme [store], mais pas gardé décodé (une restauration en
     * écrit des centaines ; chacun se décode quand une case l'affiche).
     */
    suspend fun restore(pictogramId: Long, bytes: ByteArray): Boolean = write(pictogramId, bytes, publish = false)

    private suspend fun write(pictogramId: Long, bytes: ByteArray, publish: Boolean): Boolean {
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
                pending.renameTo(File(downloadedDirectory, "$pictogramId$PICTOGRAM_FILE_EXTENSION")).also { isRenamed ->
                    if (isRenamed) syncDirectory(downloadedDirectory, TAG) else Log.e(TAG, "Pictogram $pictogramId could not be renamed into place")
                }
            } catch (exception: IOException) {
                Log.e(TAG, "Pictogram $pictogramId could not be written", exception)
                false
            } finally {
                if (pending.exists()) pending.delete()
            }
        }
        if (isWritten && publish) publishDownloaded(pictogramId, image)
        return isWritten
    }

    private suspend fun publishBundled(decoded: Map<Long, ImageBitmap>) {
        mutex.withLock { bundledImages = bundledImages + decoded }
    }

    private suspend fun publishDownloaded(pictogramId: Long, image: ImageBitmap) {
        mutex.withLock { downloadedImages = downloadedImages.plusEvictingOldest(pictogramId, image) }
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
                val pictogramId = fileName.removeSuffix(PICTOGRAM_FILE_EXTENSION).toLongOrNull()
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
            publishBundled(decoded)
            bundledIds.complete(fileNames.mapNotNull { it.removeSuffix(PICTOGRAM_FILE_EXTENSION).toLongOrNull() }.toSet())
        }
        // Aucun fichier (APK mal empaqueté) est aussi un échec : jamais un « 0 sur 0 » en simple info.
        if (decoded.isEmpty() || decoded.size < fileNames.size) {
            Log.e(TAG, "Only ${decoded.size} of ${fileNames.size} bundled pictograms decoded")
        } else {
            Log.i(TAG, "${decoded.size} bundled pictograms decoded")
        }
    }
}
