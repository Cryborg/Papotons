package com.wobblebot.papotons.data.backup

import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.board.FIRST_USER_BOARD_ID
import com.wobblebot.papotons.data.board.MAX_SCREENS
import com.wobblebot.papotons.data.board.MAX_SCREEN_POSITION
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import com.wobblebot.papotons.data.profile.MAX_PROFILE_NAME_LENGTH
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PHOTO = "0a1b2c3d-0000-4000-8000-0000000000aa.jpg"
private const val ABSENT_PHOTO = "0a1b2c3d-0000-4000-8000-0000000000bb.jpg"
private const val PICTOGRAM = 2_000L
private const val ABSENT_PICTOGRAM = 3_000L
private const val DEFAULT_NAME = "Moi"
private const val DEFAULT_BOARD_NAME = "Grille retrouvée"

private fun profile(id: Long, name: String = "Léo", isExitLocked: Long = 1L): CopyRow =
    mapOf("id" to id, "name" to name, "start_board_id" to null, "is_exit_locked" to isExitLocked)

private fun board(id: Long, profileId: Long = 1L, rows: Long = 2L, columns: Long = 3L, screenPosition: Long? = null, name: Any? = "grille $id"): CopyRow =
    mapOf(
        "id" to id, "name" to name, "row_count" to rows, "column_count" to columns,
        "profile_id" to profileId, "core_board_id" to null, "screen_position" to screenPosition,
    )

private fun cell(id: Long, boardId: Long = 1L, row: Long = 0L, column: Long = 0L, label: Any? = "mot $id"): MutableMap<String, Any?> =
    mutableMapOf(
        "id" to id, "board_id" to boardId, "grid_row" to row, "grid_column" to column, "label" to label,
        "linked_board_id" to null, "pictogram_id" to null, "word_category" to null, "photo_file" to null, "spoken_text" to null,
    )

/** Schéma 5 : Léo (écran 1, dossier 2) et Marie (écran 10). */
private fun tables(
    profiles: List<CopyRow> = listOf(profile(1), profile(2, "Marie")),
    boards: List<CopyRow> = listOf(board(1, screenPosition = 0), board(2), board(10, profileId = 2, screenPosition = 0)),
    cells: List<CopyRow> = emptyList(),
    schemaVersion: Int = 5,
    announced: Int? = null,
) = CopyTables(schemaVersion, profiles, boards, cells, announced)

private suspend fun recover(tables: CopyTables) = recoverContent(
    tables,
    isPictogramOnDevice = { it == PICTOGRAM },
    isPhotoOnDevice = { it == PHOTO },
    defaultProfileName = DEFAULT_NAME,
    defaultBoardName = DEFAULT_BOARD_NAME,
)

private suspend fun cellsOf(vararg cells: CopyRow): List<CellEntity> = recover(tables(cells = cells.toList()))!!.content.cells

/** Ce qu'on garde d'une copie abîmée, ligne par ligne. */
class CorruptedCopyRecoveryTest {
    @Test
    fun `intact rows come back as they were`() = runTest {
        val recovered = recover(tables(cells = listOf(cell(5, row = 1, column = 2).apply { put("linked_board_id", 2L) }, cell(9, boardId = 10))))!!

        assertEquals(listOf("Léo", "Marie"), recovered.content.profiles.map { it.name })
        assertEquals(listOf(1L, 2L, 10L), recovered.content.boards.map { it.id })
        assertEquals(listOf(0, null, 0), recovered.content.boards.map { it.screenPosition })
        assertEquals(
            listOf(
                CellEntity(id = 5, boardId = 1, row = 1, column = 2, label = "mot 5", linkedBoardId = 2, pictogramId = null, category = null),
                CellEntity(id = 9, boardId = 10, row = 0, column = 0, label = "mot 9", linkedBoardId = null, pictogramId = null, category = null),
            ),
            recovered.content.cells,
        )
        assertEquals(emptyMap<String, Int>(), recovered.leftOut)
    }

