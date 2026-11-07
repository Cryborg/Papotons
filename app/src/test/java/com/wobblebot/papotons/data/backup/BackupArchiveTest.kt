package com.wobblebot.papotons.data.backup

import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import com.wobblebot.papotons.data.profile.MAX_PROFILE_NAME_LENGTH
import com.wobblebot.papotons.data.profile.ProfileEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val PHOTO = "0a1b2c3d-0000-4000-8000-00000000000a.jpg"
private const val MISSING_PHOTO = "0a1b2c3d-0000-4000-8000-00000000000b.jpg"
private const val DEFAULT_NAME = "Utilisateur"

class BackupArchiveTest {
    private val leo = ProfileEntity(id = 1, name = "Léo", startBoardId = 7)
    private val home = BoardEntity(id = 1, name = "Accueil", rowCount = 2, columnCount = 3, profileId = 1, coreBoardId = 1)
    private val food = BoardEntity(id = 7, name = "Manger", rowCount = 1, columnCount = 2, profileId = 1, coreBoardId = 2)

    /** Une grille inatteignable : elle existe en base, elle revient. */
    private val unreachable = BoardEntity(id = 12, name = "Ancienne", rowCount = 1, columnCount = 1, profileId = 1, coreBoardId = null)

    private fun cell(id: Long, boardId: Long, row: Int, column: Int, label: String = "c$id") = CellEntity(
        id = id, boardId = boardId, row = row, column = column, label = label, linkedBoardId = null,
        pictogramId = null, category = null, photoFile = null, spokenText = null,
    )

    private val cells = listOf(
        cell(1, 1, 0, 0, "je").copy(pictogramId = 6632, category = "PEOPLE"),
        cell(2, 1, 0, 2, "Mamie").copy(photoFile = PHOTO, pictogramId = 31, spokenText = "Mamie Jeanne"),
        cell(3, 1, 1, 1, "Manger").copy(linkedBoardId = 7, pictogramId = 4610, category = "FOOD"),
        // Hors de sa grille : gardée en base, elle revient.
        cell(4, 1, 5, 9, "x".repeat(500)).copy(category = "INCONNUE_DEMAIN"),
        // Image absente de l'appareil : la référence revient quand même.
        cell(5, 7, 0, 1, "pomme").copy(pictogramId = 999, photoFile = MISSING_PHOTO),
        cell(6, 12, 0, 0, "vieux"),
    )

    private val content = BackupContent(listOf(leo), listOf(home, food, unreachable), cells)

    private fun entries(value: BackupContent = content) =
        backupEntries(value, pictogramsInArchive = setOf(6632, 31, 4610), photosInArchive = setOf(PHOTO))

    /** La restauration telle que l'appli la fait : le manifeste, puis chaque grille, puis le validateur. */
    private fun restore(archive: Map<String, String>): BackupContent {
        val manifest = readManifest(checkNotNull(archive[MANIFEST_PATH]))
        val boards = manifest.boardPaths.map { readBoard(checkNotNull(archive[it])) }
        return backupContent(manifest, boards.map { it.first }, boards.flatMap { it.second }, DEFAULT_NAME).also(::validate)
    }

    private fun assertRefused(reason: RefusalReason, block: () -> Unit) {
        try {
            block()
            fail("Expected a refusal: $reason")
        } catch (exception: BackupRefusedException) {
            assertEquals(exception.message, reason, exception.reason)
        }
    }

    @Test
    fun `a backup comes back identical, ids and positions included`() {
        val restored = restore(entries())

        assertEquals(content.boards.toSet(), restored.boards.toSet())
        // Les ids de cases sont neufs à la restauration : tout le reste revient.
        assertEquals(content.cells.map { it.copy(id = 0) }.toSet(), restored.cells.toSet())
    }

    @Test
    fun `the archive names only the images it carries, and the photo wins`() {
        val archive = entries()
        val images = JSONObject(checkNotNull(archive[MANIFEST_PATH])).getJSONObject("paths").getJSONObject("images")
        val paths = images.keys().asSequence().map { images.getString(it) }.toSet()

        // Le picto 31, masqué par la photo de « Mamie », est déclaré aussi : il porte sa licence.
        assertEquals(setOf(pictogramImagePath(6632), pictogramImagePath(31), pictogramImagePath(4610), photoImagePath(PHOTO)), paths)
        val buttons = JSONObject(checkNotNull(archive[boardPath(1)])).getJSONArray("buttons")
        val mamie = (0 until buttons.length()).map { buttons.getJSONObject(it) }.single { it.getString("label") == "Mamie" }
        assertEquals("photo-$PHOTO", mamie.getString("image_id"))
        val pomme = JSONObject(checkNotNull(archive[boardPath(7)])).getJSONArray("buttons").getJSONObject(0)
        assertFalse(pomme.has("image_id"))
    }

