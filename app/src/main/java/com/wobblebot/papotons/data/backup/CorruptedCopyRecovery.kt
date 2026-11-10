package com.wobblebot.papotons.data.backup

import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.BoardOrigin
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.board.FIRST_SCREEN_POSITION
import com.wobblebot.papotons.data.board.FIRST_USER_BOARD_ID
import com.wobblebot.papotons.data.board.MAX_SCREENS
import com.wobblebot.papotons.data.board.MAX_SCREEN_POSITION
import com.wobblebot.papotons.data.board.legacyFirstScreenOf
import com.wobblebot.papotons.data.photo.CELL_PHOTO_NAME
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import com.wobblebot.papotons.data.profile.NO_PROFILE
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.data.profile.isValidProfileName
import com.wobblebot.papotons.domain.WordCategory

/** Le premier schéma avec des utilisateurs, et le premier avec des écrans. */
internal const val FIRST_SCHEMA_WITH_PROFILES = 4
private const val FIRST_SCHEMA_WITH_SCREENS = 5

/** Grilles qui doivent viser un utilisateur absent pour qu'il soit refait quand d'autres se relisent. */
private const val MIN_BOARDS_TO_REBUILD_A_PROFILE = 2

/** Les schémas que Papotons a écrits. */
val RECOVERABLE_SCHEMAS = 1..FIRST_SCHEMA_WITH_SCREENS

/** Un caractère de remplacement stocké tel quel : le texte a été abîmé avant d'être écrit (décodé ailleurs sans vérification). */
private const val REPLACEMENT_CHARACTER = '\uFFFD'

/**
 * Une ligne relue d'une copie : la valeur de chaque colonne telle que SQLite la type (`Long`,
 * `String`, `Double`, `ByteArray` ou `null`). Une colonne absente du schéma de la copie est absente.
 */
typealias CopyRow = Map<String, Any?>

/**
 * Ce que le lecteur a pu relire d'une copie, dans l'ordre des ids. [announcedCellCount] : le nombre
 * de cases que l'index annonce, `null` s'il n'a pas répondu.
 */
class CopyTables(
    val schemaVersion: Int,
    val profiles: List<CopyRow>,
    val boards: List<CopyRow>,
    val cells: List<CopyRow>,
    val announcedCellCount: Int?,
)

/**
 * Ce qui revient d'une copie. [leftOut] : les lignes relues mais écartées, par règle ; [degraded] :
 * les cases revenues sans une partie (picto, photo, catégorie, phrase). Des comptes, pour le journal.
 */
class RecoveredCopy(val content: BackupContent, val leftOut: Map<String, Int>, val degraded: Map<String, Int>)

/**
 * Les grilles qu'on peut remettre depuis les lignes relues d'une copie abîmée, `null` si rien ne
 * reste. Une ligne relue sans erreur peut être fausse : chaque ligne passe les règles de la base et
 * de l'éditeur, sinon elle reste dehors et sa place reste vide (aucune case ne glisse, invariant 3).
 * Un picto ou une photo absent de l'appareil, une catégorie inconnue, une phrase illisible : la case
 * revient sans. Un nom d'utilisateur ou de grille illisible : un nom de repli, la ligne reste. Les schémas 1 à 4 se lisent comme les formats de sauvegarde de leur époque, par
 * [backupContent], qui fait le travail des migrations (à ceci près que les grilles visées se
 * déduisent des seules cases gardées). Le premier id gagne une place disputée. Au-delà des bornes
 * de la sauvegarde, le reste est écarté : ce qui revient passe toujours [validate].
 */
