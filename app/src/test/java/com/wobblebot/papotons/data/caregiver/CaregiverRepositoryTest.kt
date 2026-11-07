package com.wobblebot.papotons.data.caregiver

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.domain.Caregiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Copie de la clé privée du dépôt : un renommage ferait oublier tous les accompagnants. */
private val CAREGIVERS = stringPreferencesKey("caregivers")

class CaregiverRepositoryTest {
    private val store = FakePreferences()
    private val repository = CaregiverRepository(store)

    private suspend fun idOf(name: String) = repository.observeCaregivers().first().single { it.name == name }.id

    @Test
    fun `each caregiver opens with their own code only`() = runTest {
        repository.add("Marie", "4721")
        repository.add("Paul", "1234")

        assertTrue(repository.verify(idOf("Marie"), "4721"))
        assertFalse(repository.verify(idOf("Marie"), "1234"))
        assertTrue(repository.verify(idOf("Paul"), "1234"))
        assertFalse(repository.verify(42, "4721"))
    }

    @Test
    fun `the code is never stored as typed`() = runTest {
        repository.add("Marie", "4721")

        val stored = parseCaregivers(checkNotNull(store.current[CAREGIVERS])).single()
        assertFalse(stored.codeHash.contentEquals("4721".toByteArray()))
        assertTrue(stored.codeHash.size > "4721".length)
        assertFalse(checkNotNull(store.current[CAREGIVERS]).contains("\"4721\""))
    }

    @Test
    fun `a code changed by recovery is flagged until an ordinary change`() = runTest {
        repository.add("Marie", "4721")
        val marie = idOf("Marie")

        repository.recoverCode(marie, "0000", nowMs = 1_700_000_000_000)

        assertTrue(repository.verify(marie, "0000"))
        assertFalse(repository.verify(marie, "4721"))
        assertEquals(1_700_000_000_000, repository.observeCaregivers().first().single().codeResetAtMs)
        // Renommer ne cache pas qu'un code a été remis par la multiplication.
        repository.edit(marie, "Marie-Anne", code = null)
        assertEquals(1_700_000_000_000, repository.observeCaregivers().first().single().codeResetAtMs)
        assertTrue(repository.verify(marie, "0000"))
        repository.edit(marie, "Marie", "5555")
        assertTrue(repository.verify(marie, "5555"))
        assertEquals("Marie", repository.observeCaregivers().first().single().name)
        assertNull(repository.observeCaregivers().first().single().codeResetAtMs)
    }

    @Test
    fun `a renamed caregiver keeps their code`() = runTest {
        repository.add("Marie", "4721")
        val marie = idOf("Marie")

        repository.edit(marie, "Marie-Anne", code = null)

        assertTrue(repository.verify(marie, "4721"))
        assertEquals(listOf("Marie-Anne"), repository.observeCaregivers().first().map { it.name })
    }

    @Test
    fun `deleting a caregiver leaves the others`() = runTest {
        repository.add("Marie", "4721")
        repository.add("Paul", "1234")

        repository.delete(idOf("Marie"))

        assertEquals(listOf("Paul"), repository.observeCaregivers().first().map { it.name })
        assertThrows(CaregiverNotFoundException::class.java) { runBlocking { repository.delete(1) } }
        assertThrows(CaregiverNotFoundException::class.java) { runBlocking { repository.edit(1, "Marie", null) } }
        assertThrows(CaregiverNotFoundException::class.java) { runBlocking { repository.recoverCode(1, "0000") } }
    }

    /** Base64 abîmé : illisible, donc aucun accompagnant. Sel vide : listé, jamais vérifié ; « Code oublié ? » le répare. */
    @Test
    fun `damaged codes never crash`() = runTest {
        val damagedBase64 = CaregiverRepository(FakePreferences(mutablePreferencesOf(CAREGIVERS to
            "[{\"id\":1,\"name\":\"Marie\",\"codeHash\":\"%%%\",\"codeSalt\":\"AA==\"}]")))
        val emptySalt = CaregiverRepository(FakePreferences(mutablePreferencesOf(CAREGIVERS to
            "[{\"id\":1,\"name\":\"Marie\",\"codeHash\":\"AA==\",\"codeSalt\":\"\"}]")))

        assertEquals(emptyList<Caregiver>(), damagedBase64.observeCaregivers().first())
        assertEquals(1, emptySalt.observeCaregivers().first().size)
        assertFalse(emptySalt.verify(1, "4721"))
    }

    /** Une lecture ratée ne fige pas la liste : un ajout fait ensuite s'affiche. */
    @Test
    fun `the list recovers after a failed read`() = runTest {
        store.readFailure = IOException("unreadable")
        val names = mutableListOf<List<String>>()
        val collector = backgroundScope.launch { repository.observeCaregivers().collect { list -> names += list.map { it.name } } }
        runCurrent()
        store.readFailure = null
        repository.add("Marie", "4721")

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf("Marie"), names.last())
        collector.cancel()
    }

    /** Ids jamais réutilisés tant qu'un plus grand existe : un ancien code ne vise pas un nouveau venu. */
    @Test
    fun `ids follow the largest one`() = runTest {
        repository.add("Marie", "4721")
        repository.add("Paul", "1234")
        repository.delete(idOf("Marie"))

        repository.add("Léa", "9999")

        assertEquals(3L, idOf("Léa"))
    }

    @Test
    fun `an unreadable file means no caregiver, so the parental question comes back`() = runTest {
        val damaged = CaregiverRepository(FakePreferences(mutablePreferencesOf(CAREGIVERS to "{pas du json")))

        assertEquals(emptyList<Caregiver>(), damaged.observeCaregivers().first())
        assertFalse(damaged.verify(1, "4721"))
    }

    @Test
    fun `a failed read means no caregiver and a refused code, never a crash`() = runTest {
        repository.add("Marie", "4721")
        store.readFailure = IOException("unreadable")

        assertEquals(emptyList<Caregiver>(), repository.observeCaregivers().first())
        assertFalse(repository.verify(1, "4721"))
    }

    @Test
    fun `names the backup would refuse are never written`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.add(" ", "4721") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.add("Marie", "47") } }
        assertTrue(repository.observeCaregivers().first().isEmpty())
    }
}