    @Test
    fun `other readers find the positions in the grid order`() {
        val grid = JSONObject(checkNotNull(entries()[boardPath(1)])).getJSONObject("grid")
        val order = grid.getJSONArray("order")

        assertEquals(2, order.length())
        assertEquals("1", order.getJSONArray(0).getString(0))
        assertTrue(order.getJSONArray(0).isNull(1))
        assertEquals("2", order.getJSONArray(0).getString(2))
        assertEquals("3", order.getJSONArray(1).getString(1))
    }

    @Test
    fun `a pictogram carries the ARASAAC credit`() {
        val image = JSONObject(checkNotNull(entries()[boardPath(1)])).getJSONArray("images").getJSONObject(0)

        assertEquals("Sergio Palao", image.getJSONObject("license").getString("author_name"))
        assertEquals("CC BY-NC-SA", image.getJSONObject("license").getString("type"))
    }

    @Test
    fun `an archive from another app is refused`() {
        val manifest = JSONObject(checkNotNull(entries()[MANIFEST_PATH])).apply { remove("ext_papotons_backup") }

        assertRefused(RefusalReason.NotPapotons) { readManifest(manifest.toString()) }
    }

    @Test
    fun `a backup from a newer version is refused`() {
        val manifest = JSONObject(checkNotNull(entries()[MANIFEST_PATH])).put("ext_papotons_backup", BACKUP_FORMAT_VERSION + 1)

        assertRefused(RefusalReason.NotPapotons) { readManifest(manifest.toString()) }
    }

    @Test
    fun `broken JSON is unreadable, not a crash`() {
        assertRefused(RefusalReason.Unreadable) { readManifest("{\"ext_papotons_backup\": 1, ") }
        assertRefused(RefusalReason.Unreadable) { readBoard("[") }
    }

    @Test
    fun `a backup without boards is refused, it would look like lost boards`() {
        assertRefused(RefusalReason.Invalid) { validate(BackupContent(listOf(leo), emptyList(), emptyList())) }
        // Toutes les cases supprimées : un accueil vide existe en base, il se sauvegarde.
        validate(BackupContent(listOf(leo), listOf(home), emptyList()))
    }

    @Test
    fun `board ids out of range or repeated are refused`() {
        for (id in listOf(0L, MAX_BOARD_ID + 1)) {
            val bad = content.copy(boards = content.boards.map { if (it.id == 12L) it.copy(id = id) else it }, cells = cells.filter { it.boardId != 12L })
            assertRefused(RefusalReason.Invalid) { validate(bad) }
        }
        assertRefused(RefusalReason.Invalid) { validate(content.copy(boards = content.boards + food.copy(name = "doublon"))) }
        validate(content.copy(boards = content.boards.map { if (it.id == 12L) it.copy(id = MAX_BOARD_ID) else it }, cells = cells.map { if (it.boardId == 12L) it.copy(boardId = MAX_BOARD_ID) else it }))
    }

    @Test
    fun `grid sides out of range are refused`() {
        for ((rows, columns) in listOf(0 to 3, 2 to 0, MAX_GRID_SIDE + 1 to 3, 2 to MAX_GRID_SIDE + 1)) {
            assertRefused(RefusalReason.Invalid) { validate(content.copy(boards = listOf(home.copy(rowCount = rows, columnCount = columns), food, unreachable))) }
        }
        validate(content.copy(boards = listOf(home.copy(rowCount = MAX_GRID_SIDE, columnCount = MAX_GRID_SIDE), food, unreachable)))
    }

