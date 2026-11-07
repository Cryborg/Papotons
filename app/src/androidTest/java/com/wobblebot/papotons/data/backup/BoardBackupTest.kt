package com.wobblebot.papotons.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.data.CORRUPTED_DATABASES_DIRECTORY
import com.wobblebot.papotons.data.PapotonsDatabase
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.createPreferences
import com.wobblebot.papotons.data.photo.CELL_PHOTOS_DIRECTORY
import com.wobblebot.papotons.data.photo.CellPhotos
import com.wobblebot.papotons.data.pictogram.DOWNLOADED_PICTOGRAMS_DIRECTORY
import com.wobblebot.papotons.data.pictogram.PictogramImages
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Un picto du vocabulaire de base, donc embarqué. */
private const val BUNDLED_PICTOGRAM = 6632L

/** Un id qu'aucun picto embarqué ne porte : il passe pour téléchargé. */
private const val DOWNLOADED_PICTOGRAM = 999_999_001L
private const val PHOTO = "0a1b2c3d-0000-4000-8000-0000000000aa.jpg"
private const val MISSING_PHOTO = "0a1b2c3d-0000-4000-8000-0000000000bb.jpg"
private const val UNREFERENCED_PHOTO = "0a1b2c3d-0000-4000-8000-0000000000cc.jpg"

