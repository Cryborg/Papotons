package com.wobblebot.papotons.data.backup

import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.photo.CELL_PHOTO_NAME
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Version du format de sauvegarde écrite par cette version de l'appli ; une plus récente est refusée. */
const val BACKUP_FORMAT_VERSION = 1

const val MANIFEST_PATH = "manifest.json"
private const val OPEN_BOARD_FORMAT = "open-board-0.1"
private const val LOCALE = "fr"

// Bornes de ce que la base peut contenir sans faire tomber l'appli : vérifiées à l'export comme à
// la restauration, pour que tout ce que l'export écrit, la restauration l'accepte.
const val MAX_BOARDS = 5_000
const val MAX_CELLS = 200_000
const val MAX_TEXT_LENGTH = 10_000
const val MAX_GRID_SIDE = 100
const val MAX_BOARD_ID = 1_000_000L

/** Images distinctes (pictos et photos) : chacune se lit et s'écrit à la restauration. */
const val MAX_IMAGES = 10_000

// Bornes de l'archive, comptées pendant la lecture (jamais la taille annoncée), et vérifiées à l'export.
const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
const val MAX_ENTRIES = 20_000
const val MAX_ENTRY_BYTES = 8L * 1024 * 1024

/** Tout le JSON d'une sauvegarde : ce que la restauration garde en mémoire. */
const val MAX_JSON_BYTES = 64L * 1024 * 1024

/** Toutes les images d'une sauvegarde, décompressées. */
const val MAX_IMAGE_BYTES = 256L * 1024 * 1024

// Clés du manifeste et des grilles (Open Board Format), et extensions propres à Papotons.
private const val KEY_FORMAT = "format"
private const val KEY_ROOT = "root"
private const val KEY_PATHS = "paths"
private const val KEY_BOARDS = "boards"
private const val KEY_IMAGES = "images"
private const val KEY_BACKUP = "ext_papotons_backup"
private const val KEY_ID = "id"
private const val KEY_LOCALE = "locale"
private const val KEY_NAME = "name"
private const val KEY_GRID = "grid"
private const val KEY_ROWS = "rows"
private const val KEY_COLUMNS = "columns"
private const val KEY_ORDER = "order"
private const val KEY_BUTTONS = "buttons"
private const val KEY_LABEL = "label"
private const val KEY_VOCALIZATION = "vocalization"
private const val KEY_IMAGE_ID = "image_id"
private const val KEY_LOAD_BOARD = "load_board"
private const val KEY_PATH = "path"
private const val KEY_CONTENT_TYPE = "content_type"
private const val KEY_LICENSE = "license"
private const val KEY_ROW = "ext_papotons_row"
private const val KEY_COLUMN = "ext_papotons_column"
private const val KEY_CATEGORY = "ext_papotons_word_category"
private const val KEY_PICTOGRAM_ID = "ext_papotons_pictogram_id"
private const val KEY_PHOTO = "ext_papotons_photo"
private const val KEY_ARASAAC_ID = "ext_papotons_arasaac_id"

/** Licence des pictos ARASAAC (invariant 5), sans adresse : aucune URL dans le code (invariant 1, `InvariantsTest`). */
private val ARASAAC_LICENSE = mapOf(
    "type" to "CC BY-NC-SA",
    "author_name" to "Sergio Palao",
    "copyright_notice" to "Pictogrammes ARASAAC, propriété du gouvernement d'Aragon",
)

/** Toutes les grilles et toutes les cases : ce qu'une sauvegarde porte et ce qu'une restauration remet. */
data class BackupContent(val boards: List<BoardEntity>, val cells: List<CellEntity>)

/** Pourquoi une sauvegarde est refusée, à l'export comme à la restauration. */
enum class RefusalReason { NotPapotons, Unreadable, TooLarge, Invalid }

/** [detail] va au journal (règle et id de grille), jamais à l'écran. */
class BackupRefusedException(val reason: RefusalReason, detail: String) : Exception(detail)

fun boardPath(boardId: Long) = "boards/$boardId.obf"

fun pictogramImagePath(pictogramId: Long) = "images/pictogram-$pictogramId.png"

fun photoImagePath(photoFile: String) = "images/photo-$photoFile"

private fun pictogramImageId(pictogramId: Long) = "pictogram-$pictogramId"

private fun photoImageId(photoFile: String) = "photo-$photoFile"

internal fun refuse(reason: RefusalReason, detail: String): Nothing = throw BackupRefusedException(reason, detail)