suspend fun recoverContent(
    tables: CopyTables,
    isPictogramOnDevice: suspend (Long) -> Boolean,
    isPhotoOnDevice: (String) -> Boolean,
    defaultProfileName: String,
    defaultBoardName: String,
): RecoveredCopy? {
    val leftOut = LinkedHashMap<String, Int>()
    fun leaveOut(rule: String) {
        leftOut[rule] = (leftOut[rule] ?: 0) + 1
    }
    val degraded = LinkedHashMap<String, Int>()
    fun degrade(part: String) {
        degraded[part] = (degraded[part] ?: 0) + 1
    }
    // Un picto téléchargé se décode pour être vérifié : une fois par id, pas par case.
    val pictogramsOnDevice = HashMap<Long, Boolean>()
    suspend fun isPictogramKnown(id: Long): Boolean = pictogramsOnDevice[id] ?: isPictogramOnDevice(id).also { pictogramsOnDevice[id] = it }
    val hasProfiles = tables.schemaVersion >= FIRST_SCHEMA_WITH_PROFILES
    val hasScreens = tables.schemaVersion >= FIRST_SCHEMA_WITH_SCREENS

    val profiles = LinkedHashMap<Long, ProfileEntity>()
    if (hasProfiles) {
        for (row in tables.profiles) {
            val id = row.long("id")?.takeIf { it in 1..MAX_BOARD_ID } ?: run { leaveOut("profile id"); continue }
            if (id in profiles) {
                // Un id relu deux fois : l'une des deux clés est brouillée, et rien ne dit à quel enfant sont
                // les grilles. Aucun des deux noms n'est cru : jamais les grilles de l'un sous le prénom de l'autre.
                profiles[id] = profiles.getValue(id).copy(name = defaultProfileName, isExitLocked = true)
                degrade("profile id repeated")
                continue
            }
            if (profiles.size >= MAX_PROFILES) run { leaveOut("over the backup limits"); continue }
            // Un nom brouillé ne fait pas tomber l'utilisateur et toutes ses grilles : un nom de repli, à changer.
            val name = row.readableText("name")?.takeIf(::isValidProfileName) ?: defaultProfileName.also { degrade("profile name") }
            // Brouillé : protégé, comme un nouvel utilisateur. Le pire est un adulte qui doit le déverrouiller.
            val isExitLocked = row.long("is_exit_locked") != 0L
            profiles[id] = ProfileEntity(id = id, name = name, startBoardId = row.long("start_board_id"), isExitLocked = isExitLocked)
        }
    }
    // Un utilisateur que les grilles visent mais qu'on n'a pas relu (sa ligne perdue, ou sa clé brouillée
    // sans erreur) revient sous le nom de repli, protégé. Quand d'autres se relisent, seulement s'il a
    // au moins deux grilles : un utilisateur naît avec tout un vocabulaire, un `profile_id` brouillé ne
    // touche qu'une grille, et en inventerait un.
    val rebuildsEveryProfile = hasProfiles && profiles.isEmpty()
    val boardsByProfile = tables.boards.mapNotNull { it.long("profile_id") }.groupingBy { it }.eachCount()

    val boards = LinkedHashMap<Long, BoardEntity>()
    val screenPlaces = HashSet<Pair<Long, Int>>()
    // Des écrans au rang brouillé ou déjà pris : ils restent des écrans, rangés après les autres.
    val unplacedScreens = ArrayList<Long>()
    for (row in tables.boards) {
        val id = row.long("id")?.takeIf { it in 1..MAX_BOARD_ID } ?: run { leaveOut("board id"); continue }
        if (id in boards) run { leaveOut("board id repeated"); continue }
        if (boards.size >= MAX_BOARDS) run { leaveOut("over the backup limits"); continue }
        val profileId = if (hasProfiles) {
            val referenced = row.long("profile_id")
            val isWorthRebuilding = rebuildsEveryProfile || (boardsByProfile[referenced] ?: 0) >= MIN_BOARDS_TO_REBUILD_A_PROFILE
            if (referenced != null && referenced in 1..MAX_BOARD_ID && referenced !in profiles && isWorthRebuilding && profiles.size < MAX_PROFILES) {
                profiles[referenced] = ProfileEntity(id = referenced, name = defaultProfileName, startBoardId = null, isExitLocked = true)
                degrade("profile rebuilt")
            }
            referenced?.takeIf { it in profiles } ?: run { leaveOut("board profile"); continue }
        } else {
            NO_PROFILE
        }
        val rowCount = row.int("row_count")?.takeIf { it in 1..MAX_GRID_SIDE } ?: run { leaveOut("board size"); continue }
        val columnCount = row.int("column_count")?.takeIf { it in 1..MAX_GRID_SIDE } ?: run { leaveOut("board size"); continue }
        // Comme pour un utilisateur : ses cases valent plus que son nom.
        val name = row.readableText("name") ?: defaultBoardName.also { degrade("board name") }
        val isScreen = hasScreens && row["screen_position"] != null
        val screenPosition = if (isScreen) row.int("screen_position")?.takeIf { it in 0..MAX_SCREEN_POSITION && screenPlaces.add(profileId to it) } else null
        if (isScreen && screenPosition == null) unplacedScreens += id
        // Brouillé, il changerait la taille des cases et ce que les ajouts de vocabulaire visent : seul un id du vocabulaire de base passe.
        val coreBoardId = row.long("core_board_id")?.takeIf { it in 1 until FIRST_USER_BOARD_ID }
        boards[id] = BoardEntity(id, name, rowCount, columnCount, profileId, coreBoardId, screenPosition)
    }

    val cells = ArrayList<CellEntity>()
    val places = HashSet<Triple<Long, Int, Int>>()
    // Pictos (`Long`) et photos (`String`) distincts : ce que la sauvegarde compte.
    val images = HashSet<Any>()
    for (row in tables.cells) {
        val id = row.long("id") ?: run { leaveOut("cell id"); continue }
        // Pas de doublon d'id écarté : une clé brouillée en double porte une autre case, et la base en
        // donne de neuves à la restauration. Seule la place compte.
        if (cells.size >= MAX_CELLS) run { leaveOut("over the backup limits"); continue }
        val board = row.long("board_id")?.let(boards::get) ?: run { leaveOut("cell board"); continue }
        val gridRow = row.int("grid_row")?.takeIf { it in 0 until board.rowCount } ?: run { leaveOut("cell position"); continue }
        val gridColumn = row.int("grid_column")?.takeIf { it in 0 until board.columnCount } ?: run { leaveOut("cell position"); continue }
        val label = row.readableText("label") ?: run { leaveOut("cell label"); continue }
        val linkedBoardId = if (row["linked_board_id"] == null) {
            null
        } else {
            row.long("linked_board_id")?.takeIf { boards[it]?.profileId == board.profileId } ?: run { leaveOut("cell folder"); continue }
        }
        // Après toutes les règles qui écartent : une case écartée ne doit pas garder la place d'une autre.
        if (!places.add(Triple(board.id, gridRow, gridColumn))) run { leaveOut("cell place taken"); continue }
        // Au-delà du plafond d'images de la sauvegarde, la case revient sans la nouvelle image. Le picto
        // compté avant d'examiner la photo : les deux d'une même case ne passent pas à 9 999.
        fun keepsImage(image: Any) = images.size < MAX_IMAGES || image in images
        val pictogramId = row.long("pictogram_id")?.takeIf { it >= 1 && keepsImage(it) && isPictogramKnown(it) }
        pictogramId?.let(images::add)
        val photoFile = row.text("photo_file")?.takeIf { CELL_PHOTO_NAME.matches(it) && keepsImage(it) && isPhotoOnDevice(it) }
        photoFile?.let(images::add)
        if (row["pictogram_id"] != null && pictogramId == null) degrade("pictogram")
        if (row["photo_file"] != null && photoFile == null) degrade("photo")
        cells += CellEntity(
            id = id,
            boardId = board.id,
            row = gridRow,
            column = gridColumn,
            label = label,
            linkedBoardId = linkedBoardId,
            pictogramId = pictogramId,
            category = row.text("word_category")?.takeIf { WordCategory.fromStoredName(it) != null }.also { if (it == null && row["word_category"] != null) degrade("category") },
            photoFile = photoFile,
            spokenText = row.readableText("spoken_text").also { if (it == null && row["spoken_text"] != null) degrade("phrase") },
        )
    }

    val manifest = BackupManifest(
        version = when {
            hasScreens -> BACKUP_FORMAT_VERSION.toLong()
            hasProfiles -> BEFORE_SCREENS_FORMAT_VERSION.toLong()
            else -> SINGLE_PROFILE_FORMAT_VERSION.toLong()
        },
        boardPaths = emptyList(),
        profiles = if (hasProfiles) profiles.values.toList() else null,
    )
    val read = backupContent(manifest, boards.values.toList(), cells, defaultProfileName)
    val placed = withScreensForEveryone(read.boards, unplacedScreens, read.cells, ::leaveOut)
    // Sans grille, un utilisateur ne s'ouvrirait plus.
    val withBoards = placed.mapTo(HashSet()) { it.profileId }
    val keptProfiles = read.profiles.filter { it.id in withBoards }
    if (keptProfiles.isEmpty()) return null
    if (keptProfiles.size < read.profiles.size) leftOut["profile without boards"] = read.profiles.size - keptProfiles.size
    return RecoveredCopy(BackupContent(keptProfiles, placed, read.cells), leftOut, degraded)
}