    @Test
    fun `a cell of a lost board is left out, and counted`() = runTest {
        val recovered = recover(tables(cells = listOf(cell(5, boardId = 7), cell(6))))!!

        assertEquals(listOf(6L), recovered.content.cells.map { it.id })
        assertEquals(mapOf("cell board" to 1), recovered.leftOut)
    }

    @Test
    fun `a cell outside its board is left out`() = runTest {
        // La grille 1 a 2 lignes et 3 colonnes : la ligne 1 et la colonne 2 sont les dernières.
        assertEquals(listOf(1L), cellsOf(cell(1, row = 1, column = 2), cell(2, row = 2), cell(3, column = 3), cell(4, row = -1)).map { it.id })
    }

    @Test
    fun `a place claimed twice keeps the first id, and a left-out cell keeps no place`() = runTest {
        val cells = cellsOf(cell(3, label = "\uFFFD"), cell(4), cell(8))

        // La 3 illisible ne prend pas la place : la 4 l'a, la 8 reste dehors. Aucune case ne glisse ailleurs.
        assertEquals(listOf(4L), cells.map { it.id })
        assertEquals(0 to 0, cells.single().row to cells.single().column)
    }

    @Test
    fun `an unreadable label leaves its cell out`() = runTest {
        val cells = cellsOf(
            cell(1, column = 0, label = "cont\uFFFD"),
            cell(2, column = 1, label = "a\u001Eb"),
            cell(3, column = 2, label = 42L),
            cell(4, row = 1, column = 0, label = "  "),
            cell(5, row = 1, column = 1, label = "x".repeat(MAX_TEXT_LENGTH + 1)),
            cell(6, row = 1, column = 2, label = "pomme"),
        )

        assertEquals(listOf("pomme"), cells.map { it.label })
    }

    @Test
    fun `an image missing from the device is dropped, the cell stays`() = runTest {
        val cells = cellsOf(
            cell(1, column = 0).apply { put("pictogram_id", PICTOGRAM); put("photo_file", PHOTO) },
            cell(2, column = 1).apply { put("pictogram_id", ABSENT_PICTOGRAM); put("photo_file", ABSENT_PHOTO) },
            // Un nom qu'on n'a pas écrit deviendrait un chemin : même présent, il part.
            cell(3, column = 2).apply { put("photo_file", "../$PHOTO"); put("pictogram_id", "2000") },
        )

        assertEquals(listOf(PICTOGRAM, null, null), cells.map { it.pictogramId })
        assertEquals(listOf(PHOTO, null, null), cells.map { it.photoFile })
    }

    @Test
    fun `an unknown category or an unreadable phrase is dropped, the cell stays`() = runTest {
        val cells = cellsOf(
            cell(1, column = 0).apply { put("word_category", "VERB"); put("spoken_text", "Je veux manger") },
            cell(2, column = 1).apply { put("word_category", "VER\uFFFD"); put("spoken_text", "Je v\u0000eux") },
        )

        assertEquals(listOf("VERB", null), cells.map { it.category })
        assertEquals(listOf("Je veux manger", null), cells.map { it.spokenText })
    }

    @Test
    fun `a folder to a lost board or to another profile's board is left out`() = runTest {
        val cells = cellsOf(
            cell(1, column = 0).apply { put("linked_board_id", 2L) },
            cell(2, column = 1).apply { put("linked_board_id", 99L) },
            cell(3, column = 2).apply { put("linked_board_id", 10L) },
        )

        assertEquals(listOf(1L), cells.map { it.id })
    }