/**
 * Ce que la base ne peut pas contenir, et rien d'autre : un libellé long, une case hors de sa grille,
 * une catégorie inconnue, un accueil absent ou une image manquante existent en base et passent. Le
 * même contrôle tourne avant d'écrire un export : une sauvegarde de Papotons ne peut pas être
 * refusée par Papotons.
 */
fun validate(content: BackupContent) {
    val (boards, cells) = content
    // Sans grille, la base restaurée passerait pour perdue au lancement suivant. Un accueil sans
    // case, lui, existe en base (toutes les cases supprimées) et se sauvegarde.
    if (boards.isEmpty()) refuse(RefusalReason.Invalid, "No board")
    if (boards.size > MAX_BOARDS || cells.size > MAX_CELLS) refuse(RefusalReason.TooLarge, "${boards.size} boards, ${cells.size} cells")
    val boardIds = HashSet<Long>()
    for (board in boards) {
        if (board.id !in 1..MAX_BOARD_ID || !boardIds.add(board.id)) refuse(RefusalReason.Invalid, "Board id ${board.id} out of range or repeated")
        if (board.rowCount !in 1..MAX_GRID_SIDE || board.columnCount !in 1..MAX_GRID_SIDE) refuse(RefusalReason.Invalid, "Board ${board.id} size")
        checkLength(board.name, board.id)
    }
    val positions = HashSet<Triple<Long, Int, Int>>()
    for (cell in cells) {
        if (cell.boardId !in boardIds) refuse(RefusalReason.Invalid, "Cell in missing board ${cell.boardId}")
        if (!positions.add(Triple(cell.boardId, cell.row, cell.column))) refuse(RefusalReason.Invalid, "Two cells at one place in board ${cell.boardId}")
        val linked = cell.linkedBoardId
        if (linked != null && linked !in boardIds) refuse(RefusalReason.Invalid, "Folder in board ${cell.boardId} opens missing board $linked")
        // Un id nul ou négatif deviendrait un nom de fichier que le ménage ne reconnaîtrait jamais.
        cell.pictogramId?.let { if (it < 1) refuse(RefusalReason.Invalid, "Pictogram id in board ${cell.boardId}") }
        val photo = cell.photoFile
        // Le nom devient un chemin d'écriture : seul le format que l'appli écrit passe.
        if (photo != null && !CELL_PHOTO_NAME.matches(photo)) refuse(RefusalReason.Invalid, "Photo name in board ${cell.boardId}")
        checkLength(cell.label, cell.boardId)
        cell.spokenText?.let { checkLength(it, cell.boardId) }
        cell.category?.let { checkLength(it, cell.boardId) }
    }
    val imageCount = cells.mapNotNull { it.pictogramId }.toSet().size + cells.mapNotNull { it.photoFile }.toSet().size
    if (imageCount > MAX_IMAGES) refuse(RefusalReason.TooLarge, "$imageCount images")
}

private fun checkLength(text: String, boardId: Long) {
    if (text.length > MAX_TEXT_LENGTH) refuse(RefusalReason.TooLarge, "Text of ${text.length} characters in board $boardId")
}

/**
 * Les fichiers JSON de l'archive, par chemin. [pictogramsInArchive] et [photosInArchive] : les images
 * que l'archive embarque ; une case dont l'image manque garde sa référence (`ext_papotons_*`) sans `image_id`.
 */
