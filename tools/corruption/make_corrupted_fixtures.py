#!/usr/bin/env python3
"""Fabrique les bases Papotons abîmées que lit DatabaseCorruptionFixturesTest (TODO-8).

Une base saine au schéma Room courant (le JSON le plus récent de app/schemas/, identity hash
compris : Room l'ouvre comme une base de l'appli), puis une copie par panne, chacune abîmée d'une
seule façon. Tout est déterministe (graine fixe) : relancer le script redonne les mêmes octets.
Les fichiers produits sont versionnés tels quels et jamais édités à la main.

Usage : python3 -I tools/corruption/make_corrupted_fixtures.py

Les numéros de pages ne sont pas en dur : le script les retrouve dans sqlite_master et en
parcourant l'arbre de la table `cells`. Le test vérifie les comptes de lignes écrits ici
(PROFILE_IDS, BOARDS, cases par grille) : les changer oblige à revoir ses constats.
"""
import json
import pathlib
import random
import sqlite3
import struct
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "app/schemas/com.wobblebot.papotons.data.PapotonsDatabase"
OUTPUT = ROOT / "app/src/androidTest/assets/corrupted-databases"
PAGE_SIZE = 4096
SEED = 20261107
FILE_MODE = 0o644

# Deux utilisateurs, comme une famille avec deux enfants ; ids de grilles comme l'appli les donne
# (1 à 999 pour le premier, 1000 et plus ensuite).
PROFILES = [(1, "Léo"), (2, "Inès")]
# (id, nom, lignes, colonnes, utilisateur, position d'écran ou None pour un dossier)
BOARDS = [
    (1, "Accueil", 6, 8, 1, 0),
    (2, "Manger", 4, 6, 1, None),
    (3, "Jouer", 4, 6, 1, None),
    (4, "École", 6, 8, 1, 1),
    (1000, "Accueil", 4, 5, 2, 0),
    (1001, "Émotions", 3, 4, 2, None),
    (1002, "Maison", 6, 8, 2, 1),
]
WORDS = ["je", "tu", "vouloir", "manger", "boire", "jouer", "encore", "fini", "pas", "oui",
         "maman", "papa", "dehors", "dormir", "aider", "aimer", "gâteau", "eau", "ballon", "livre",
         "content", "triste", "fatigué", "où ?", "quoi ?", "avec", "dans", "grand", "petit", "chaud"]
CATEGORIES = ["PEOPLE", "VERB", "DESCRIPTOR", "NOUN", "SOCIAL", "QUESTION", "NEGATION", "LITTLE_WORD", None]


def latest_schema() -> dict:
    path = max(SCHEMAS.glob("*.json"), key=lambda p: int(p.stem))
    return json.loads(path.read_text(encoding="utf-8"))["database"]


def create_healthy(path: pathlib.Path) -> None:
    schema = latest_schema()
    path.unlink(missing_ok=True)
    connection = sqlite3.connect(path)
    connection.execute(f"PRAGMA page_size = {PAGE_SIZE}")
    # Comme SQLiteOpenHelper : la table de la langue, puis la version du schéma.
    connection.execute("CREATE TABLE android_metadata (locale TEXT)")
    connection.execute("INSERT INTO android_metadata VALUES ('fr_FR')")
    for entity in schema["entities"]:
        name = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", name))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", name))
    for query in schema["setupQueries"]:
        connection.execute(query)
    connection.execute(f"PRAGMA user_version = {schema['version']}")

    connection.executemany("INSERT INTO profiles (id, name, start_board_id, is_exit_locked) VALUES (?, ?, NULL, 1)", PROFILES)
    connection.executemany(
        "INSERT INTO boards (id, name, row_count, column_count, profile_id, core_board_id, screen_position) VALUES (?, ?, ?, ?, ?, NULL, ?)",
        BOARDS,
    )
    names = random.Random(SEED)
    word = 0
    for board_id, _, rows, columns, _, _ in BOARDS:
        for row in range(rows):
            for column in range(columns):
                label = WORDS[word % len(WORDS)]
                connection.execute(
                    "INSERT INTO cells (board_id, grid_row, grid_column, label, linked_board_id, pictogram_id, word_category, photo_file, spoken_text)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        board_id, row, column, label, linked_board(board_id, row, column), 2000 + word,
                        CATEGORIES[word % len(CATEGORIES)],
                        # Une case sur cinq porte une photo prise à l'appareil, nommée comme CellPhotos.
                        f"{uuid.UUID(int=names.getrandbits(128), version=4)}.jpg" if word % 5 == 0 else None,
                        # Une sur trois dit une phrase à elle.
                        f"Je voudrais {label}, s'il te plaît" if word % 3 == 0 else None,
                    ),
                )
                word += 1
    connection.commit()
    connection.execute("VACUUM")
    connection.close()
    path.chmod(FILE_MODE)