    @Test
    fun `a board with a damaged size or profile is left out with its cells, an unreadable name is replaced`() = runTest {
        val recovered = recover(
            tables(
                boards = listOf(
                    board(1, screenPosition = 0),
                    board(2, rows = 0),
                    board(3, columns = MAX_GRID_SIDE + 1L),
                    board(4, name = "�"),
                    board(5, name = 5L),
                    board(6),
                    board(7, profileId = 9),
                    board(6, name = "double"),
                    board(10, profileId = 2, screenPosition = 0),
                ),
                cells = (2L..7L).map { cell(it, boardId = it) },
            ),
        )!!

        assertEquals(listOf(1L, 4L, 5L, 6L, 10L), recovered.content.boards.map { it.id })
        assertEquals(listOf(DEFAULT_BOARD_NAME, DEFAULT_BOARD_NAME, "grille 6"), recovered.content.boards.filter { it.id in 4L..6L }.map { it.name })
        assertEquals(listOf(4L, 5L, 6L), recovered.content.cells.map { it.id })
        assertEquals(mapOf("board name" to 2), recovered.degraded)
    }

    @Test
    fun `a screen whose place is damaged or taken stays a screen, after the others`() = runTest {
        val boards = recover(
            tables(
                boards = listOf(
                    board(1, screenPosition = 0),
                    // Le rang de l'écran 1, déjà pris ; puis un rang hors bornes, puis du texte.
                    board(5, screenPosition = 0),
                    board(6, screenPosition = 3),
                    board(7, screenPosition = MAX_SCREEN_POSITION + 1L),
                    board(8) + ("screen_position" to "1"),
                    board(9),
                    board(10, profileId = 2, screenPosition = 0),
                ),
            ),
        )!!.content.boards

        assertEquals(listOf(0, 4, 3, 5, 6, null, 0), boards.map { it.screenPosition })
    }

    @Test
    fun `a core board id that is not one of the core vocabulary is dropped, the board stays`() = runTest {
        val boards = recover(
            tables(
                boards = listOf(
                    board(1, screenPosition = 0) + ("core_board_id" to 4L),
                    board(2) + ("core_board_id" to FIRST_USER_BOARD_ID),
                    board(3) + ("core_board_id" to "4"),
                    board(10, profileId = 2, screenPosition = 0),
                ),
            ),
        )!!.content.boards

        assertEquals(listOf(4L, null, null, null), boards.map { it.coreBoardId })
    }

    @Test
    fun `a profile with an unreadable name or a repeated id comes back under the fallback name`() = runTest {
        val recovered = recover(
            tables(
                profiles = listOf(profile(1), profile(2, name = "M�rie"), profile(3, name = "x".repeat(MAX_PROFILE_NAME_LENGTH + 1)), profile(1, "double")),
                boards = listOf(board(1, screenPosition = 0), board(10, profileId = 2, screenPosition = 0), board(20, profileId = 3, screenPosition = 0)),
            ),
        )!!

        // Le 1 relu deux fois (« double ») : ni « Léo » ni « double » ne sont crus.
        assertEquals(listOf(DEFAULT_NAME, DEFAULT_NAME, DEFAULT_NAME), recovered.content.profiles.map { it.name })
        assertEquals(listOf(1L, 10L, 20L), recovered.content.boards.map { it.id })
        assertEquals(emptyMap<String, Int>(), recovered.leftOut)
        assertEquals(mapOf("profile name" to 2, "profile id repeated" to 1), recovered.degraded)
    }

    @Test
    fun `with no profile readable, the profiles the boards name come back under the fallback name, locked`() = runTest {
        val recovered = recover(
            tables(
                profiles = emptyList(),
                boards = listOf(board(1, screenPosition = 0), board(10, profileId = 2, screenPosition = 0), board(11, profileId = 0, screenPosition = 1)),
            ),
        )!!

        assertEquals(listOf(1L to DEFAULT_NAME, 2L to DEFAULT_NAME), recovered.content.profiles.map { it.id to it.name })
        assertTrue(recovered.content.profiles.all { it.isExitLocked })
        assertEquals(listOf(1L, 10L), recovered.content.boards.map { it.id })
        validate(recovered.content)
    }