    @Test
    fun `two cells at one place are refused`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(cells = cells + cell(9, 7, 0, 1))) }
    }

    @Test
    fun `a folder opening a missing board is refused`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(cells = cells + cell(9, 7, 0, 0).copy(linkedBoardId = 40))) }
    }

    @Test
    fun `a cell in a missing board is refused`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(cells = cells + cell(9, 40, 0, 0))) }
    }

    @Test
    fun `a photo name that is not one the app writes is refused`() {
        for (name in listOf("../x.jpg", "Mamie.jpg", "${PHOTO.removeSuffix(".jpg")}.png")) {
            assertRefused(RefusalReason.Invalid) { validate(content.copy(cells = cells + cell(9, 7, 0, 0).copy(photoFile = name))) }
        }
    }

    @Test
    fun `a giant text is too large, a long one passes`() {
        assertRefused(RefusalReason.TooLarge) { validate(content.copy(cells = cells + cell(9, 7, 0, 0, "x".repeat(MAX_TEXT_LENGTH + 1)))) }
        validate(content.copy(cells = cells + cell(9, 7, 0, 0, "x".repeat(MAX_TEXT_LENGTH))))
    }

    @Test
    fun `the export refuses what the restore would refuse`() {
        assertRefused(RefusalReason.Invalid) { entries(content.copy(cells = cells + cell(9, 7, 0, 1))) }
    }

    @Test
    fun `numbers written as text are read, fractions are refused`() {
        val archive = entries()
        val board = JSONObject(checkNotNull(archive[boardPath(7)]))
        val button = board.getJSONArray("buttons").getJSONObject(0)

        button.put("ext_papotons_row", "7")
        assertEquals(7, readBoard(board.toString()).second.single().row)
        button.put("ext_papotons_row", 3_000_000_000L)
        assertRefused(RefusalReason.Invalid) { readBoard(board.toString()) }
        button.put("ext_papotons_row", 0.5)
        assertRefused(RefusalReason.Invalid) { readBoard(board.toString()) }
    }

    @Test
    fun `a button without its position is refused`() {
        val board = JSONObject(checkNotNull(entries()[boardPath(7)]))
        board.getJSONArray("buttons").getJSONObject(0).remove("ext_papotons_column")

        assertRefused(RefusalReason.Invalid) { readBoard(board.toString()) }
    }

    @Test
    fun `the root is the home board, or the smallest one without it`() {
        assertEquals("boards/1.obf", JSONObject(checkNotNull(entries()[MANIFEST_PATH])).getString("root"))
        val withoutHome = BackupContent(listOf(leo), listOf(food, unreachable), cells.filter { it.boardId != 1L })
        assertEquals("boards/7.obf", JSONObject(checkNotNull(entries(withoutHome)[MANIFEST_PATH])).getString("root"))
        assertEquals(withoutHome.boards.toSet(), restore(entries(withoutHome)).boards.toSet())
    }

    @Test
    fun `every text is bounded, not only the label`() {
        val giant = "x".repeat(MAX_TEXT_LENGTH + 1)
        assertRefused(RefusalReason.TooLarge) { validate(content.copy(boards = listOf(home.copy(name = giant), food, unreachable))) }
        assertRefused(RefusalReason.TooLarge) { validate(content.copy(cells = cells + cell(9, 7, 0, 0).copy(spokenText = giant))) }
        assertRefused(RefusalReason.TooLarge) { validate(content.copy(cells = cells + cell(9, 7, 0, 0).copy(category = giant))) }
    }

    @Test
    fun `a board listed twice in the manifest is refused before being read twice`() {
        val manifest = JSONObject(checkNotNull(entries()[MANIFEST_PATH]))
        manifest.getJSONObject("paths").getJSONObject("boards").put("copie", boardPath(7))

        assertRefused(RefusalReason.Invalid) { readManifest(manifest.toString()) }
    }

    @Test
    fun `deeply nested JSON is unreadable, not a crash`() {
        assertRefused(RefusalReason.Unreadable) { readBoard("{\"a\":".repeat(100_000)) }
    }

    @Test
    fun `a text field that is not text is refused, never read as missing`() {
        val board = JSONObject(checkNotNull(entries()[boardPath(7)]))
        board.getJSONArray("buttons").getJSONObject(0).put("ext_papotons_photo", 12)

        assertRefused(RefusalReason.Invalid) { readBoard(board.toString()) }
    }

    @Test
    fun `too many images are refused`() {
        val many = (1..MAX_IMAGES + 1).map { index -> cell(1000L + index, 7, index, 5).copy(pictogramId = index.toLong()) }

        assertRefused(RefusalReason.TooLarge) { validate(content.copy(cells = many)) }
    }

    @Test
    fun `the export refuses a board too large for the restore to read`() {
        val text = "x".repeat(MAX_TEXT_LENGTH)
        val big = BoardEntity(id = 50, name = "grosse", rowCount = MAX_GRID_SIDE, columnCount = MAX_GRID_SIDE)
        val bigCells = (0 until 1_000).map { index -> cell(5000L + index, 50, index / MAX_GRID_SIDE, index % MAX_GRID_SIDE, text) }

        assertRefused(RefusalReason.TooLarge) { entries(content.copy(boards = content.boards + big.copy(profileId = 1), cells = cells + bigCells)) }
    }

    @Test
    fun `a pictogram id below one is refused`() {
        for (id in listOf(0L, -5L)) {
            assertRefused(RefusalReason.Invalid) { validate(content.copy(cells = cells + cell(9, 7, 0, 0).copy(pictogramId = id))) }
        }
    }

    @Test
    fun `too many boards or cells are refused`() {
        val boards = (1L..MAX_BOARDS + 1).map { BoardEntity(id = it, name = "b$it", rowCount = 1, columnCount = 1, profileId = 1) }
        assertRefused(RefusalReason.TooLarge) { validate(BackupContent(listOf(leo), boards, boards.map { cell(it.id, it.id, 0, 0) })) }
        val manifest = JSONObject(checkNotNull(entries()[MANIFEST_PATH]))
        val paths = manifest.getJSONObject("paths").getJSONObject("boards")
        for (id in 100L..100L + MAX_BOARDS) paths.put(id.toString(), boardPath(id))
        assertRefused(RefusalReason.TooLarge) { readManifest(manifest.toString()) }
    }

    @Test
    fun `a pictogram hidden by a photo is still declared with its credit`() {
        val board = JSONObject(checkNotNull(entries()[boardPath(1)]))
        val images = board.getJSONArray("images")
        val pictogram31 = (0 until images.length()).map { images.getJSONObject(it) }.single { it.optLong("ext_papotons_arasaac_id") == 31L }

        assertEquals("Sergio Palao", pictogram31.getJSONObject("license").getString("author_name"))
    }

    @Test
    fun `every profile comes back with its boards, origins, start board and exit lock`() {
        // Léo protégé, Anna non : un aller-retour qui perdrait l'option la remettrait à protégé.
        val anna = ProfileEntity(id = 4, name = "Anna", startBoardId = null, isExitLocked = false)
        val annaHome = BoardEntity(id = 1000, name = "Accueil", rowCount = 2, columnCount = 3, profileId = 4, coreBoardId = 1)
        val twoProfiles = content.copy(profiles = listOf(leo, anna), boards = content.boards + annaHome, cells = cells + cell(20, 1000, 0, 0, "moi"))

        val restored = restore(entries(twoProfiles))

        assertEquals(twoProfiles.profiles, restored.profiles)
        assertEquals(twoProfiles.boards.toSet(), restored.boards.toSet())
        assertEquals(listOf(1L, 1L, 1L, 4L), restored.boards.sortedBy { it.id }.map { it.profileId })
    }

    private fun withExitLockOfFirstProfile(change: (JSONObject) -> Unit): Map<String, String> {
        val archive = entries().toMutableMap()
        val manifest = JSONObject(checkNotNull(archive[MANIFEST_PATH]))
        change(manifest.getJSONArray("ext_papotons_profiles").getJSONObject(0))
        archive[MANIFEST_PATH] = manifest.toString()
        return archive
    }

    /** Sauvegarde d'avant l'option : protégé, comme un nouvel utilisateur. */
    @Test
    fun `a profile without exit lock comes back locked`() {
        val absent = withExitLockOfFirstProfile { it.remove("ext_papotons_exit_locked") }
        val written = withExitLockOfFirstProfile { it.put("ext_papotons_exit_locked", JSONObject.NULL) }

        assertTrue(restore(absent).profiles.single().isExitLocked)
        assertTrue(restore(written).profiles.single().isExitLocked)
    }

    @Test
    fun `an exit lock that is not a boolean is refused, never read as missing`() {
        val archive = withExitLockOfFirstProfile { it.put("ext_papotons_exit_locked", "false") }

        assertRefused(RefusalReason.Invalid) { restore(archive) }
    }

    @Test
    fun `a backup from before profiles becomes a single profile`() {
        val archive = entries().toMutableMap()
        val manifest = JSONObject(checkNotNull(archive[MANIFEST_PATH])).put("ext_papotons_backup", 1).apply { remove("ext_papotons_profiles") }
        archive[MANIFEST_PATH] = manifest.toString()
        for (board in content.boards) {
            archive[boardPath(board.id)] = JSONObject(checkNotNull(archive[boardPath(board.id)]))
                .apply { remove("ext_papotons_profile_id"); remove("ext_papotons_core_board_id") }.toString()
        }

        val restored = restore(archive)

        assertEquals(listOf(ProfileEntity(1, DEFAULT_NAME, startBoardId = null)), restored.profiles)
        // 7 est visé par un dossier : avant les tailles, c'était un dossier de l'adulte, pas l'accueil moyen.
        assertEquals(mapOf(1L to 1L, 7L to null, 12L to 12L), restored.boards.associate { it.id to it.coreBoardId })
        assertTrue(restored.boards.all { it.profileId == 1L })
    }

    @Test
    fun `a folder that opens another profile's board is refused`() {
        val anna = ProfileEntity(id = 2, name = "Anna", startBoardId = null)
        val annaHome = BoardEntity(id = 1000, name = "Accueil", rowCount = 1, columnCount = 1, profileId = 2, coreBoardId = 1)
        val crossing = content.copy(profiles = listOf(leo, anna), boards = content.boards + annaHome, cells = cells + cell(20, 1000, 0, 0).copy(linkedBoardId = 7))

        assertRefused(RefusalReason.Invalid) { validate(crossing) }
    }

    @Test
    fun `a backup without profile is refused`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(profiles = emptyList())) }
    }

    @Test
    fun `a board of a missing profile is refused`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(boards = listOf(home, food, unreachable.copy(profileId = 3)))) }
        // Format 2 sans `ext_papotons_profile_id` : la grille arrive sans utilisateur.
        assertRefused(RefusalReason.Invalid) { validate(content.copy(boards = listOf(home, food, unreachable.copy(profileId = 0)))) }
    }

    @Test
    fun `a profile without boards is refused, it could never be opened`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(profiles = listOf(leo, ProfileEntity(2, "Anna", null)))) }
    }

    @Test
    fun `a profile name must be filled and not too long`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(profiles = listOf(leo.copy(name = " ")))) }
        assertRefused(RefusalReason.Invalid) { validate(content.copy(profiles = listOf(leo.copy(name = "x".repeat(MAX_PROFILE_NAME_LENGTH + 1))))) }
        validate(content.copy(profiles = listOf(leo.copy(name = "x".repeat(MAX_PROFILE_NAME_LENGTH)))))
    }

    @Test
    fun `profile ids are unique`() {
        assertRefused(RefusalReason.Invalid) { validate(content.copy(profiles = listOf(leo, leo))) }
    }

    @Test
    fun `the largest number of profiles passes, one more is refused`() {
        fun withProfiles(count: Int): BackupContent {
            val profiles = (1L..count).map { ProfileEntity(it, "p$it", startBoardId = null) }
            val boards = profiles.map { BoardEntity(id = it.id, name = "b", rowCount = 1, columnCount = 1, profileId = it.id) }
            return BackupContent(profiles, boards, emptyList())
        }

        validate(withProfiles(MAX_PROFILES))
        assertRefused(RefusalReason.TooLarge) { validate(withProfiles(MAX_PROFILES + 1)) }
    }

    @Test
    fun `the root for other readers is the home of the first profile`() {
        // Le premier utilisateur (id 1) a son accueil à 1001 ; la grille 1 est l'accueil d'un autre.
        val first = ProfileEntity(id = 1, name = "Léo", startBoardId = null)
        val second = ProfileEntity(id = 4, name = "Anna", startBoardId = null)
        val boards = listOf(
            BoardEntity(id = 1, name = "Accueil", rowCount = 1, columnCount = 1, profileId = 4, coreBoardId = 1),
            BoardEntity(id = 1001, name = "Accueil", rowCount = 1, columnCount = 1, profileId = 1, coreBoardId = 1),
        )

        val manifest = JSONObject(checkNotNull(entries(BackupContent(listOf(second, first), boards, emptyList()))[MANIFEST_PATH]))

        assertEquals("boards/1001.obf", manifest.getString("root"))
    }
}
