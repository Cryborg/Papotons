package com.wobblebot.papotons.data

import com.wobblebot.papotons.data.board.BoardDao
import com.wobblebot.papotons.data.board.TestDatabases
import com.wobblebot.papotons.data.profile.ProfileEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val LEO = listOf(ProfileEntity(id = 1, name = "Léo", startBoardId = null))
private val NEW_PROFILES = listOf(ProfileEntity(id = 1, name = "Moi", startBoardId = null))

/** L'instance courante de Room, et sa relève après une corruption vue en cours d'usage. */
@OptIn(ExperimentalCoroutinesApi::class)
class PapotonsDatabasesTest {
    private fun dao(profiles: Flow<List<ProfileEntity>>) = mockk<BoardDao> { every { observeProfiles() } returns profiles }

    @Test
    fun `the same instance serves until it is replaced, then the new one`() {
        val first = dao(flowOf(LEO))
        val second = dao(flowOf(NEW_PROFILES))
        val test = TestDatabases(first, second)

        assertSame(first, test.databases.boardDao)
        test.lose()
        // Perdue mais pas encore supprimée : une instance neuve rouvrirait l'ancien fichier.
        assertSame(first, test.databases.boardDao)
        test.replace()
        assertSame(second, test.databases.boardDao)
        assertSame(second, test.databases.boardDao)
    }

    @Test
    fun `a flow of the lost instance gives way to the new instance's`() = runTest(UnconfinedTestDispatcher()) {
        val connectionClosed = CompletableDeferred<Unit>()
        val lost = dao(flow {
            emit(LEO)
            connectionClosed.await()
            throw IllegalStateException("connection is closed")
        })
        val test = TestDatabases(lost, dao(flowOf(NEW_PROFILES)))
        val emissions = mutableListOf<List<ProfileEntity>>()
        val collector = launch { test.databases.observe { it.observeProfiles() }.toList(emissions) }

        test.lose()
        connectionClosed.complete(Unit)
        test.replace()

        assertEquals(listOf(LEO, NEW_PROFILES), emissions)
        assertTrue(collector.isActive)
        collector.cancel()
    }

    @Test
    fun `a query that saw the corruption moves on at once, the database already replaced`() = runTest {
        lateinit var test: TestDatabases
        // Comme en vrai : `onCorruption` copie, supprime et remplace AVANT que la requête ne lève.
        val lost = dao(flow {
            emit(LEO)
            test.replace()
            throw IllegalStateException("database disk image is malformed")
        })
        test = TestDatabases(lost, dao(flowOf(NEW_PROFILES)))
        val emissions = mutableListOf<List<ProfileEntity>>()
        val collector = launch { test.databases.observe { it.observeProfiles() }.toList(emissions) }

        runCurrent()

        assertEquals(listOf(LEO, NEW_PROFILES), emissions)
        // Sans attendre le délai : rien n'a avancé l'horloge.
        assertEquals(0L, currentTime)
        collector.cancel()
    }

    @Test
    fun `an error of a lost instance never replaced comes through once the wait is over`() = runTest {
        val test = TestDatabases(dao(flow { throw IllegalStateException("connection is closed") }))
        test.lose()
        var failure: Throwable? = null
        launch {
            try {
                test.databases.observe { it.observeProfiles() }.toList()
            } catch (exception: IllegalStateException) {
                failure = exception
            }
        }

        // Fichier resté là : pas d'écran figé sans un mot, l'erreur remonte comme avant.
        advanceTimeBy(DATABASE_REPLACEMENT_TIMEOUT_MS - 1)
        assertNull(failure)
        advanceTimeBy(2)
        assertEquals("connection is closed", failure?.message)
    }

    @Test
    fun `an error of a healthy instance still reaches the collector`() = runTest {
        val test = TestDatabases(dao(flow { throw IllegalStateException("disk full") }))

        val failure = runCatching { test.databases.observe { it.observeProfiles() }.toList() }.exceptionOrNull()

        assertEquals("disk full", failure?.message)
    }

    @Test
    fun `a failure is blamed on the loss until the next replacement, and after it`() {
        val test = TestDatabases(dao(flowOf(LEO)), dao(flowOf(NEW_PROFILES)))
        val before = test.databases.replacements.value

        assertFalse(test.databases.isLostSince(before))
        test.lose()
        assertTrue(test.databases.isLostSince(before))
        test.replace()
        test.databases.boardDao
        // La nouvelle instance est saine, mais l'échec vu depuis `before` venait bien de la perte.
        assertTrue(test.databases.isLostSince(before))
        assertFalse(test.databases.isLostSince(test.databases.replacements.value))
    }
}
