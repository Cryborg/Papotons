package com.wobblebot.papotons.data.pictogram

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.data.board.CoreVocabulary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val UNKNOWN_PICTOGRAM_ID = -1L

/** Invariant 2 : chaque picto du vocabulaire de base se décode depuis l'APK, sans réseau. */
@RunWith(AndroidJUnit4::class)
class PictogramImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
}