fun backupEntries(content: BackupContent, pictogramsInArchive: Set<Long>, photosInArchive: Set<String>): Map<String, String> {
    validate(content)
    val cellsByBoard = content.cells.groupBy { it.boardId }
    val rootId = if (content.boards.any { it.id == HOME_BOARD_ID }) HOME_BOARD_ID else content.boards.minOf { it.id }
    val boardPaths = JSONObject()
    val imagePaths = JSONObject()
    val entries = LinkedHashMap<String, String>()
    for (board in content.boards) {
        val cells = cellsByBoard[board.id].orEmpty()
        val images = LinkedHashMap<String, JSONObject>()
        val buttons = JSONArray()
        for (cell in cells) {
            val button = JSONObject()
                .put(KEY_ID, cell.id.toString())
                .put(KEY_LABEL, cell.label)
                .put(KEY_ROW, cell.row)
                .put(KEY_COLUMN, cell.column)
            cell.spokenText?.let { button.put(KEY_VOCALIZATION, it) }
            cell.category?.let { button.put(KEY_CATEGORY, it) }
            cell.pictogramId?.let { button.put(KEY_PICTOGRAM_ID, it) }
            cell.photoFile?.let { button.put(KEY_PHOTO, it) }
            cell.linkedBoardId?.let { button.put(KEY_LOAD_BOARD, JSONObject().put(KEY_ID, it.toString()).put(KEY_PATH, boardPath(it))) }
            // La photo l'emporte sur le picto, comme à l'affichage.
            val photo = cell.photoFile?.takeIf { it in photosInArchive }
            val pictogram = cell.pictogramId?.takeIf { it in pictogramsInArchive }
            // Tout picto de l'archive est déclaré avec sa licence (invariant 5), même masqué par une photo.
            if (pictogram != null) {
                images.getOrPut(pictogramImageId(pictogram)) {
                    JSONObject().put(KEY_ID, pictogramImageId(pictogram)).put(KEY_PATH, pictogramImagePath(pictogram))
                        .put(KEY_CONTENT_TYPE, "image/png").put(KEY_ARASAAC_ID, pictogram).put(KEY_LICENSE, JSONObject(ARASAAC_LICENSE))
                }
            }
            if (photo != null) {
                images.getOrPut(photoImageId(photo)) {
                    JSONObject().put(KEY_ID, photoImageId(photo)).put(KEY_PATH, photoImagePath(photo))
                        .put(KEY_CONTENT_TYPE, "image/jpeg").put(KEY_PHOTO, photo)
                }
            }
            when {
                photo != null -> button.put(KEY_IMAGE_ID, photoImageId(photo))
                pictogram != null -> button.put(KEY_IMAGE_ID, pictogramImageId(pictogram))
            }
            buttons.put(button)
        }
        for ((imageId, image) in images) imagePaths.put(imageId, image.getString(KEY_PATH))
        boardPaths.put(board.id.toString(), boardPath(board.id))
        val boardJson = JSONObject()
            .put(KEY_FORMAT, OPEN_BOARD_FORMAT)
            .put(KEY_ID, board.id.toString())
            .put(KEY_LOCALE, LOCALE)
            .put(KEY_NAME, board.name)
            .put(KEY_GRID, JSONObject().put(KEY_ROWS, board.rowCount).put(KEY_COLUMNS, board.columnCount).put(KEY_ORDER, order(board, cells)))
            .put(KEY_BUTTONS, buttons)
            .put(KEY_IMAGES, JSONArray(images.values))
        entries[boardPath(board.id)] = boardJson.toString()
    }
    val manifest = JSONObject()
        .put(KEY_FORMAT, OPEN_BOARD_FORMAT)
        .put(KEY_ROOT, boardPath(rootId))
        .put(KEY_PATHS, JSONObject().put(KEY_BOARDS, boardPaths).put(KEY_IMAGES, imagePaths))
        .put(KEY_BACKUP, BACKUP_FORMAT_VERSION)
    val all = linkedMapOf(MANIFEST_PATH to manifest.toString()) + entries
    // Ce que la restauration refuserait ne s'écrit pas : une sauvegarde de Papotons se restaure toujours.
    val sizes = all.mapValues { (_, text) -> text.toByteArray(Charsets.UTF_8).size.toLong() }
    sizes.entries.firstOrNull { it.value > MAX_ENTRY_BYTES }?.let { refuse(RefusalReason.TooLarge, "${it.key}: ${it.value} bytes") }
    if (sizes.values.sum() > MAX_JSON_BYTES) refuse(RefusalReason.TooLarge, "${sizes.values.sum()} bytes of JSON")
    return all
}

/** Pour les autres lecteurs : Papotons relit les positions dans `ext_papotons_row` et `ext_papotons_column`. */
private fun order(board: BoardEntity, cells: List<CellEntity>): JSONArray {
    val byPosition = cells.associateBy { it.row to it.column }
    return JSONArray((0 until board.rowCount).map { row ->
        JSONArray((0 until board.columnCount).map { column -> byPosition[row to column]?.id?.toString() ?: JSONObject.NULL })
    })
}

