package com.wobblebot.papotons.data.pictogram

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

/**
 * Les pictos embarqués (`assets/pictograms/<id>.png`), décodés une fois au démarrage, pendant le
 * carton du studio, puis publiés d'un bloc : une seule recomposition des cases, pas une par image.
 */
@Singleton
class PictogramImages @Inject constructor(@ApplicationContext private val context: Context) {
    private var images by mutableStateOf<Map<Long, ImageBitmap>>(emptyMap())

    /** `null` tant que les pictos ne sont pas décodés, ou pour un picto absent ou illisible. */
    fun image(pictogramId: Long): ImageBitmap? = images[pictogramId]

    suspend fun loadBundled() = withContext(Dispatchers.IO) {
        val fileNames = try {
            context.assets.list(DIRECTORY).orEmpty()
        } catch (exception: IOException) {
            Log.e(TAG, "Bundled pictograms could not be listed", exception)
            return@withContext
        }
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
            images = decoded
        }
        // Aucun fichier (APK mal empaqueté) est aussi un échec : jamais un « 0 sur 0 » en simple info.
        if (decoded.isEmpty() || decoded.size < fileNames.size) {
            Log.e(TAG, "Only ${decoded.size} of ${fileNames.size} bundled pictograms decoded")
        } else {
            Log.i(TAG, "${decoded.size} bundled pictograms decoded")
        }
    }
}
