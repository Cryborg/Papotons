package com.wobblebot.papotons.data.pictogram

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.data.board.CoreVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val UNKNOWN_PICTOGRAM_ID = -1L

/**
 * Invariant 2 : chaque picto du vocabulaire de base se décode depuis l'APK, sans réseau ; un picto
 * téléchargé est gardé sur l'appareil (écrit, publié, relu au lancement suivant).
 */
@RunWith(AndroidJUnit4::class)
class PictogramImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = 987_654_321L
    private val stored = File(context.filesDir, "pictograms/$testId.png")

    @After
    fun cleanUp() {
        stored.delete()
    }

    private fun pngBytes() = context.assets.open("pictograms/6632.png").use { it.readBytes() }

    @Test
    fun everyCoreVocabularyPictogramDecodesFromTheApk() {
        val images = PictogramImages(context)

        runBlocking { images.loadBundled() }

        for (cell in CoreVocabulary.build { "label$it" }.cells) {
            val pictogramId = checkNotNull(cell.pictogramId)
            val image = images.image(pictogramId)
            assertTrue("pictogram $pictogramId", image != null && image.width > 0 && image.height > 0)
        }
        assertNull(images.image(UNKNOWN_PICTOGRAM_ID))
    }

    @Test
    fun aBundledPictogramIsNeverWrittenAgainAndLeftoversAreCleaned() = runBlocking {
        val leftover = File(context.filesDir, "pictograms/.pending-test").apply {
            parentFile?.mkdirs()
            writeText("interrupted")
        }
        val images = PictogramImages(context)
        images.loadBundled()

        assertFalse(leftover.exists())
        assertTrue(images.store(6632, pngBytes()))
        assertFalse(File(context.filesDir, "pictograms/6632.png").exists())
    }

    @Test
    fun aStoredPictogramIsShownAndFoundAgainAfterARestart() = runBlocking {
        val images = PictogramImages(context)
        images.loadBundled()

        assertTrue(images.store(testId, pngBytes()))
        assertTrue(stored.isFile)
        assertNotNull(images.image(testId))

        // Lancement suivant : la case le demande sous le carton, AVANT la fin du listage des
        // embarqués ; il s'affiche dès que la liste est connue.
        val afterRestart = PictogramImages(context)
        val request = async(Dispatchers.Default) { afterRestart.ensureLoaded(testId) }
        assertNull(afterRestart.image(testId))
        afterRestart.loadBundled()
        request.await()
        assertNotNull(afterRestart.image(testId))
    }

    @Test
    fun anUnreadableImageIsNeverWritten() = runBlocking {
        val images = PictogramImages(context)
        images.loadBundled()

        assertFalse(images.store(testId, "<html>Erreur</html>".toByteArray()))
        assertFalse(stored.exists())
    }
}