/**
 * Les écrans au rang perdu ([unplacedScreens]) rangés après les autres écrans de leur utilisateur ;
 * un utilisateur dont aucun écran n'a survécu reçoit le premier écran que choisirait la migration
 * 4 → 5 parmi ses grilles relues. Une seule grille perdue ne fait donc jamais tomber tout un
 * utilisateur, et seul l'ordre des onglets change : aucune case ne bouge. Au-delà de [MAX_SCREENS]
 * écrans, les derniers rangs redeviennent de simples grilles, comptées par [leaveOut] : sinon les
 * réglages refuseraient tout nouvel écran à cet utilisateur.
 */
private fun withScreensForEveryone(boards: List<BoardEntity>, unplacedScreens: List<Long>, cells: List<CellEntity>, leaveOut: (String) -> Unit): List<BoardEntity> {
    val positions = boards.associateTo(HashMap()) { it.id to it.screenPosition }
    val linkedBoardIds = cells.mapNotNullTo(HashSet()) { it.linkedBoardId }
    for (profileBoards in boards.groupBy { it.profileId }.values) {
        var next = profileBoards.mapNotNull { it.screenPosition }.maxOrNull()?.plus(1) ?: FIRST_SCREEN_POSITION
        for (board in profileBoards.filter { it.id in unplacedScreens }) {
            if (next > MAX_SCREEN_POSITION) break
            positions[board.id] = next++
        }
        if (profileBoards.none { positions[it.id] != null }) {
            legacyFirstScreenOf(startBoardId = null, profileBoards.map { BoardOrigin(it.id, it.coreBoardId) }, linkedBoardIds)?.let { positions[it] = FIRST_SCREEN_POSITION }
        }
        for (board in profileBoards.filter { positions[it.id] != null }.sortedBy { positions[it.id] }.drop(MAX_SCREENS)) {
            positions[board.id] = null
            leaveOut("over the screen limit")
        }
    }
    return boards.map { it.copy(screenPosition = positions[it.id]) }
}

private fun CopyRow.long(column: String): Long? = this[column] as? Long

private fun CopyRow.int(column: String): Int? = long(column)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

private fun CopyRow.text(column: String): String? = this[column] as? String

/** Du texte non vide que l'éditeur aurait pu écrire, sinon `null`. */
private fun CopyRow.readableText(column: String): String? = text(column)?.takeIf { it.isNotBlank() && isReadable(it) }

/** Sans caractère de remplacement ni de contrôle (l'éditeur écrit une ligne, espaces normalisés), et dans la borne de la sauvegarde. */
private fun isReadable(text: String): Boolean =
    text.length <= MAX_TEXT_LENGTH && text.none { it == REPLACEMENT_CHARACTER || Character.isISOControl(it) }