/** Les chemins des grilles d'une sauvegarde de Papotons, ou un refus. */
fun readManifest(text: String): List<String> = parse {
    val manifest = JSONObject(text)
    val version = manifest.opt(KEY_BACKUP) as? Number ?: refuse(RefusalReason.NotPapotons, "No backup mark")
    if (version.toLong() !in 1..BACKUP_FORMAT_VERSION.toLong()) refuse(RefusalReason.NotPapotons, "Backup format $version")
    val paths = manifest.optJSONObject(KEY_PATHS) ?: refuse(RefusalReason.Unreadable, "No paths")
    val boards = paths.optJSONObject(KEY_BOARDS) ?: refuse(RefusalReason.Unreadable, "No board paths")
    if (boards.length() > MAX_BOARDS) refuse(RefusalReason.TooLarge, "${boards.length()} boards")
    val boardPaths = boards.keys().asSequence().map { boards.opt(it) as? String ?: refuse(RefusalReason.Unreadable, "Board path") }.toList()
    // Un même chemin listé mille fois ferait relire mille fois la même grille.
    if (boardPaths.size != boardPaths.toSet().size) refuse(RefusalReason.Invalid, "Repeated board path")
    boardPaths
}

/** Une grille et ses cases (ids de cases à zéro : la base en donne de neufs). */
fun readBoard(text: String): Pair<BoardEntity, List<CellEntity>> = parse {
    val json = JSONObject(text)
    val boardId = json.longOrNull(KEY_ID) ?: refuse(RefusalReason.Invalid, "Board without id")
    val grid = json.optJSONObject(KEY_GRID) ?: refuse(RefusalReason.Invalid, "Board $boardId without grid")
    val board = BoardEntity(
        id = boardId,
        name = json.stringOrNull(KEY_NAME) ?: refuse(RefusalReason.Invalid, "Board $boardId without name"),
        rowCount = grid.intOrNull(KEY_ROWS) ?: refuse(RefusalReason.Invalid, "Board $boardId without rows"),
        columnCount = grid.intOrNull(KEY_COLUMNS) ?: refuse(RefusalReason.Invalid, "Board $boardId without columns"),
    )
    val buttons = json.optJSONArray(KEY_BUTTONS) ?: JSONArray()
    if (buttons.length() > MAX_CELLS) refuse(RefusalReason.TooLarge, "Board $boardId: ${buttons.length()} buttons")
    val cells = (0 until buttons.length()).map { index ->
        val button = buttons.optJSONObject(index) ?: refuse(RefusalReason.Invalid, "Board $boardId: button $index")
        CellEntity(
            boardId = boardId,
            row = button.intOrNull(KEY_ROW) ?: refuse(RefusalReason.Invalid, "Board $boardId: button $index without row"),
            column = button.intOrNull(KEY_COLUMN) ?: refuse(RefusalReason.Invalid, "Board $boardId: button $index without column"),
            label = button.stringOrNull(KEY_LABEL) ?: refuse(RefusalReason.Invalid, "Board $boardId: button $index without label"),
            linkedBoardId = button.optJSONObject(KEY_LOAD_BOARD)?.let { link ->
                link.longOrNull(KEY_ID) ?: refuse(RefusalReason.Invalid, "Board $boardId: folder $index without board id")
            },
            pictogramId = button.longOrNull(KEY_PICTOGRAM_ID),
            category = button.stringOrNull(KEY_CATEGORY),
            photoFile = button.stringOrNull(KEY_PHOTO),
            spokenText = button.stringOrNull(KEY_VOCALIZATION),
        )
    }
    board to cells
}

/**
 * JSON invalide, trop imbriqué ou trop gros pour la mémoire : illisible, jamais un plantage. La classe
 * seulement au journal : le message d'`org.json` cite le texte lu (un prénom, un libellé).
 */
private inline fun <T> parse(block: () -> T): T = try {
    block()
} catch (exception: JSONException) {
    refuse(RefusalReason.Unreadable, "Invalid JSON")
} catch (error: StackOverflowError) {
    refuse(RefusalReason.Unreadable, "JSON nested too deeply")
} catch (error: OutOfMemoryError) {
    refuse(RefusalReason.TooLarge, "Out of memory while reading")
}

/** Absent : `null` ; présent mais pas du texte : refusé (jamais un `null` qui effacerait en silence). */
private fun JSONObject.stringOrNull(key: String): String? {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) return null
    return value as? String ?: refuse(RefusalReason.Invalid, "$key is not text")
}

// `opt` et conversion explicite : le org.json d'Android et celui des tests JVM ne convertissent pas
// pareil (« 12 » en texte, 12.0, grands nombres).
private fun JSONObject.longOrNull(key: String): Long? {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) return null
    val number = (value as? Int)?.toLong() ?: value as? Long ?: (value as? String)?.toLongOrNull()
    return number ?: refuse(RefusalReason.Invalid, "$key is not a whole number")
}

private fun JSONObject.intOrNull(key: String): Int? = longOrNull(key)?.let { value ->
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) refuse(RefusalReason.Invalid, "$key out of range")
    value.toInt()
}