/** Ce que l'export écrit, la restauration le remet : vraie base, vrais fichiers, vraie archive. */
@RunWith(AndroidJUnit4::class)
class BoardBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: PapotonsDatabase
    private lateinit var repository: BoardRepository
    private lateinit var backup: BoardBackup
    private val archive = File(context.cacheDir, "test-backup.obz")
    private val crafted = File(context.cacheDir, "test-crafted.obz")
    // Un fichier par test : DataStore refuse deux instances actives sur le même.
    private val preferencesFile = File(context.cacheDir, "test-backup-${System.nanoTime()}.preferences_pb")
    private val photosDirectory = File(context.filesDir, CELL_PHOTOS_DIRECTORY)
    private val downloaded = File(File(context.filesDir, DOWNLOADED_PICTOGRAMS_DIRECTORY), "$DOWNLOADED_PICTOGRAM.png")
    private val copies = File(context.filesDir, CORRUPTED_DATABASES_DIRECTORY)
    private val pictograms = PictogramImages(context)

    private val boards = listOf(
        BoardEntity(id = 1, name = "Accueil", rowCount = 2, columnCount = 2, profileId = 1),
        BoardEntity(id = 7, name = "Manger", rowCount = 1, columnCount = 2, profileId = 1),
    )

    private fun cell(boardId: Long, row: Int, column: Int, label: String) =
        CellEntity(boardId = boardId, row = row, column = column, label = label, linkedBoardId = null, pictogramId = null, category = null)

    private val cells = listOf(
        cell(1, 0, 0, "je").copy(pictogramId = BUNDLED_PICTOGRAM, category = "PEOPLE"),
        cell(1, 0, 1, "Mamie").copy(photoFile = PHOTO, spokenText = "Mamie Jeanne"),
        cell(1, 1, 0, "Manger").copy(linkedBoardId = 7, pictogramId = DOWNLOADED_PICTOGRAM),
        // Ce que la base peut contenir et que l'éditeur n'écrirait pas : tout revient.
        cell(1, 4, 4, "x".repeat(500)).copy(category = "INCONNUE"),
        cell(7, 0, 0, "pomme").copy(photoFile = MISSING_PHOTO, pictogramId = 999_999_002L),
    )

    private fun image(color: Int, side: Int, height: Int = side): ByteArray {
        val bitmap = Bitmap.createBitmap(side, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, PapotonsDatabase::class.java).build()
        repository = BoardRepository(database.boardDao(), createPreferences { preferencesFile }, context)
        pictograms.loadBundled()
        backup = BoardBackup(context, repository, pictograms, CellPhotos(context))
        database.boardDao().insertProfile(ProfileEntity(id = 1, name = "Léo", startBoardId = null))
        database.boardDao().insertAll(boards, cells)
        photosDirectory.mkdirs()
        // Plus grande que 480 px, en PNG : la restauration la réencode comme une photo choisie.
        File(photosDirectory, PHOTO).writeBytes(image(Color.RED, 1600, 900))
        downloaded.parentFile?.mkdirs()
        downloaded.writeBytes(image(Color.BLUE, 64))
    }

    @After
    fun tearDown() {
        database.close()
        for (file in listOf(archive, crafted, preferencesFile, downloaded, orphanPictogram, File(photosDirectory, PHOTO), File(photosDirectory, UNREFERENCED_PHOTO))) file.delete()
        copies.deleteRecursively()
        File(context.filesDir, "$CORRUPTED_DATABASES_DIRECTORY.edited").delete()
    }

    private fun content() = runBlocking { repository.snapshot().first }

    private fun BackupContent.withoutCellIds() = copy(cells = cells.map { it.copy(id = 0) }.toSet().toList())

    @Test
    fun aBackupComesBackIdenticalWithItsImages() = runBlocking {
        val before = content()
        backup.export(Uri.fromFile(archive))
        // Tout part : autres grilles, images supprimées.
        database.boardDao().replaceAll(listOf(ProfileEntity(id = 1, name = "Autre", startBoardId = null)), listOf(BoardEntity(1, "autre", 1, 1, profileId = 1)), listOf(cell(1, 0, 0, "autre")))
        File(photosDirectory, PHOTO).delete()
        downloaded.delete()

        val pending = backup.analyze(Uri.fromFile(archive))
        backup.restore(pending)

        val after = content()
        assertEquals(before.boards.toSet(), after.boards.toSet())
        assertEquals(before.withoutCellIds().cells.toSet(), after.withoutCellIds().cells.toSet())
        val restoredPhoto = File(photosDirectory, PHOTO)
        val photo = checkNotNull(BitmapFactory.decodeFile(restoredPhoto.path))
        assertEquals(480 to 480, photo.width to photo.height)
        // JPEG (FF D8), plus le PNG de l'archive.
        assertEquals(listOf(0xFF, 0xD8), restoredPhoto.readBytes().take(2).map { it.toInt() and 0xFF })
        // Réencodée en JPEG : rouge à peu près.
        val center = photo.getPixel(240, 240)
        assertTrue(Color.red(center) > 200 && Color.green(center) < 50 && Color.blue(center) < 50)
        assertEquals(Color.BLUE, checkNotNull(BitmapFactory.decodeFile(downloaded.path)).getPixel(10, 10))
        assertEquals(1, repository.replacements.value)
        assertFalse(restoreFile.exists())
    }

    private val restoreFile = File(context.noBackupFilesDir, "restore.obz")

    @Test
    fun aPictogramThatCannotBeWrittenStopsTheRestoreBeforeTheBoards() = runBlocking {
        backup.export(Uri.fromFile(archive))
        val before = content()
        downloaded.delete()
        val pending = backup.analyze(Uri.fromFile(archive))
        // Abîmé entre la lecture et la confirmation : le picto ne s'écrit pas.
        rewrite { name, bytes -> if (name == pictogramImagePath(DOWNLOADED_PICTOGRAM)) "pas une image".toByteArray() else bytes }
        crafted.copyTo(restoreFile, overwrite = true)

        try {
            backup.restore(pending)
            fail("The restore should fail")
        } catch (exception: java.io.IOException) {
            // Attendu : rien n'a touché la base.
        }

        assertEquals(before, content())
        assertEquals(0, repository.replacements.value)
        assertFalse(restoreFile.exists())
    }

    @Test
    fun aPhotoUnreadableAtTheRestoreComesBackWithoutItsImage() = runBlocking {
        backup.export(Uri.fromFile(archive))
        File(photosDirectory, PHOTO).delete()
        val pending = backup.analyze(Uri.fromFile(archive))
        rewrite { name, bytes -> if (name == photoImagePath(PHOTO)) "pas une image".toByteArray() else bytes }
        crafted.copyTo(restoreFile, overwrite = true)

        backup.restore(pending)

        assertEquals(1, repository.replacements.value)
        assertTrue(content().cells.any { it.photoFile == PHOTO })
        assertFalse(File(photosDirectory, PHOTO).exists())
    }

    @Test
    fun aRestoreGoesToTheEndEvenWhenItsCallerIsCancelled() = runBlocking {
        backup.export(Uri.fromFile(archive))
        database.boardDao().replaceAll(listOf(ProfileEntity(id = 1, name = "Autre", startBoardId = null)), listOf(BoardEntity(1, "autre", 1, 1, profileId = 1)), listOf(cell(1, 0, 0, "autre")))
        val pending = backup.analyze(Uri.fromFile(archive))

        val job = launch(start = CoroutineStart.UNDISPATCHED) { backup.restore(pending) }
        job.cancel()
        job.join()

        assertEquals(boards.toSet(), content().boards.toSet())
        assertEquals(1, repository.replacements.value)
    }

    @Test
    fun aRefusedOrForgottenBackupLeavesNoCopyBehind() = runBlocking {
        backup.export(Uri.fromFile(archive))
        crafted.writeText("pas un zip")
        assertRefused(RefusalReason.Unreadable) { backup.analyze(Uri.fromFile(crafted)) }
        assertFalse(restoreFile.exists())

        backup.analyze(Uri.fromFile(archive))
        assertTrue(restoreFile.exists())
        backup.discard()
        assertFalse(restoreFile.exists())
    }

    private val orphanPictogram = File(File(context.filesDir, DOWNLOADED_PICTOGRAMS_DIRECTORY), "999999003.png")

    @Test
    fun theBackupCarriesBundledPictogramsAndOnlyImagesThatExist() = runBlocking {
        // Des images que plus rien ne vise : sans perte, elles restent sur l'appareil.
        File(photosDirectory, UNREFERENCED_PHOTO).writeBytes(image(Color.BLACK, 480))
        orphanPictogram.writeBytes(image(Color.BLACK, 64))
        backup.export(Uri.fromFile(archive))

        val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue(pictogramImagePath(BUNDLED_PICTOGRAM) in names)
        assertTrue(pictogramImagePath(DOWNLOADED_PICTOGRAM) in names)
        assertFalse(pictogramImagePath(999_999_002L) in names)
        assertFalse(photoImagePath(MISSING_PHOTO) in names)
        // Sans copie corrompue, rien d'autre que le format.
        assertTrue(names.none { it.startsWith("papotons/") })
    }

    @Test
    fun aPictogramKeptFromTheDeviceIsDatedNowForTheStartupSweep() = runBlocking {
        backup.export(Uri.fromFile(archive))
        downloaded.setLastModified(1_000L)

        backup.restore(backup.analyze(Uri.fromFile(archive)))

        assertTrue(downloaded.lastModified() > 1_000L)
    }

    @Test
    fun aFailedBackupLeavesTheBoardsUnsaved() = runBlocking {
        try {
            backup.export(Uri.fromFile(File(context.cacheDir, "absent/papotons.obz")))
            fail("The backup should fail")
        } catch (exception: java.io.IOException) {
            // Attendu.
        }

        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun aPictogramAlreadyOnTheDeviceIsNeverOverwritten() = runBlocking {
        backup.export(Uri.fromFile(archive))
        val mine = image(Color.GREEN, 64)
        downloaded.writeBytes(mine)

        backup.restore(backup.analyze(Uri.fromFile(archive)))

        assertArrayEquals(mine, downloaded.readBytes())
    }

    @Test
    fun anUnreadableImageIsRefusedBeforeTheConfirmation() = runBlocking {
        backup.export(Uri.fromFile(archive))
        rewrite { name, bytes -> if (name == photoImagePath(PHOTO)) "pas une image".toByteArray() else bytes }

        assertRefused(RefusalReason.Unreadable) { backup.analyze(Uri.fromFile(crafted)) }
    }

    @Test
    fun aGiantEntryIsRefusedWithoutWritingAnything() = runBlocking {
        backup.export(Uri.fromFile(archive))
        val before = content()
        rewrite { name, bytes -> if (name == boardPath(7)) ByteArray(9 * 1024 * 1024) { ' '.code.toByte() } else bytes }

        assertRefused(RefusalReason.TooLarge) { backup.analyze(Uri.fromFile(crafted)) }

        assertEquals(before, content())
    }

    @Test
    fun aZipThatIsNotABackupIsRefused() = runBlocking {
        crafted.writeText("pas un zip")

        assertRefused(RefusalReason.Unreadable) { backup.analyze(Uri.fromFile(crafted)) }
    }

    @Test
    fun afterALossTheBackupCarriesTheCopiesAndTheImagesNoCellPointsTo() = runBlocking {
        File(copies, "000001-x").mkdirs()
        File(copies, "000001-x/papotons.db").writeText("abîmée")
        File(photosDirectory, UNREFERENCED_PHOTO).writeBytes(image(Color.BLACK, 480))
        orphanPictogram.writeBytes(image(Color.BLACK, 64))

        backup.export(Uri.fromFile(archive))

        val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("papotons/corrupted-databases/000001-x/papotons.db" in names)
        assertTrue("papotons/unreferenced-images/photos/$UNREFERENCED_PHOTO" in names)
        assertTrue("papotons/unreferenced-images/photos/$PHOTO" !in names)
        assertTrue("papotons/unreferenced-images/pictograms/${orphanPictogram.name}" in names)
        assertTrue("papotons/unreferenced-images/pictograms/$DOWNLOADED_PICTOGRAM.png" !in names)
        // Le secours n'est ni une grille ni une image pour la restauration.
        val before = content()
        backup.restore(backup.analyze(Uri.fromFile(archive)))
        assertEquals(before.boards.toSet(), content().boards.toSet())
    }

    @Test
    fun aPictogramSharedByManyCellsCountsOnceAndTheBackupRestores() = runBlocking {
        // Du bruit : un PNG qui ne se compresse pas, de quelques centaines de Ko.
        val noise = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            val random = java.util.Random(7)
            setPixels(IntArray(300 * 300) { random.nextInt() or 0xFF000000.toInt() }, 0, 300, 0, 0, 300, 300)
        }
        downloaded.writeBytes(ByteArrayOutputStream().also { noise.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        val big = BoardEntity(id = 30, name = "Beaucoup", rowCount = 40, columnCount = 40, profileId = 1)
        val many = (0 until 1_000).map { index -> cell(30, index / 40, index % 40, "m$index").copy(pictogramId = DOWNLOADED_PICTOGRAM) }
        database.boardDao().insertAll(listOf(big), many)

        backup.export(Uri.fromFile(archive))
        val pending = backup.analyze(Uri.fromFile(archive))

        assertEquals(cells.size + many.size, pending.cellCount)
    }

    @Test
    fun aDamagedPhotoOnTheDeviceIsLeftOutAndTheBackupStillRestores() = runBlocking {
        File(photosDirectory, PHOTO).writeText("abîmée")

        backup.export(Uri.fromFile(archive))
        val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertFalse(photoImagePath(PHOTO) in names)
        backup.restore(backup.analyze(Uri.fromFile(archive)))

        // La case garde sa photo : la référence est revenue.
        assertTrue(content().cells.any { it.photoFile == PHOTO })
    }

    @Test
    fun theManifestIsWrittenLastForStreamingReaders() = runBlocking {
        backup.export(Uri.fromFile(archive))

        val last = ZipFile(archive).use { zip -> zip.entries().asSequence().last().name }
        assertEquals(MANIFEST_PATH, last)
    }

    @Test
    fun aCompleteBackupMarksTheBoardsSaved() = runBlocking {
        repository.addWord(1, 1, 1, com.wobblebot.papotons.domain.CellContent("eau", null, null, null, null))
        assertTrue(repository.observeUnsavedChanges().first())

        assertEquals(0, backup.export(Uri.fromFile(archive)))

        assertFalse(repository.observeUnsavedChanges().first())
    }

    @Test
    fun aDamagedPictogramIsLeftOutAndTheBoardsStayUnsaved() = runBlocking {
        downloaded.writeBytes(image(Color.BLUE, 64).copyOf(40))

        assertEquals(1, backup.export(Uri.fromFile(archive)))

        val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertFalse(pictogramImagePath(DOWNLOADED_PICTOGRAM) in names)
        assertTrue(repository.observeUnsavedChanges().first())
        backup.restore(backup.analyze(Uri.fromFile(archive)))
    }

    @Test
    fun aLargeRecoveryFileTravelsAndTheBackupStillRestores() = runBlocking {
        File(copies, "000001-x").mkdirs()
        File(copies, "000001-x/papotons.db").writeBytes(ByteArray(9 * 1024 * 1024))

        backup.export(Uri.fromFile(archive))

        val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("papotons/corrupted-databases/000001-x/papotons.db" in names)
        backup.restore(backup.analyze(Uri.fromFile(archive)))
    }

    @Test
    fun anUnreadableRecoveryFileIsLeftOutAndTheBackupSucceeds(): Unit = runBlocking {
        File(copies, "000001-x").mkdirs()
        val unreadable = File(copies, "000001-x/papotons.db").apply {
            writeText("abîmée")
            setReadable(false, false)
        }
        File(copies, "000001-x/papotons.db-wal").writeText("lisible")

        backup.export(Uri.fromFile(archive))

        val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertFalse("papotons/corrupted-databases/000001-x/papotons.db" in names)
        assertTrue("papotons/corrupted-databases/000001-x/papotons.db-wal" in names)
        unreadable.setReadable(true, false)
    }

    @Test
    fun aBackupGoesToTheEndEvenWhenItsCallerIsCancelled() = runBlocking {
        val job = launch(start = CoroutineStart.UNDISPATCHED) { backup.export(Uri.fromFile(archive)) }
        job.cancel()
        job.join()

        assertEquals(MANIFEST_PATH, ZipFile(archive).use { zip -> zip.entries().asSequence().last().name })
        assertFalse(repository.observeUnsavedChanges().first())
    }

    @Test
    fun aPhotoAlreadyOnTheDeviceIsKeptAsItIs() = runBlocking {
        backup.export(Uri.fromFile(archive))
        val before = File(photosDirectory, PHOTO).readBytes()

        backup.restore(backup.analyze(Uri.fromFile(archive)))

        // Ni réécrite ni recompressée : une restauration sur la même tablette n'abîme pas les photos.
        assertArrayEquals(before, File(photosDirectory, PHOTO).readBytes())
    }

    /** Copie [archive] dans [crafted] en transformant ses entrées. */
    private fun rewrite(transform: (String, ByteArray) -> ByteArray) {
        ZipFile(archive).use { source ->
            ZipOutputStream(crafted.outputStream()).use { target ->
                for (entry in source.entries()) {
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(transform(entry.name, source.getInputStream(entry).use { it.readBytes() }))
                    target.closeEntry()
                }
            }
        }
    }

    private suspend fun assertRefused(reason: RefusalReason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a refusal: $reason")
        } catch (exception: BackupRefusedException) {
            assertEquals(exception.message, reason, exception.reason)
        }
    }
}