    @Test
    fun `a profile whose key was garbled into an unused id still gets its boards back`() = runTest {
        // Léa (id 1) relue sous l'id 42, sans erreur : ses grilles visent toujours 1.
        val recovered = recover(
            tables(
                profiles = listOf(profile(42, "Léa")),
                boards = listOf(board(1, screenPosition = 0), board(2), board(3)),
                cells = listOf(cell(1), cell(2, boardId = 2)),
            ),
        )!!

        assertEquals(listOf(1L to DEFAULT_NAME), recovered.content.profiles.map { it.id to it.name })
        assertEquals(listOf(1L, 2L, 3L), recovered.content.boards.map { it.id })
        assertEquals(2, recovered.content.cells.size)
        validate(recovered.content)
    }

    @Test
    fun `a profile key garbled into another profile's never gives one child's boards the other's name`() = runTest {
        // Léa (id 1) relue sous l'id 2, celui de Tom : aucun des deux noms n'est cru.
        val recovered = recover(
            tables(
                profiles = listOf(profile(2, "Léa"), profile(2, "Tom")),
                boards = listOf(board(1, screenPosition = 0), board(2), board(10, profileId = 2, screenPosition = 0), board(11, profileId = 2)),
            ),
        )!!

        assertEquals(listOf(1L to DEFAULT_NAME, 2L to DEFAULT_NAME), recovered.content.profiles.sortedBy { it.id }.map { it.id to it.name })
        assertTrue(recovered.content.profiles.all { it.isExitLocked })
        assertEquals(listOf(1L, 2L, 10L, 11L), recovered.content.boards.map { it.id })
    }

    @Test
    fun `a single board naming an unknown profile invents no one`() = runTest {
        val recovered = recover(tables(boards = listOf(board(1, screenPosition = 0), board(10, profileId = 2, screenPosition = 0), board(20, profileId = 7))))!!

        assertEquals(listOf("Léo", "Marie"), recovered.content.profiles.map { it.name })
        assertEquals(mapOf("board profile" to 1), recovered.leftOut)
    }

    @Test
    fun `a damaged exit lock comes back locked`() = runTest {
        val profiles = recover(
            tables(
                profiles = listOf(profile(1, isExitLocked = 0L), profile(2, "Marie", isExitLocked = 7L), profile(3, "Zoé") + ("is_exit_locked" to "0")),
                boards = listOf(board(1, screenPosition = 0), board(10, profileId = 2, screenPosition = 0), board(20, profileId = 3, screenPosition = 0)),
            ),
        )!!.content.profiles

        assertEquals(listOf(false, true, true), profiles.map { it.isExitLocked })
    }

    @Test
    fun `a profile whose screens are all lost opens on a board that no folder opens`() = runTest {
        val recovered = recover(
            tables(
                boards = listOf(board(1, screenPosition = 0), board(11, profileId = 2), board(12, profileId = 2)),
                cells = listOf(cell(1), cell(2, boardId = 12).apply { put("linked_board_id", 11L) }),
            ),
        )!!

        assertEquals(listOf("Léo", "Marie"), recovered.content.profiles.map { it.name })
        assertEquals(listOf(0, null, 0), recovered.content.boards.map { it.screenPosition })
        assertEquals(listOf(1L, 2L), recovered.content.cells.map { it.id })
    }

    @Test
    fun `a profile without any board left is left out`() = runTest {
        val recovered = recover(tables(boards = listOf(board(1, screenPosition = 0))))!!

        assertEquals(listOf("Léo"), recovered.content.profiles.map { it.name })
        assertEquals(mapOf("profile without boards" to 1), recovered.leftOut)
    }

    @Test
    fun `nothing left to open is nothing recovered`() = runTest {
        assertNull(recover(tables(boards = listOf(board(2, profileId = 9)))))
        assertNull(recover(tables(boards = emptyList())))
    }