def linked_board(board_id: int, row: int, column: int) -> int | None:
    """Les deux premières cases de l'accueil de Léo ouvrent ses dossiers."""
    if board_id == 1 and row == 0 and column < 2:
        return 2 + column
    return None


def root_pages(path: pathlib.Path) -> dict[str, int]:
    connection = sqlite3.connect(path)
    pages = dict(connection.execute("SELECT name, rootpage FROM sqlite_master WHERE rootpage > 0"))
    connection.close()
    return pages


def table_leaves(data: bytes, root: int) -> list[int]:
    """Feuilles d'un arbre de table, de gauche à droite."""
    offset = (root - 1) * PAGE_SIZE
    header = offset + (100 if root == 1 else 0)
    kind = data[header]
    if kind == 0x0D:
        return [root]
    if kind != 0x05:
        raise ValueError(f"page {root}: not a table b-tree page ({kind:#x})")
    cell_count = struct.unpack(">H", data[header + 3:header + 5])[0]
    right_most = struct.unpack(">I", data[header + 8:header + 12])[0]
    pointers = [struct.unpack(">H", data[header + 12 + 2 * i:header + 14 + 2 * i])[0] for i in range(cell_count)]
    children = [struct.unpack(">I", data[offset + pointer:offset + pointer + 4])[0] for pointer in pointers]
    return [leaf for child in children + [right_most] for leaf in table_leaves(data, child)]


def overwrite_page(data: bytearray, page: int, start: int, content: bytes) -> None:
    offset = (page - 1) * PAGE_SIZE + start
    data[offset:offset + len(content)] = content


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    healthy_path = OUTPUT / "healthy.db"
    create_healthy(healthy_path)
    healthy = healthy_path.read_bytes()
    pages = root_pages(healthy_path)
    cell_leaves = table_leaves(healthy, pages["cells"])
    if len(cell_leaves) < 3:
        raise SystemExit(f"cells spans {len(cell_leaves)} leaves: add cells, the variants need at least 3")
    generator = random.Random(SEED)

    variants: dict[str, bytearray] = {}

    # L'en-tête « SQLite format 3 » effacé : SQLite ne reconnaît plus le fichier.
    broken = bytearray(healthy)
    broken[0:16] = bytes(16)
    variants["header-zeroed"] = broken

    # La page de l'index unique des cases (position dans la grille) remplie d'octets aléatoires.
    broken = bytearray(healthy)
    overwrite_page(broken, pages["index_cells_board_id_grid_row_grid_column"], 0, generator.randbytes(PAGE_SIZE))
    variants["cells-index-page-random"] = broken

    # La page de la table des grilles remplie d'octets aléatoires : la première requête de l'appli la lit.
    broken = bytearray(healthy)
    overwrite_page(broken, pages["boards"], 0, generator.randbytes(PAGE_SIZE))
    variants["boards-page-random"] = broken

    # Une feuille de la table des cases dont la seconde moitié (là où vivent les lignes) est à zéro.
    broken = bytearray(healthy)
    overwrite_page(broken, cell_leaves[1], PAGE_SIZE // 2, bytes(PAGE_SIZE // 2))
    variants["cells-page-half-zeroed"] = broken

    # Trois rafales de 16 octets aléatoires dans une autre feuille de la table des cases.
    broken = bytearray(healthy)
    for _ in range(3):
        overwrite_page(broken, cell_leaves[-1], generator.randrange(PAGE_SIZE // 2, PAGE_SIZE - 16), generator.randbytes(16))
    variants["cells-page-random-bytes"] = broken

    # Fichier coupé au milieu d'une page, comme une copie interrompue.
    variants["file-cut-in-half"] = bytearray(healthy[: len(healthy) // 2 + PAGE_SIZE // 3])

    for name, content in variants.items():
        path = OUTPUT / f"{name}.db"
        path.write_bytes(bytes(content))
        path.chmod(FILE_MODE)
    print(f"{len(healthy) // PAGE_SIZE} pages, roots {pages}, cells leaves {cell_leaves}")


if __name__ == "__main__":
    main()
