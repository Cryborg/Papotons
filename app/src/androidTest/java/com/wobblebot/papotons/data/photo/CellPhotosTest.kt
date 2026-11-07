package com.wobblebot.papotons.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val SOURCE_WIDTH = 300
private const val SOURCE_HEIGHT = 400
private const val MARK_SIZE = 100
private const val STORED_SIDE = 480
private const val EDGE = 10

/** Une photo de proche importée : recadrée, réduite, orientée, gardée dans l'appli, jamais à moitié. */
@RunWith(AndroidJUnit4::class)
class CellPhotosTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = File(context.cacheDir, "test-source.jpg")
    private val photos = CellPhotos(context)
    private val created = mutableListOf<String>()

    @Before
    fun emptyPending() {
        File(context.noBackupFilesDir, "photos-pending").listFiles().orEmpty().forEach { it.delete() }
    }

    @After
    fun cleanUp() = runBlocking {
        source.delete()
        for (name in created) photos.delete(name)
    }

    /** Portrait blanc, un carré rouge en haut à gauche ; orientation EXIF donnée. */
    private fun portraitWithRedCorner(orientation: Int, width: Int = SOURCE_WIDTH, height: Int = SOURCE_HEIGHT, mark: Int = MARK_SIZE): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, mark.toFloat(), mark.toFloat(), Paint().apply { color = Color.RED })
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(source)
    }

    private fun isRed(bitmap: Bitmap, x: Int, y: Int): Boolean {
        val pixel = bitmap.getPixel(x, y)
        return Color.red(pixel) > 200 && Color.green(pixel) < 80
    }

    private fun prepared(orientation: Int): Bitmap = runBlocking {
        val name = checkNotNull(photos.prepare(portraitWithRedCorner(orientation)))
        created += name
        assertTrue(photos.commit(name))
        BitmapFactory.decodeFile(File(context.filesDir, "photos/$name").path)
    }

    @Test
    fun aPhotoTurnedNinetyDegreesComesOutSquareAndUpright() {
        val photo = prepared(ExifInterface.ORIENTATION_ROTATE_90)

        assertEquals(STORED_SIDE to STORED_SIDE, photo.width to photo.height)
        // Tournée d'un quart de tour vers la droite : le coin rouge passe en haut à droite.
        assertTrue(isRed(photo, STORED_SIDE - EDGE, EDGE))
        assertFalse(isRed(photo, EDGE, EDGE))
    }

    @Test
    fun aPhotoTurnedTwoHundredSeventyDegreesComesOutUpright() {
        val photo = prepared(ExifInterface.ORIENTATION_ROTATE_270)

        // Un quart de tour vers la gauche : le coin rouge passe en bas à gauche.
        assertTrue(isRed(photo, EDGE, STORED_SIDE - EDGE))
        assertFalse(isRed(photo, STORED_SIDE - EDGE, EDGE))
    }

    @Test
    fun aPreparedPhotoIsOnlyKeptOnceCommittedAndCommitIsIdempotent() = runBlocking {
        val name = checkNotNull(photos.prepare(portraitWithRedCorner(ExifInterface.ORIENTATION_NORMAL)))
        created += name
        val stored = File(context.filesDir, "photos/$name")
        assertFalse(stored.exists())

        assertTrue(photos.commit(name))
        assertTrue(photos.commit(name))
        assertTrue(stored.isFile)

        photos.delete(name)
        assertFalse(stored.exists())
    }

    @Test
    fun anUnreadableImageLeavesNothing() = runBlocking {
        source.writeText("pas une image")

        assertNull(photos.prepare(Uri.fromFile(source)))
        assertTrue(File(context.noBackupFilesDir, "photos-pending").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun leftoversAreCleanedAtStart() = runBlocking {
        val name = checkNotNull(photos.prepare(portraitWithRedCorner(ExifInterface.ORIENTATION_NORMAL)))
        photos.freshCameraCapture()
        File(context.cacheDir, "camera/capture.jpg").writeText("photo pleine taille")

        photos.cleanUp()

        assertFalse(File(context.noBackupFilesDir, "photos-pending/$name").exists())
        assertFalse(File(context.cacheDir, "camera/capture.jpg").exists())
    }

    @Test
    fun startCleaningSparesCommittedPhotos() = runBlocking {
        val kept = checkNotNull(photos.prepare(portraitWithRedCorner(ExifInterface.ORIENTATION_NORMAL)))
        created += kept
        assertTrue(photos.commit(kept))
        val dropped = checkNotNull(photos.prepare(portraitWithRedCorner(ExifInterface.ORIENTATION_NORMAL)))

        photos.cleanUp()

        assertTrue(File(context.filesDir, "photos/$kept").isFile)
        assertFalse(File(context.noBackupFilesDir, "photos-pending/$dropped").exists())
        assertFalse(photos.commit("absent.jpg"))
    }

    @Test
    fun aLargePhotoIsSampledDownToTheStoredSide() {
        val name = runBlocking { checkNotNull(photos.prepare(portraitWithRedCorner(ExifInterface.ORIENTATION_NORMAL, 2000, 2400, 500))) }
        created += name
        runBlocking { photos.commit(name) }
        val photo = BitmapFactory.decodeFile(File(context.filesDir, "photos/$name").path)

        assertEquals(STORED_SIDE to STORED_SIDE, photo.width to photo.height)
        assertTrue(isRed(photo, EDGE, EDGE))
    }

    @Test
    fun aTransparentImageGetsAWhiteBackground() = runBlocking {
        val transparent = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_WIDTH, Bitmap.Config.ARGB_8888)
        source.outputStream().use { transparent.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val name = checkNotNull(photos.prepare(Uri.fromFile(source)))
        created += name
        photos.commit(name)
        val pixel = BitmapFactory.decodeFile(File(context.filesDir, "photos/$name").path).getPixel(EDGE, EDGE)

        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    /** Chaque orientation EXIF, miroirs compris : le coin rouge (en haut à gauche à l'origine) arrive où il doit. */
    @Test
    fun everyExifOrientationPutsTheRedCornerInPlace() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(0, 0, Color.RED)
        }
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to (0 to 0),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (3 to 0),
            ExifInterface.ORIENTATION_ROTATE_180 to (3 to 3),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to (0 to 3),
            ExifInterface.ORIENTATION_TRANSPOSE to (0 to 0),
            ExifInterface.ORIENTATION_ROTATE_90 to (3 to 0),
            ExifInterface.ORIENTATION_TRANSVERSE to (3 to 3),
            ExifInterface.ORIENTATION_ROTATE_270 to (0 to 3),
        )
        for ((orientation, corner) in expected) {
            val result = oriented(source, orientation)
            assertEquals("orientation $orientation", Color.RED, result.getPixel(corner.first, corner.second))
        }
    }
}