    @Test
    fun `beyond the backup limits the rest is left out, so the restore never refuses it`() = runTest {
        val profiles = (1L..MAX_PROFILES + 1L).map { profile(it, "p$it") }
        val boards = profiles.map { board(it["id"] as Long, profileId = it["id"] as Long, screenPosition = 0) }

        val recovered = recover(tables(profiles = profiles, boards = boards))!!

        assertEquals(MAX_PROFILES, recovered.content.profiles.size)
        validate(recovered.content)
    }

    @Test
    fun `beyond the backup's image limit a cell comes back without its new image`() = runTest {
        val side = MAX_GRID_SIDE.toLong()
        val many = CopyTables(
            schemaVersion = 5,
            profiles = listOf(profile(1)),
            boards = listOf(board(1, rows = side, columns = side, screenPosition = 0), board(2)),
            // Une image distincte par case, une de trop ; la dernière reprend une image déjà gardée.
            cells = (0 until MAX_IMAGES).map { index -> cell(index + 1L, row = index / side, column = index % side).apply { put("pictogram_id", index + 1L) } } +
                cell(MAX_IMAGES + 1L, boardId = 2).apply { put("pictogram_id", MAX_IMAGES + 1L) } +
                cell(MAX_IMAGES + 2L, boardId = 2, column = 1).apply { put("pictogram_id", 1L) },
            announcedCellCount = null,
        )

        val recovered = recoverContent(many, { true }, { true }, DEFAULT_NAME, DEFAULT_BOARD_NAME)!!

        assertEquals(MAX_IMAGES + 2, recovered.content.cells.size)
        assertEquals(listOf(null, 1L), recovered.content.cells.takeLast(2).map { it.pictogramId })
        validate(recovered.content)
    }

    @Test
    fun `a copy before the profiles comes back as one profile, its core boards recognized`() = runTest {
        val before = CopyTables(
            schemaVersion = 3,
            profiles = emptyList(),
            boards = listOf(board(1) - "profile_id" - "core_board_id" - "screen_position", board(1000) - "profile_id" - "core_board_id" - "screen_position"),
            cells = listOf(cell(1, boardId = 1000) - "pictogram_id"),
            announcedCellCount = null,
        )

        val content = recover(before)!!.content

        assertEquals(listOf(FIRST_PROFILE_ID to DEFAULT_NAME), content.profiles.map { it.id to it.name })
        assertEquals(listOf(FIRST_PROFILE_ID, FIRST_PROFILE_ID), content.boards.map { it.profileId })
        // Comme la migration 3 → 4 : l'accueil du vocabulaire en est la copie, la grille de l'adulte non.
        assertEquals(listOf(1L, null), content.boards.map { it.coreBoardId })
        assertEquals(listOf(0, null), content.boards.map { it.screenPosition })
        assertEquals(listOf(1000L), content.cells.map { it.boardId })
    }

    @Test
    fun `a copy before the screens opens on each profile's start board`() = runTest {
        val before = CopyTables(
            schemaVersion = 4,
            profiles = listOf(profile(1) + ("start_board_id" to 2L), profile(2, "Marie")),
            boards = listOf(board(1) - "screen_position", board(2) - "screen_position", board(10, profileId = 2) - "screen_position"),
            cells = emptyList(),
            announcedCellCount = null,
        )

        val boards = recover(before)!!.content.boards

        assertEquals(listOf(BoardEntity(2, "grille 2", 2, 3, 1, null, 0), BoardEntity(10, "grille 10", 2, 3, 2, null, 0)), boards.filter { it.screenPosition != null })
    }

    @Test
    fun `a damaged screen place before the screens is ignored`() = runTest {
        // Au schéma 4, la colonne n'existe pas : un reste lu par erreur ne compte pas.
        val before = CopyTables(4, listOf(profile(1)), listOf(board(1, screenPosition = 99)), emptyList(), null)

        assertEquals(listOf(0), recover(before)!!.content.boards.map { it.screenPosition })
    }

