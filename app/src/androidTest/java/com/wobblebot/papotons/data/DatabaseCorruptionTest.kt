package com.wobblebot.papotons.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.BoardWithCells
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val DATABASE_NAME = "corruption-test.db"
private const val EMISSION_TIMEOUT_MS = 5_000L

/** La base de l'appli, ouverte comme l'appli l'ouvre ([buildDatabase]). Invariant 4. */
@RunWith(AndroidJUnit4::class)
class DatabaseCorruptionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val copies = File(context.filesDir, CORRUPTED_DATABASES_DIRECTORY)

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
        copies.deleteRecursively()
    }

    @Test
    fun aCorruptedDatabaseIsCopiedBeforeBeingReplaced() {
        buildDatabase(context, DATABASE_NAME).apply {
            runBlocking { boardDao().insertAll(listOf(BoardEntity(id = 1, name = "home", rowCount = 1, columnCount = 1)), emptyList()) }
            close()
        }
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        // Pas de `-wal` factice : SQLite jette un journal invalide avant même de signaler la corruption.
        val corruptedBytes = ByteArray(8192) { (it % 7).toByte() }
        databaseFile.writeBytes(corruptedBytes)
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()

        val database = buildDatabase(context, DATABASE_NAME)
        try {
            // Rouverte vide, tables recréées : la requête passe.
            assertEquals(0, runBlocking { database.boardDao().countBoards() })
        } finally {
            database.close()
        }

        val copy = copies.listFiles().orEmpty().single()
        assertArrayEquals(corruptedBytes, File(copy, DATABASE_NAME).readBytes())
    }

    @Test
    fun aFailedCopyStillLetsTheDatabaseReopen() {
        context.getDatabasePath(DATABASE_NAME).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8192) { (it % 7).toByte() })
        }
        // Un fichier ordinaire à la place du dossier des copies : la copie échoue.
        copies.writeText("not a directory")

        val database = buildDatabase(context, DATABASE_NAME)
        try {
            assertEquals(0, runBlocking { database.boardDao().countBoards() })
        } finally {
            database.close()
        }
    }

    @Test
    fun anObservedBoardIsReadAgainAfterAWrite() {
        // Rougit si l'enveloppe du callback ne délègue pas `onOpen` : plus aucune invalidation.
        val database = buildDatabase(context, DATABASE_NAME)
        try {
            runBlocking {
                val emissions = Channel<BoardWithCells?>(Channel.UNLIMITED)
                val collector = launch(Dispatchers.Default) { database.boardDao().observeBoard(1).collect { emissions.send(it) } }
                assertNull(withTimeout(EMISSION_TIMEOUT_MS) { emissions.receive() })

                database.boardDao().insertAll(listOf(BoardEntity(id = 1, name = "home", rowCount = 1, columnCount = 1)), emptyList())

                assertNotNull(withTimeout(EMISSION_TIMEOUT_MS) { emissions.receive() })
                collector.cancel()
            }
        } finally {
            database.close()
        }
    }
}
