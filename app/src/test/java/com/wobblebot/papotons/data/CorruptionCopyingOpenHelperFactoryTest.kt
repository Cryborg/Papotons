package com.wobblebot.papotons.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** La copie de secours d'`onCorruption`, sur la JVM : fichiers compagnons et plafond des copies. */
class CorruptionCopyingOpenHelperFactoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val filesDirectory by lazy { folder.newFolder("files") }
    private val databaseFile by lazy { File(folder.newFolder("databases"), "papotons.db") }
    private val copies by lazy { File(filesDirectory, CORRUPTED_DATABASES_DIRECTORY) }
    private val context = mockk<Context> { every { filesDir } answers { filesDirectory } }
    private val roomCallback = mockk<SupportSQLiteOpenHelper.Callback>(relaxed = true)

    /** Le callback que la fabrique passe réellement au helper, celui qui reçoit `onCorruption`. */
    private fun wrappedCallback(): SupportSQLiteOpenHelper.Callback {
        var configuration: SupportSQLiteOpenHelper.Configuration? = null
        val delegate = SupportSQLiteOpenHelper.Factory { received ->
            configuration = received
            mockk(relaxed = true)
        }
        CorruptionCopyingOpenHelperFactory(context, delegate)
            .create(SupportSQLiteOpenHelper.Configuration(context, databaseFile.name, roomCallback))
        return checkNotNull(configuration).callback
    }

    private fun corrupt() {
        val database = mockk<SupportSQLiteDatabase> { every { path } returns databaseFile.path }
        wrappedCallback().onCorruption(database)
    }

    @Test
    fun `the database and its log files are copied before Room deletes them`() {
        // Contenus différents : une copie de l'un sous le nom de l'autre se verrait.
        databaseFile.writeBytes(byteArrayOf(1, 1, 1))
        File(databaseFile.path + "-wal").writeBytes(byteArrayOf(2, 2))
        File(databaseFile.path + "-shm").writeBytes(byteArrayOf(3))

        corrupt()

        val copy = copies.listFiles().orEmpty().single()
        assertArrayEquals(byteArrayOf(1, 1, 1), File(copy, "papotons.db").readBytes())
        assertArrayEquals(byteArrayOf(2, 2), File(copy, "papotons.db-wal").readBytes())
        assertArrayEquals(byteArrayOf(3), File(copy, "papotons.db-shm").readBytes())
        // Absent : ignoré sans échec.
        assertFalse(File(copy, "papotons.db-journal").exists())
        verify { roomCallback.onCorruption(any()) }
    }

    @Test
    fun `the first copy and the newest ones are kept, numbered after the highest`() {
        // Numéros à trous, comme après un élagage : la suite part du plus grand, pas du nombre de copies.
        val first = "000001-a"
        for (name in listOf(first, "000004-d", "000005-e")) File(copies, name).mkdirs()
        databaseFile.writeBytes(byteArrayOf(9))

        corrupt()

        val kept = copies.list().orEmpty().sorted()
        // La première porte les grilles d'avant la perte ; les suivantes, ce qui a été refait.
        assertEquals(listOf(first, "000005-e"), kept.take(2))
        assertEquals(3, kept.size)
        assertTrue(kept.last().startsWith("000006-"))
        assertTrue(File(copies, kept.last()).resolve("papotons.db").isFile)
    }

    @Test
    fun `a log file that cannot be copied does not cost the database copy`() {
        databaseFile.writeBytes(byteArrayOf(1, 1, 1))
        // Un `-wal` illisible : sa copie échoue.
        File(databaseFile.path + "-wal").writeBytes(byteArrayOf(2))
        val unreadableLog = File(databaseFile.path + "-wal")
        unreadableLog.setReadable(false)

        corrupt()

        val copy = copies.listFiles().orEmpty().single()
        assertArrayEquals(byteArrayOf(1, 1, 1), File(copy, "papotons.db").readBytes())
        assertFalse(File(copy, "papotons.db-wal").exists())
    }

    @Test
    fun `two corruptions in a row keep two distinct copies`() {
        databaseFile.writeBytes(byteArrayOf(5))

        corrupt()
        corrupt()

        assertEquals(2, copies.list().orEmpty().size)
    }

    @Test
    fun `every callback method reaches Room`() {
        val database = mockk<SupportSQLiteDatabase>()
        val callback = wrappedCallback()

        callback.onConfigure(database)
        callback.onCreate(database)
        callback.onUpgrade(database, 1, 2)
        callback.onDowngrade(database, 3, 2)
        callback.onOpen(database)

        verify {
            roomCallback.onConfigure(database)
            roomCallback.onCreate(database)
            roomCallback.onUpgrade(database, 1, 2)
            roomCallback.onDowngrade(database, 3, 2)
            roomCallback.onOpen(database)
        }
    }

    @Test
    fun `an unexpected copy failure still lets Room delete the database`() {
        every { context.filesDir } throws IllegalStateException("no files directory")
        databaseFile.writeBytes(byteArrayOf(7))

        corrupt()

        verify { roomCallback.onCorruption(any()) }
    }
}