    @Test
    fun `what comes back passes the restore's own check`() = runTest {
        val recovered = recover(tables(cells = listOf(cell(1), cell(2, boardId = 2), cell(3, boardId = 10))))!!

        validate(recovered.content)
    }
    @Test
    fun `an id out of range or not a number leaves its row out, except a cell's`() = runTest {
        val recovered = recover(
            tables(
                profiles = listOf(profile(0, "Zéro"), profile(1), profile(MAX_BOARD_ID + 1, "Trop"), profile(2, "Marie"), profile(3, "Zoé") + ("id" to "3")),
                boards = listOf(board(0), board(1, screenPosition = 0), board(-3), board(MAX_BOARD_ID + 1), board(10, profileId = 2, screenPosition = 0)),
                cells = listOf(cell(1) + ("id" to null), cell(2, column = 1)),
            ),
        )!!

        assertEquals(listOf("Léo", "Marie"), recovered.content.profiles.map { it.name })
        assertEquals(listOf(1L, 10L), recovered.content.boards.map { it.id })
        // L'id d'une case ne compte pas (la base en donne un neuf) : illisible, la case revient à sa place.
        assertEquals(listOf(0L, 2L), recovered.content.cells.map { it.id })
        assertEquals(mapOf("profile id" to 3, "board id" to 3), recovered.leftOut)
        validate(recovered.content)
    }

    @Test
    fun `a cell id seen twice keeps both cells, each at its own place`() = runTest {
        // Une clé brouillée qui en double une autre : deux vraies cases, la base leur donnera des ids neufs.
        assertEquals(listOf(0, 1), cellsOf(cell(4, column = 0), cell(4, column = 1), cell(5, column = 0)).map { it.column })
    }

    @Test
    fun `beyond the backup's board limit the rest is left out`() = runTest {
        val boards = (1L..MAX_BOARDS + 1L).map { board(it, rows = 1, columns = 1, screenPosition = if (it == 1L) 0 else null) }

        val recovered = recover(tables(profiles = listOf(profile(1)), boards = boards))!!

        assertEquals(MAX_BOARDS, recovered.content.boards.size)
        assertEquals(MAX_BOARDS.toLong(), recovered.content.boards.last().id)
        validate(recovered.content)
    }

    @Test
    fun `a screen with no rank left after the last one becomes a plain board`() = runTest {
        val boards = recover(
            tables(
                profiles = listOf(profile(1)),
                boards = listOf(board(1, screenPosition = MAX_SCREEN_POSITION.toLong()), board(2, screenPosition = -1)),
            ),
        )!!.content.boards

        assertEquals(listOf(MAX_SCREEN_POSITION, null), boards.map { it.screenPosition })
    }

    @Test
    fun `a screen with no rank left is counted`() = runTest {
        val recovered = recover(
            tables(
                profiles = listOf(profile(1)),
                boards = listOf(board(1, screenPosition = MAX_SCREEN_POSITION.toLong()), board(2, screenPosition = -1)),
            ),
        )!!

        assertEquals(mapOf("over the screen limit" to 1), recovered.leftOut)
    }

    @Test
    fun `beyond the screen limit the last ranks become plain boards, and are counted`() = runTest {
        // Une case brouillée en rang d'écran sur chaque grille-dossier : plus d'onglets que l'interface n'en permet.
        val screenCount = MAX_SCREENS + 2
        val boards = (1L..screenCount).map { board(it, rows = 1, columns = 1, screenPosition = screenCount - it) }

        val recovered = recover(tables(profiles = listOf(profile(1)), boards = boards))!!

        val positions = recovered.content.boards.map { it.screenPosition }
        assertEquals(MAX_SCREENS, positions.count { it != null })
        // Les deux plus grands rangs, ceux des grilles 1 et 2, sont ceux qui tombent.
        assertEquals(listOf<Int?>(null, null), positions.take(2))
        assertEquals(mapOf("over the screen limit" to 2), recovered.leftOut)
        validate(recovered.content)
    }

