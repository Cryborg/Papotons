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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val BARRIER_TIMEOUT_SECONDS = 5L

/** La copie de secours d'`onCorruption`, sur la JVM : fichiers compagnons et plafond des copies. */
class CorruptionCopyingOpenHelperFactoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val filesDirectory by lazy { folder.newFolder("files") }
    private val databaseFile by lazy { File(folder.newFolder("databases"), "papotons.db") }
    private val copies by lazy { File(filesDirectory, CORRUPTED_DATABASES_DIRECTORY) }
    private val context = mockk<Context> { every { filesDir } answers { filesDirectory } }
    private val roomCallback = mockk<SupportSQLiteOpenHelper.Callback>(relaxed = true)

    private var replacedCount = 0

    /** Ce que la fabrique disait de la base quand elle a prévenu du remplacement. */
    private val replacedSeenAtNotice = mutableListOf<Boolean>()
    private val factory: CorruptionCopyingOpenHelperFactory by lazy {
        CorruptionCopyingOpenHelperFactory(
            context,
            onReplaced = {
                replacedCount++
                replacedSeenAtNotice += factory.isReplaced
            },
            delegate = delegate,
        )
    }
    private var configuration: SupportSQLiteOpenHelper.Configuration? = null
    private val delegate = SupportSQLiteOpenHelper.Factory { received ->
        configuration = received
        mockk(relaxed = true)
    }

    /** Le callback que la fabrique passe réellement au helper, celui qui reçoit `onCorruption`. */
    private fun wrappedCallback(): SupportSQLiteOpenHelper.Callback {
        factory.create(SupportSQLiteOpenHelper.Configuration(context, databaseFile.name, roomCallback))
        return checkNotNull(configuration).callback
    }

    /** [isInUse] faux : vue par `SQLiteDatabase.open`, avant que la base ne soit ouverte. */
    private fun corruptedDatabase(isInUse: Boolean) = mockk<SupportSQLiteDatabase> {
        every { path } returns databaseFile.path
        every { isOpen } returns isInUse
    }

    private fun corrupt(isInUse: Boolean = false) {
        wrappedCallback().onCorruption(corruptedDatabase(isInUse))
    }

    /** Comme le callback par défaut : la base supprimée ; ce que la fabrique savait à ce moment-là. */
    private fun deleteOnCorruption(): MutableList<Pair<Boolean, Boolean>> {
        val seen = mutableListOf<Pair<Boolean, Boolean>>()
        every { roomCallback.onCorruption(any()) } answers {
            seen += factory.isLost to factory.isReplaced
            databaseFile.delete()
        }
        return seen
    }

    @Test
    fun `a database corrupted in use is lost before the copy and replaced only once deleted`() {
        databaseFile.writeBytes(byteArrayOf(1))
        val seenByRoom = deleteOnCorruption()
        // La copie commence par lire `filesDir` : ce que la fabrique en disait à ce moment-là.
        val lostSeenAtCopy = mutableListOf<Boolean>()
        every { context.filesDir } answers {
            lostSeenAtCopy += factory.isLost
            filesDirectory
        }

        corrupt(isInUse = true)

        assertTrue(lostSeenAtCopy.isNotEmpty() && lostSeenAtCopy.all { it })
        // Remplacée avant la suppression, l'instance neuve rouvrirait l'ancien fichier.
        assertEquals(listOf(true to false), seenByRoom)
        assertTrue(factory.isReplaced)
        assertEquals(1, replacedCount)
        // Le drapeau avant le compteur : un flux qui suit le compteur trouve l'instance neuve.
        assertEquals(listOf(true), replacedSeenAtNotice)
        assertEquals(1, copies.listFiles().orEmpty().size)
    }

    @Test
    fun `a corruption seen again on a lost database never deletes the new one`() {
        databaseFile.writeBytes(byteArrayOf(1))
        deleteOnCorruption()
        val callback = wrappedCallback()
        callback.onCorruption(corruptedDatabase(isInUse = true))
        // L'instance neuve a recréé sa base au même chemin ; une requête de l'ancienne arrive en retard.
        databaseFile.writeBytes(byteArrayOf(2))

        callback.onCorruption(corruptedDatabase(isInUse = false))
        callback.onCorruption(corruptedDatabase(isInUse = true))

        assertArrayEquals(byteArrayOf(2), databaseFile.readBytes())
        verify(exactly = 1) { roomCallback.onCorruption(any()) }
        assertEquals(1, replacedCount)
        assertEquals(1, copies.listFiles().orEmpty().size)
    }

    @Test
    fun `two queries hitting the corruption together copy and delete only once`() {
        databaseFile.writeBytes(byteArrayOf(1))
        deleteOnCorruption()
        val callback = wrappedCallback()
        // Les deux fils lisent la base ouverte au même instant : seul le `compareAndSet` les départage.
        val bothInside = CyclicBarrier(2)
        val database = mockk<SupportSQLiteDatabase> {
            every { path } returns databaseFile.path
            every { isOpen } answers {
                bothInside.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                true
            }
        }

        val threads = List(2) { thread { callback.onCorruption(database) } }
        threads.forEach { it.join() }

        verify(exactly = 1) { roomCallback.onCorruption(any()) }
        assertEquals(1, replacedCount)
        assertEquals(1, copies.listFiles().orEmpty().size)
    }

    @Test
    fun `a log file left after deletion keeps the database from being replaced`() {
        databaseFile.writeBytes(byteArrayOf(1))
        val wal = File(databaseFile.path + "-wal").apply { writeBytes(byteArrayOf(2)) }
        // Room supprime la base mais laisse le journal, qu'une base neuve rejouerait.
        every { roomCallback.onCorruption(any()) } answers { databaseFile.delete() }

        corrupt(isInUse = true)

        assertTrue(wal.exists())
        assertFalse(factory.isReplaced)
        assertEquals(0, replacedCount)
    }

    @Test
    fun `a database corrupted while opening leaves the instance as it is`() {
        databaseFile.writeBytes(byteArrayOf(1))
        deleteOnCorruption()

        corrupt(isInUse = false)

        assertFalse(factory.isLost)
        assertFalse(factory.isReplaced)
        assertEquals(0, replacedCount)
    }

    @Test
    fun `a database still there after deletion is lost but never replaced`() {
        databaseFile.writeBytes(byteArrayOf(1))

        corrupt(isInUse = true)

        assertTrue(factory.isLost)
        assertFalse(factory.isReplaced)
        assertEquals(0, replacedCount)
    }

    @Test
    fun `a deleted database is replaced even when Room's callback fails afterwards`() {
        databaseFile.writeBytes(byteArrayOf(1))
        every { roomCallback.onCorruption(any()) } answers {
            databaseFile.delete()
            throw IllegalStateException("close failed")
        }

        assertThrows(IllegalStateException::class.java) { corrupt(isInUse = true) }

        assertTrue(factory.isReplaced)
        assertEquals(1, replacedCount)
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
    fun `a log file copied without its database is not kept as a copy`() {
        databaseFile.writeBytes(byteArrayOf(1, 1, 1))
        File(databaseFile.path + "-wal").writeBytes(byteArrayOf(2))
        // La base illisible : sa copie échoue, seul le `-wal` passerait.
        databaseFile.setReadable(false)

        corrupt()

        assertEquals(emptyList<File>(), copies.listFiles().orEmpty().toList())
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
