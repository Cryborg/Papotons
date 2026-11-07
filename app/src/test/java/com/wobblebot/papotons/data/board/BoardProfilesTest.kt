package com.wobblebot.papotons.data.board

import android.content.Context
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import com.wobblebot.papotons.data.profile.MAX_PROFILE_NAME_LENGTH
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Les utilisateurs côté données : chacun ses grilles, son écran de départ, et rien qui déborde chez l'autre. */
class BoardProfilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { folder.root }
    }
    private val preferences = FakePreferences()

    /** Une base neuve (l'utilisateur 1 aux ids fixes) et son dépôt, accueil ouvert. */
    private suspend fun freshRepository(dao: FakeBoardDao = FakeBoardDao()): BoardRepository =
        BoardRepository(dao, preferences, context).also { it.ensureHomeBoard() }

    private fun FakeBoardDao.homeOf(profileId: Long) = boards.value.single { it.profileId == profileId && it.coreBoardId == HOME_BOARD_ID }.id

    @Test
    fun `a fresh install seeds a first profile at the fixed ids`() = runTest {
        val dao = FakeBoardDao()

        assertEquals(HOME_BOARD_ID, freshRepository(dao).openProfileId.value?.let { dao.homeOf(it) })
        assertEquals(listOf(FIRST_PROFILE_ID), dao.profiles.value.map { it.id })
        assertTrue(dao.boards.value.all { it.profileId == FIRST_PROFILE_ID && it.coreBoardId == it.id })
    }

    /** Le défaut grave : un nouvel utilisateur qui s'ouvrirait sur les grilles du premier. */
    @Test
    fun `a new profile opens on its own home, never on the first profile's`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)

        val anna = repository.createProfile("Anna")
        repository.switchProfile(anna)
        val start = repository.ensureHomeBoard()

        assertEquals(dao.homeOf(anna), start)
        assertTrue("id $start", start >= FIRST_USER_BOARD_ID)
        assertEquals(anna, repository.openProfileId.value)
    }

    @Test
    fun `a start board pointing at another profile's board falls back to the profile's own home`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        dao.profiles.value = dao.profiles.value.map { if (it.id == anna) it.copy(startBoardId = BoardSize.MEDIUM.homeBoardId) else it }

        repository.switchProfile(anna)

        assertEquals(dao.homeOf(anna), repository.ensureHomeBoard())
    }

    @Test
    fun `a vanished open profile falls back to the smallest one`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        repository.switchProfile(42)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        assertEquals(FIRST_PROFILE_ID, repository.openProfileId.value)
    }

    @Test
    fun `each profile keeps its own size`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        repository.switchProfile(anna)
        repository.ensureHomeBoard()

        val annaMedium = checkNotNull(repository.setStartBoard(BoardSize.MEDIUM))

        assertNotEquals(BoardSize.MEDIUM.homeBoardId, annaMedium)
        assertEquals(BoardSize.MEDIUM.homeBoardId, dao.boards.value.single { it.id == annaMedium }.coreBoardId)
        repository.switchProfile(FIRST_PROFILE_ID)
        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        repository.switchProfile(anna)
        assertEquals(annaMedium, repository.ensureHomeBoard())
    }

    /** Écrit pendant un changement d'utilisateur, un dossier ne doit jamais relier deux utilisateurs. */
    @Test
    fun `a folder belongs to the profile of its parent board, not to the open one`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        val annaHome = dao.homeOf(anna)

        // L'utilisateur 1 est ouvert ; la grille parente est à Anna. Ligne 6 de l'accueil : libre.
        repository.addFolder(annaHome, 6, 7, CellContent("Animaux", null, null, null, null), BoardSize.STANDARD)

        assertEquals(anna, dao.boards.value.single { it.name == "Animaux" }.profileId)
    }

    @Test
    fun `deleting a profile removes only its boards and returns its photos`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val firstBoards = dao.boards.value.toSet()
        val anna = repository.createProfile("Anna")
        val annaHome = dao.homeOf(anna)
        repository.addWord(annaHome, 6, 7, CellContent("Mamie", null, null, "0a1b2c3d-0000-4000-8000-00000000000a.jpg", null))
        repository.addFolder(annaHome, 6, 6, CellContent("Animaux", null, null, null, null), BoardSize.STANDARD)

        val photos = repository.deleteProfile(anna)

        assertEquals(listOf("0a1b2c3d-0000-4000-8000-00000000000a.jpg"), photos)
        assertEquals(firstBoards, dao.boards.value.toSet())
        assertEquals(listOf(FIRST_PROFILE_ID), dao.profiles.value.map { it.id })
    }

    @Test
    fun `deleting the open profile first opens another one`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        repository.switchProfile(anna)
        repository.ensureHomeBoard()
        val switchesBefore = repository.profileSwitches.value

        repository.deleteProfile(anna)

        assertEquals(switchesBefore + 1, repository.profileSwitches.value)
        assertEquals(FIRST_PROFILE_ID, repository.openProfileId.value)
        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
    }

    /** Sinon la phrase de l'enfant se viderait quand l'adulte supprime un autre utilisateur. */
    @Test
    fun `deleting another profile leaves the open one alone`() = runTest {
        val repository = freshRepository()
        val anna = repository.createProfile("Anna")
        val switchesBefore = repository.profileSwitches.value

        repository.deleteProfile(anna)

        assertEquals(switchesBefore, repository.profileSwitches.value)
        assertEquals(FIRST_PROFILE_ID, repository.openProfileId.value)
    }

    @Test
    fun `no more than the maximum number of profiles can be created`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        repeat(MAX_PROFILES - 1) { repository.createProfile("p$it") }
        val boardCount = dao.boards.value.size

        assertThrows(TooManyProfilesException::class.java) { runBlocking { repository.createProfile("one too many") } }
        assertEquals(MAX_PROFILES, dao.profiles.value.size)
        assertEquals(boardCount, dao.boards.value.size)
    }

    @Test
    fun `deleting a folder never deletes the size home of a profile added later`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        val annaLarge = dao.boards.value.single { it.profileId == anna && it.coreBoardId == BoardSize.LARGE_CELLS.homeBoardId }.id
        val annaHome = dao.homeOf(anna)
        repository.addWord(annaHome, 6, 7, CellContent("porte", null, null, null, null))
        val door = dao.cells.value.single { it.label == "porte" }
        dao.cells.value = dao.cells.value.map { if (it.id == door.id) it.copy(linkedBoardId = annaLarge) else it }
        val doorCell = checkNotNull(repository.observeBoard(annaHome).first()).cells.single { it.label == "porte" }

        repository.deleteCell(doorCell, boardId = annaHome, openRootBoardId = annaHome)

        assertTrue(dao.boards.value.any { it.id == annaLarge })
    }

    @Test
    fun `the last profile cannot be deleted`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)

        assertThrows(LastProfileException::class.java) { runBlocking { repository.deleteProfile(FIRST_PROFILE_ID) } }
        assertEquals(1, dao.profiles.value.size)
    }

    @Test
    fun `switching profiles is not a change to save`() = runTest {
        val repository = freshRepository()
        val anna = repository.createProfile("Anna")
        val (_, count) = repository.snapshot()
        repository.markSaved(count)

        repository.switchProfile(anna)

        assertFalse(repository.observeUnsavedChanges().first())
    }

    /** Protégé par défaut : un utilisateur ajouté ne sort jamais librement vers le bureau. */
    @Test
    fun `the exit lock is on by default and changes only the profile it targets`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        val (_, count) = repository.snapshot()
        repository.markSaved(count)
        assertTrue(repository.observeProfiles().first().all { it.isExitLocked })
        assertFalse(repository.observeUnsavedChanges().first())

        repository.setExitLocked(anna, isLocked = false)

        assertEquals(mapOf(FIRST_PROFILE_ID to true, anna to false), repository.observeProfiles().first().associate { it.id to it.isExitLocked })
        // L'option part dans la sauvegarde : elle est un changement à sauvegarder.
        assertTrue(repository.observeUnsavedChanges().first())
        assertThrows(ProfileNotFoundException::class.java) { runBlocking { repository.setExitLocked(42, isLocked = false) } }
    }

    /** Grilles perdues, utilisateurs restés : le vocabulaire revient chez le plus petit, les autres partent. */
    @Test
    fun `reseeding a database without boards drops the profiles left without boards`() = runTest {
        val dao = FakeBoardDao(profiles = listOf(ProfileEntity(2, "Léo", null), ProfileEntity(5, "Anna", null)))

        freshRepository(dao)

        assertEquals(listOf(2L), dao.profiles.value.map { it.id })
        assertTrue(dao.boards.value.all { it.profileId == 2L })
    }

    @Test
    fun `a name the backup would refuse is never written`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)

        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.createProfile(" ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.renameProfile(FIRST_PROFILE_ID, "x".repeat(MAX_PROFILE_NAME_LENGTH + 1)) } }
        assertEquals(1, dao.profiles.value.size)
    }

    @Test
    fun `profiles are listed with their names`() = runTest {
        val dao = FakeBoardDao(profiles = emptyList())
        val repository = freshRepository(dao)
        repository.createProfile("Anna")
        repository.renameProfile(FIRST_PROFILE_ID, "Léo")

        assertEquals(listOf("Léo", "Anna"), repository.observeProfiles().first().map { it.name })
    }

    @Test
    fun `a restored profile without the open id opens the smallest`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val (content, _) = repository.snapshot()
        val renumbered = content.copy(
            profiles = listOf(ProfileEntity(5, "Léo", startBoardId = null)),
            boards = content.boards.map { it.copy(profileId = 5) },
        )

        repository.replaceAll(renumbered)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        assertEquals(5L, repository.openProfileId.value)
    }

    @Test
    fun `a restore that keeps the open profile keeps it open`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val anna = repository.createProfile("Anna")
        repository.switchProfile(anna)
        repository.ensureHomeBoard()
        val (content, _) = repository.snapshot()

        repository.replaceAll(content)

        assertEquals(dao.homeOf(anna), repository.ensureHomeBoard())
        assertEquals(anna, repository.openProfileId.value)
    }
}