    @Test
    fun `a screen rank is free or taken per profile`() = runTest {
        val boards = recover(
            tables(boards = listOf(board(1, screenPosition = 0), board(10, profileId = 2, screenPosition = 1), board(11, profileId = 2, screenPosition = 0))),
        )!!.content.boards

        assertEquals(listOf(0, 1, 0), boards.map { it.screenPosition })
    }

    @Test
    fun `a photo name the app did not write is dropped even if a file answers to it`() = runTest {
        val tables = tables(
            cells = listOf(
                cell(1, column = 0).apply { put("photo_file", "../$PHOTO"); put("pictogram_id", 0L) },
                cell(2, column = 1).apply { put("photo_file", PHOTO.uppercase()); put("pictogram_id", -5L) },
                cell(3, column = 2).apply { put("photo_file", PHOTO) },
            ),
        )

        val cells = recoverContent(tables, { true }, { true }, DEFAULT_NAME, DEFAULT_BOARD_NAME)!!.content.cells

        assertEquals(listOf(null, null, PHOTO), cells.map { it.photoFile })
        assertEquals(listOf<Long?>(null, null, null), cells.map { it.pictogramId })
    }

    @Test
    fun `a cell with a new pictogram and a new photo at the image limit keeps only what fits`() = runTest {
        val side = MAX_GRID_SIDE.toLong()
        val many = CopyTables(
            schemaVersion = 5,
            profiles = listOf(profile(1)),
            boards = listOf(board(1, rows = side, columns = side, screenPosition = 0), board(2)),
            cells = (0 until MAX_IMAGES - 1).map { index -> cell(index + 1L, row = index / side, column = index % side).apply { put("pictogram_id", index + 1L) } } +
                cell(MAX_IMAGES + 1L, boardId = 2).apply { put("pictogram_id", MAX_IMAGES + 1L); put("photo_file", PHOTO) },
            announcedCellCount = null,
        )

        val recovered = recoverContent(many, { true }, { true }, DEFAULT_NAME, DEFAULT_BOARD_NAME)!!

        assertEquals(listOf(MAX_IMAGES + 1L to null), recovered.content.cells.takeLast(1).map { it.pictogramId to it.photoFile })
        assertEquals(mapOf("photo" to 1), recovered.degraded)
        validate(recovered.content)
    }

    @Test
    fun `a part a cell comes back without is counted`() = runTest {
        val recovered = recover(
            tables(cells = listOf(cell(1).apply { put("pictogram_id", ABSENT_PICTOGRAM); put("word_category", "X"); put("spoken_text", "\u0000"); put("photo_file", ABSENT_PHOTO) })),
        )!!

        assertEquals(mapOf("pictogram" to 1, "photo" to 1, "category" to 1, "phrase" to 1), recovered.degraded)
    }

    @Test
    fun `a pictogram is checked on the device once, however many cells show it`() = runTest {
        var checks = 0
        val tables = tables(cells = (0L..2L).map { cell(it + 1, column = it).apply { put("pictogram_id", PICTOGRAM) } })

        recoverContent(tables, { checks++; true }, { true }, DEFAULT_NAME, DEFAULT_BOARD_NAME)

        assertEquals(1, checks)
    }

    @Test
    fun `text is read as strict UTF-8, its final zero dropped`() {
        assertEquals("Zoé", strictUtf8("Zoé".toByteArray() + 0))
        assertEquals("Zoé", strictUtf8("Zoé".toByteArray()))
        assertEquals("", strictUtf8(byteArrayOf()))
        // Une suite d'octets qu'un décodage sans vérification rendrait en caractère plausible.
        assertNull(strictUtf8(byteArrayOf(0x63, 0x6F, 0x6E, 0x74, 0xC9.toByte(), 0x1E)))
        assertNull(strictUtf8(byteArrayOf(0x61, 0xD4.toByte(), 0)))
        assertEquals("a\u0000", strictUtf8(byteArrayOf(0x61, 0, 0)))
    }
}
