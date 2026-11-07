#!/usr/bin/env python3
"""Prépare le vocabulaire de base de Papotons : choisit et télécharge les pictos ARASAAC,
puis génère CoreVocabulary.kt, les chaînes fr/en et l'instantané des positions.

Seul code du projet qui touche le réseau (invariant 1) : il tourne à la main, sur le poste,
jamais dans l'appli. Dépend de Pillow. Les fichiers produits sont versionnés tels quels.

Usage :
  python3 -I tools/arasaac/fetch_pictograms.py select --cache DIR [--sheet planche.png]
  python3 -I tools/arasaac/fetch_pictograms.py generate --cache DIR
  python3 -I tools/arasaac/fetch_pictograms.py boards --out DIR   (hors ligne : une image par grille,
      à sa vraie disposition, pour faire valider le vocabulaire de base au test fermé)

Règles qui protègent les grilles déjà installées (invariants 3 et 4) :
- choices.json est un verrou versionné : un mot garde son picto, et une fois livré (présent dans
  l'instantané) plus aucune surcharge ne le change ;
- generate n'efface jamais un picto des assets (une base installée peut encore le référencer) ;
- l'instantané des positions (core_vocabulary_layout.txt) ne reçoit que des ajouts, et generate
  refuse de réécrire un emplacement déjà livré.
Jusqu'à la première publication, on peut encore déplacer des cases : supprimer alors
l'instantané avant de régénérer. Ensuite, plus jamais.

Licence des pictos : Sergio Palao, ARASAAC (https://arasaac.org), CC BY-NC-SA,
propriété du gouvernement d'Aragon.
"""
import argparse
import http.client
import json
import os
import pathlib
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import NamedTuple, Optional

SEARCH_URL = "https://api.arasaac.org/v1/pictograms/fr/search/{}"
IMAGE_URL = "https://static.arasaac.org/pictograms/{0}/{0}_300.png"
ATTEMPTS = 2
RETRY_DELAY_SECONDS = 1
POLITE_DELAY_SECONDS = 0.2
TIMEOUT_SECONDS = 20
HTTP_NOT_FOUND = 404
USER_AGENT = "Papotons-vocabulary-builder"
FILE_MODE = 0o644

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = pathlib.Path(__file__).resolve().parent / "choices.json"
SNAPSHOT = ROOT / "app/src/test/resources/core_vocabulary_layout.txt"
ASSETS = ROOT / "app/src/main/assets/pictograms"
THEME = ROOT / "app/src/main/java/com/wobblebot/papotons/ui/theme/Theme.kt"
KOTLIN = ROOT / "app/src/main/java/com/wobblebot/papotons/data/board/CoreVocabulary.kt"
STRINGS_FR = ROOT / "app/src/main/res/values/strings_vocabulary.xml"

# Grilles : clé, nom fr, nom en, lignes, colonnes. L'accueil d'abord : son id doit valoir 1
# (HOME_BOARD_ID dans domain/Board.kt).
BOARDS = [
    ("home", "Accueil", "Home", 7, 8),
    ("food", "Nourriture", "Food", 4, 6),
    ("games", "Jeux", "Games", 4, 6),
    ("people", "Personnes", "People", 4, 6),
    ("feelings", "Émotions", "Feelings", 4, 6),
    ("places", "Lieux", "Places", 4, 6),
]
BOARD_IDS = {key: index + 1 for index, (key, *_rest) in enumerate(BOARDS)}
assert BOARD_IDS["home"] == 1, "l'accueil doit garder l'id 1 (HOME_BOARD_ID)"

# Catégories de la clé de Goossens modifiée (noms persistés en base : ne jamais renommer).
PEOPLE, VERB, DESCRIPTOR, NOUN, SOCIAL, QUESTION, NEGATION, LITTLE_WORD = (
    "PEOPLE", "VERB", "DESCRIPTOR", "NOUN", "SOCIAL", "QUESTION", "NEGATION", "LITTLE_WORD",
)
# Nom de la couleur dans Theme.kt pour chaque catégorie (planche contact seulement).
THEME_COLOR_NAMES = {
    PEOPLE: "People", VERB: "Verb", DESCRIPTOR: "Descriptor", NOUN: "Noun",
    SOCIAL: "Social", QUESTION: "Question", NEGATION: "Negation", LITTLE_WORD: "LittleWord",
}

# (clé, fr, en, catégorie, recherche) : « recherche » est le mot-clé ARASAAC quand il diffère
# du libellé (verbe conjugué, ponctuation).
HOME_COLUMNS = [
    # Colonne 0 : pronoms
    [("i", "je", "I", PEOPLE, None), ("you", "tu", "you", PEOPLE, None), ("he", "il", "he", PEOPLE, None),
     ("she", "elle", "she", PEOPLE, None), ("we", "nous", "we", PEOPLE, None), ("you_plural", "vous", "you all", PEOPLE, None)],
    # Colonne 1 : verbes d'intention, conjugués à la première personne
    [("want", "veux", "want", VERB, "vouloir"), ("can", "peux", "can", VERB, "pouvoir"), ("like", "aime", "like", VERB, "aimer"),
     ("am_going", "vais", "am going", VERB, "aller"), ("am", "suis", "am", VERB, "être"), ("have", "ai", "have", VERB, "avoir")],
    # Colonnes 2 et 3 : verbes d'action, à l'infinitif
    [("eat", "manger", "eat", VERB, None), ("drink", "boire", "drink", VERB, None), ("play", "jouer", "play", VERB, None),
     ("go", "aller", "go", VERB, None), ("do", "faire", "do", VERB, None), ("sleep", "dormir", "sleep", VERB, None)],
    [("look", "regarder", "look", VERB, None), ("listen", "écouter", "listen", VERB, None), ("take", "prendre", "take", VERB, None),
     ("give", "donner", "give", VERB, None), ("help", "aider", "help", VERB, None), ("stop", "arrêter", "stop", VERB, None)],
    # Colonne 4 : petits mots et négation
    [("not", "pas", "not", NEGATION, "non"), ("more", "encore", "more", LITTLE_WORD, None), ("finished", "fini", "finished", LITTLE_WORD, None),
     ("with", "avec", "with", LITTLE_WORD, None), ("here", "ici", "here", LITTLE_WORD, None), ("that", "ça", "that", LITTLE_WORD, "cela")],
    # Colonne 5 : qualificatifs
    [("good", "bien", "good", DESCRIPTOR, None), ("big", "grand", "big", DESCRIPTOR, None), ("small", "petit", "small", DESCRIPTOR, None),
     ("hot", "chaud", "hot", DESCRIPTOR, None), ("cold", "froid", "cold", DESCRIPTOR, None), ("a_lot", "beaucoup", "a lot", DESCRIPTOR, None)],
    # Colonne 6 : mots sociaux et questions
    [("yes", "oui", "yes", SOCIAL, None), ("no", "non", "no", NEGATION, None), ("thank_you", "merci", "thank you", SOCIAL, None),
     ("please", "s'il te plaît", "please", SOCIAL, "s'il te plaît"), ("what", "quoi ?", "what?", QUESTION, "quoi"), ("where", "où ?", "where?", QUESTION, "où")],
]
# Ligne 6 de l'accueil : libre dans chaque colonne, pour le vocabulaire ajouté plus tard.
# Colonne 7 : dossiers, « toilettes » (indispensable) en ligne 4, puis le dossier des lieux.
HOME_FOLDERS = [
    ("folder_food", "Nourriture", "Food", NOUN, "nourriture", "food"),
    ("folder_games", "Jeux", "Games", NOUN, "jouet", "games"),
    ("folder_people", "Personnes", "People", PEOPLE, "personnes", "people"),
    ("folder_feelings", "Émotions", "Feelings", DESCRIPTOR, "émotions", "feelings"),
    ("toilet", "toilettes", "toilet", NOUN, "toilettes", None),
    ("folder_places", "Lieux", "Places", NOUN, "lieu", "places"),
]
FOLDER_WORDS = {
    "food": [
        ("apple", "pomme", "apple", NOUN, None), ("banana", "banane", "banana", NOUN, None), ("bread", "pain", "bread", NOUN, None),
        ("cake", "gâteau", "cake", NOUN, None), ("cookie", "biscuit", "cookie", NOUN, None), ("chocolate", "chocolat", "chocolate", NOUN, None),
        ("yogurt", "yaourt", "yogurt", NOUN, None), ("cheese", "fromage", "cheese", NOUN, None), ("pasta", "pâtes", "pasta", NOUN, None),
        ("water", "eau", "water", NOUN, None), ("milk", "lait", "milk", NOUN, None), ("juice", "jus", "juice", NOUN, None),
    ],
    "games": [
        ("ball", "ballon", "ball", NOUN, None), ("puzzle", "puzzle", "puzzle", NOUN, None), ("drawing", "dessin", "drawing", NOUN, None),
        ("book", "livre", "book", NOUN, None), ("music", "musique", "music", NOUN, None), ("car", "voiture", "car", NOUN, None),
        ("doll", "poupée", "doll", NOUN, None), ("blocks", "cubes", "blocks", NOUN, None), ("bubbles", "bulles", "bubbles", NOUN, "bulles de savon"),
        ("tablet", "tablette", "tablet", NOUN, None), ("television", "télévision", "television", NOUN, None), ("outside", "dehors", "outside", DESCRIPTOR, None),
    ],
    "people": [
        ("mom", "maman", "mom", PEOPLE, None), ("dad", "papa", "dad", PEOPLE, None), ("brother", "frère", "brother", PEOPLE, None),
        ("sister", "sœur", "sister", PEOPLE, None), ("grandma", "mamie", "grandma", PEOPLE, "grand-mère"), ("grandpa", "papi", "grandpa", PEOPLE, "grand-père"),
        ("baby", "bébé", "baby", PEOPLE, None), ("friend_boy", "copain", "friend (boy)", PEOPLE, "ami"), ("friend_girl", "copine", "friend (girl)", PEOPLE, "amie"),
        ("teacher", "maîtresse", "teacher", PEOPLE, "maîtresse"), ("doctor", "docteur", "doctor", PEOPLE, "médecin"),
    ],
    "feelings": [
        ("happy", "content", "happy", DESCRIPTOR, None), ("sad", "triste", "sad", DESCRIPTOR, None), ("angry", "en colère", "angry", DESCRIPTOR, "en colère"),
        ("scared", "peur", "scared", NOUN, None), ("tired", "fatigué", "tired", DESCRIPTOR, None), ("sick", "malade", "sick", DESCRIPTOR, None),
        ("hungry", "faim", "hungry", NOUN, None), ("thirsty", "soif", "thirsty", NOUN, None), ("hurt", "mal", "hurt", NOUN, "douleur"),
        ("calm", "calme", "calm", DESCRIPTOR, None), ("surprised", "surpris", "surprised", DESCRIPTOR, None),
    ],
    "places": [
        ("house", "maison", "house", NOUN, None), ("school", "école", "school", NOUN, None), ("park", "parc", "park", NOUN, None),
        ("shop", "magasin", "shop", NOUN, None), ("bedroom", "chambre", "bedroom", NOUN, None), ("kitchen", "cuisine", "kitchen", NOUN, None),
        ("bathroom", "salle de bain", "bathroom", NOUN, None), ("garden", "jardin", "garden", NOUN, None), ("swimming_pool", "piscine", "swimming pool", NOUN, None),
        ("beach", "plage", "beach", NOUN, None), ("hospital", "hôpital", "hospital", NOUN, None), ("restaurant", "restaurant", "restaurant", NOUN, None),
    ],
}
FOLDER_COLUMNS = 6

# Identifiant ARASAAC imposé par clé de case quand le premier choix automatique ne convient pas
# (relu sur la planche contact) : doublon d'un autre mot, sens détourné, image peu parlante.
OVERRIDES = {
    "you_plural": 7305,  # le premier choix était celui de « tu »
    "am_going": 2432,  # le premier choix était celui de « aller »
    "no": 39149,  # le premier choix était la croix de « pas »
    "mom": 31148,  # le premier choix était une maman ourse
    "sister": 37966,  # le premier choix était une religieuse
    "that": 7091,  # « celui-là » était peu parlant
    "folder_places": 32598,  # le premier choix était une flèche et une croix
    "hospital": 6523,  # le premier choix portait une étoile de David
    "bedroom": 33068,  # le premier choix était une chambre d'hôtel numérotée
}

# « quoi ? » : une espace insécable garde le « ? » sur la ligne du mot, et l'appli compte les
# mots d'un libellé par les espaces ordinaires (une seule ligne pour « quoi ? »).
SPACE_BEFORE_QUESTION_MARK = " ?"
NO_BREAK_SPACE_BEFORE_QUESTION_MARK = " ?"


class Cell(NamedTuple):
    board: str
    row: int
    column: int
    key: str
    french: str
    english: str
    category: str
    search: Optional[str]
    target: Optional[str]  # grille ouverte par un dossier

    @property
    def keyword(self):
        return self.search or self.french

    @property
    def string_name(self):
        return board_string_name(self.target) if self.target else f"vocabulary_{self.key}"

    @property
    def location(self):
        return f"{BOARD_IDS[self.board]}|{self.row}|{self.column}"


def board_string_name(board_key):
    return f"vocabulary_board_{board_key}"


def board_constant(board_key):
    return f"{board_key.upper()}_BOARD_ID"


def cells():
    result = []
    for column, entries in enumerate(HOME_COLUMNS):
        for row, (key, fr, en, category, search) in enumerate(entries):
            result.append(Cell("home", row, column, key, fr, en, category, search, None))
    for row, (key, fr, en, category, search, target) in enumerate(HOME_FOLDERS):
        result.append(Cell("home", row, len(HOME_COLUMNS), key, fr, en, category, search, target))
    for board, words in FOLDER_WORDS.items():
        for index, (key, fr, en, category, search) in enumerate(words):
            result.append(Cell(board, index // FOLDER_COLUMNS, index % FOLDER_COLUMNS, key, fr, en, category, search, None))
    keys = [cell.key for cell in result]
    duplicates = sorted({key for key in keys if keys.count(key) > 1})
    if duplicates:
        sys.exit(f"Clés en double : {duplicates}")
    return result


def fetch(url):
    """Contenu de l'URL, ou None pour un 404. Toute autre erreur arrête le script en la nommant."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last_error = None
    for attempt in range(ATTEMPTS):
        time.sleep(POLITE_DELAY_SECONDS)
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == HTTP_NOT_FOUND:
                return None
            last_error = error
        except (urllib.error.URLError, http.client.HTTPException, OSError) as error:
            # Coupure en cours de lecture comprise (IncompleteRead, connexion fermée).
            last_error = error
        if attempt + 1 < ATTEMPTS:
            time.sleep(RETRY_DELAY_SECONDS)
    sys.exit(f"Échec réseau sur {url} : {last_error}")


def choose(cell):
    """Premier picto dont un mot-clé égale le mot, sans bordure de couleur, ni violence ni sexe."""
    url = SEARCH_URL.format(urllib.parse.quote(cell.keyword))
    body = fetch(url)
    try:
        results = json.loads(body) if body else []
    except json.JSONDecodeError as error:
        sys.exit(f"Réponse illisible de {url} : {error}")
    wanted = cell.keyword.lower()
    for pictogram in results:
        if pictogram.get("aacColor") or pictogram.get("violence") or pictogram.get("sex"):
            continue
        if wanted in [k.get("keyword", "").lower() for k in pictogram.get("keywords", [])]:
            return int(pictogram["_id"])
    sys.exit(f"Aucun picto convenable pour « {cell.keyword} » ({cell.key}) : ajouter une surcharge dans OVERRIDES")


def write_atomically(path, text=None, data=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    try:
        with os.fdopen(descriptor, "wb") as file:
            file.write(data if data is not None else text.encode("utf-8"))
        # mkstemp crée en 0600 : les fichiers versionnés restent lisibles par tous.
        os.chmod(temporary, FILE_MODE)
        os.replace(temporary, path)
    except BaseException:
        pathlib.Path(temporary).unlink(missing_ok=True)
        raise


def read_lock():
    return {key: int(value) for key, value in json.loads(LOCK.read_text()).items()} if LOCK.exists() else {}


def write_lock(choices):
    write_atomically(LOCK, text=json.dumps(choices, indent=1, ensure_ascii=False) + "\n")


def check_transparent(data, pictogram_id, key):
    """Fond transparent obligatoire : sinon le picto masquerait la couleur de la case."""
    import io
    from PIL import Image
    with Image.open(io.BytesIO(data)) as picture:
        rgba = picture.convert("RGBA")
        width, height = rgba.size
        if any(rgba.getpixel((x, y))[3] for x in (0, width - 1) for y in (0, height - 1)):
            sys.exit(f"Picto {pictogram_id} ({key}) sans fond transparent : choisir une surcharge")


def shipped_strings():
    """Les mots déjà livrés, par leur chaîne : la case, pas le picto (deux mots peuvent avoir le même)."""
    if not SNAPSHOT.exists():
        return set()
    return {line.split("|")[3] for line in SNAPSHOT.read_text().splitlines() if line and not line.startswith("#")}


def ensure_image(cache, pictogram_id, key):
    image = cache / f"{pictogram_id}.png"
    if image.exists():
        return
    url = IMAGE_URL.format(pictogram_id)
    data = fetch(url)
    if not data:
        sys.exit(f"Picto {pictogram_id} ({key}) introuvable ou vide : {url}")
    check_transparent(data, pictogram_id, key)
    # Validé avant d'entrer dans le cache : une réponse tronquée ne s'y installe pas.
    write_atomically(image, data=data)


def select(cache, sheet):
    """Complète le verrou : seuls les mots absents sont cherchés ; un choix se corrige par une
    surcharge jusqu'à sa livraison, jamais après."""
    choices = read_lock()
    shipped = shipped_strings()
    strings_by_key = {cell.key: cell.string_name for cell in cells()}
    for key, pictogram_id in OVERRIDES.items():
        if key in choices and choices[key] != pictogram_id:
            if strings_by_key.get(key) in shipped:
                sys.exit(f"Surcharge {key} -> {pictogram_id} refusée : {key} est déjà livré avec {choices[key]}")
            # Pas encore livré : la surcharge corrige un choix refusé (doublon, fond opaque, image absente).
            del choices[key]
    for cell in cells():
        if cell.key not in choices:
            candidate = OVERRIDES.get(cell.key) or choose(cell)
            # Image validée avant le verrou : un picto refusé ne s'y installe jamais.
            ensure_image(cache, candidate, cell.key)
            choices[cell.key] = candidate
            # Écrit à chaque choix : un arrêt plus loin ne fait perdre aucun choix déjà fait.
            write_lock(choices)
        ensure_image(cache, choices[cell.key], cell.key)
        print(f"{cell.board:9} {cell.row},{cell.column} {cell.key:16} {cell.keyword:16} -> {choices[cell.key]}")
    if sheet:
        contact_sheet(cache, choices, sheet)


def theme_colors():
    """Les fonds de catégorie lus dans Theme.kt : la planche montre les vraies couleurs."""
    found = dict(re.findall(r"private val (\w+)Background = Color\(0xFF([0-9A-Fa-f]{6})\)", THEME.read_text()))
    missing = [name for name in THEME_COLOR_NAMES.values() if name not in found]
    if missing:
        sys.exit(f"Couleurs introuvables dans {THEME} : {missing} (la planche mentirait)")
    return {category: f"#{found[name]}" for category, name in THEME_COLOR_NAMES.items()}


SHEET_TILE_PIXELS = 110
SHEET_COLUMNS = 12
SHEET_LABEL_PIXELS = 16
SHEET_MARGIN_PIXELS = 5


def contact_sheet(cache, choices, sheet):
    from PIL import Image, ImageDraw
    tile, columns, label, margin = SHEET_TILE_PIXELS, SHEET_COLUMNS, SHEET_LABEL_PIXELS, SHEET_MARGIN_PIXELS
    colors = theme_colors()
    vocabulary = cells()
    rows = (len(vocabulary) + columns - 1) // columns
    canvas = Image.new("RGB", (columns * tile, rows * (tile + label)), "white")
    draw = ImageDraw.Draw(canvas)
    for index, cell in enumerate(vocabulary):
        size = tile - 2 * margin
        image = Image.open(cache / f"{choices[cell.key]}.png").convert("RGBA").resize((size, size))
        x, y = (index % columns) * tile, (index // columns) * (tile + label)
        draw.rectangle((x, y, x + tile - 1, y + tile + label - 1), fill=colors[cell.category])
        canvas.paste(image, (x + margin, y), image)
        draw.text((x + margin, y + size), f"{cell.french} {choices[cell.key]}", fill="black")
    canvas.save(sheet)


BOARD_TILE_PIXELS = 150
BOARD_LABEL_PIXELS = 30
BOARD_TITLE_PIXELS = 50
BOARD_GAP_PIXELS = 8
BOARD_FONT = pathlib.Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
BOARD_FONT_SIZE = 18
BOARD_TITLE_FONT_SIZE = 28
EMPTY_SLOT_COLOR = "#eeeeee"


def board_images(out):
    """Chaque grille du vocabulaire de base telle qu'elle s'affiche : positions, pictos embarqués,
    couleurs de catégorie, emplacements libres. Hors ligne (pictos des assets)."""
    from PIL import Image, ImageDraw, ImageFont
    # DejaVu : la police par défaut de Pillow n'a pas « œ » (sœur).
    font = ImageFont.truetype(str(BOARD_FONT), BOARD_FONT_SIZE) if BOARD_FONT.exists() else ImageFont.load_default()
    title_font = ImageFont.truetype(str(BOARD_FONT), BOARD_TITLE_FONT_SIZE) if BOARD_FONT.exists() else font
    choices, colors, vocabulary = read_lock(), theme_colors(), cells()
    out.mkdir(parents=True, exist_ok=True)
    tile, label, gap = BOARD_TILE_PIXELS, BOARD_LABEL_PIXELS, BOARD_GAP_PIXELS
    for key, french, _english, rows, columns in BOARDS:
        width = columns * (tile + gap) + gap
        height = BOARD_TITLE_PIXELS + rows * (tile + label + gap) + gap
        canvas = Image.new("RGB", (width, height), "white")
        draw = ImageDraw.Draw(canvas)
        draw.text((gap, gap), french, fill="black", font=title_font)
        placed = {(cell.row, cell.column): cell for cell in vocabulary if cell.board == key}
        for row in range(rows):
            for column in range(columns):
                x = gap + column * (tile + gap)
                y = BOARD_TITLE_PIXELS + row * (tile + label + gap)
                cell = placed.get((row, column))
                fill = colors[cell.category] if cell else EMPTY_SLOT_COLOR
                draw.rounded_rectangle((x, y, x + tile - 1, y + tile + label - 1), radius=12, fill=fill, outline="#888888")
                if cell is None:
                    continue
                size = tile - 2 * gap
                image = Image.open(ASSETS / f"{choices[cell.key]}.png").convert("RGBA").resize((size, size))
                canvas.paste(image, (x + gap, y + gap), image)
                if cell.target:
                    # Un dossier, marqué comme dans l'appli : une icône de dossier dans le coin.
                    draw.rectangle((x + gap, y + gap + 6, x + gap + 30, y + gap + 26), fill="black")
                    draw.rectangle((x + gap, y + gap + 2, x + gap + 12, y + gap + 8), fill="black")
                text_font, size_points = font, BOARD_FONT_SIZE
                while draw.textlength(cell.french, font=text_font) > tile - gap and size_points > 10 and BOARD_FONT.exists():
                    size_points -= 1
                    text_font = ImageFont.truetype(str(BOARD_FONT), size_points)
                text_width = draw.textlength(cell.french, font=text_font)
                draw.text((x + max(0, (tile - text_width) / 2), y + tile - gap // 2), cell.french, fill="black", font=text_font)
        target = out / f"{BOARD_IDS[key]:02d}-{key}.png"
        canvas.save(target)
        print(f"{target}")


def xml_text(text):
    text = text.replace(SPACE_BEFORE_QUESTION_MARK, NO_BREAK_SPACE_BEFORE_QUESTION_MARK)
    text = text.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;")
    text = text.replace("'", "\\'").replace('"', '\\"')
    # aapt lit un @ ou un ? de tête comme une référence.
    return "\\" + text if text[:1] in ("@", "?") else text


def write_strings(path, entries, comment):
    body = "\n".join(f'    <string name="{name}">{xml_text(text)}</string>' for name, text in entries)
    header = f"    <!-- {comment} -->\n" if comment else ""
    write_atomically(path, text=f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{header}{body}\n</resources>\n')


def render_kotlin(choices, vocabulary):
    constants = "\n".join(
        f"    private const val {board_constant(key)} = {BOARD_IDS[key]}L" for key, *_rest in BOARDS if key != "home"
    )
    boards = "\n".join(
        f"            BoardEntity({board_constant(key)}, label(R.string.{board_string_name(key)}), {rows}, {columns}),"
        for key, fr, en, rows, columns in BOARDS
    )
    lines = "\n".join(
        f"            cell({board_constant(cell.board)}, {cell.row}, {cell.column}, R.string.{cell.string_name}, "
        f"{choices[cell.key]}, WordCategory.{cell.category}, {board_constant(cell.target) if cell.target else 'null'}),"
        for cell in vocabulary
    )
    return KOTLIN_TEMPLATE.format(constants=constants, boards=boards, cells=lines)


def snapshot_line(cell, pictogram_id):
    linked = BOARD_IDS[cell.target] if cell.target else "-"
    return f"{cell.location}|{cell.string_name}|{pictogram_id}|{cell.category}|{linked}"


def checked_snapshot(choices, vocabulary):
    """L'instantané plus les cases nouvelles ; s'arrête si un emplacement livré changerait."""
    existing = SNAPSHOT.read_text().splitlines() if SNAPSHOT.exists() else []
    shipped = {"|".join(line.split("|")[:3]): line for line in existing if line and not line.startswith("#")}
    added = []
    for cell in vocabulary:
        line = snapshot_line(cell, choices[cell.key])
        if cell.location not in shipped:
            added.append(line)
        elif shipped[cell.location] != line:
            sys.exit(f"Emplacement déjà livré modifié ({cell.key}) :\n  livré   {shipped[cell.location]}\n  nouveau {line}")
    header = [] if existing else ["# grille|ligne|colonne|chaîne|picto|catégorie|grille ouverte : ajouts seulement (invariant 3)"]
    return "\n".join(existing + header + added) + "\n"


def generate(cache):
    choices = read_lock()
    vocabulary = cells()
    missing = [cell.key for cell in vocabulary if cell.key not in choices]
    if missing:
        sys.exit(f"Verrou incomplet, lancer select d'abord : {missing}")
    images = {pictogram_id: cache / f"{pictogram_id}.png" for pictogram_id in choices.values()}
    absent = [str(path) for path in images.values() if not (ASSETS / path.name).exists() and not path.exists()]
    if absent:
        sys.exit(f"Pictos absents du cache (lancer select avec le même --cache) : {absent}")
    snapshot = checked_snapshot(choices, vocabulary)

    # Ajout seulement : un picto déjà livré reste, une base installée peut le référencer.
    for pictogram_id, path in sorted(images.items()):
        target = ASSETS / path.name
        if not target.exists():
            data = path.read_bytes()
            # Revérifié ici : un cache rempli par une version antérieure du script n'a pas été contrôlé.
            check_transparent(data, pictogram_id, path.stem)
            write_atomically(target, data=data)

    french = [(board_string_name(key), fr) for key, fr, *_rest in BOARDS]
    # Appli en français seulement : les noms anglais de la spécification attendent la version anglaise.
    for cell in vocabulary:
        if cell.target is None:
            french.append((cell.string_name, cell.french))
    write_strings(STRINGS_FR, french, comment="Vocabulaire de base (généré par tools/arasaac/fetch_pictograms.py).")
    write_atomically(KOTLIN, text=render_kotlin(choices, vocabulary))
    write_atomically(SNAPSHOT, text=snapshot)
    print(f"{len(images)} pictos, {len(vocabulary)} cases, {len(BOARDS)} grilles")


KOTLIN_TEMPLATE = """package com.wobblebot.papotons.data.board

import androidx.annotation.StringRes
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.domain.WordCategory

// Généré par tools/arasaac/fetch_pictograms.py : modifier la spécification du script, pas ce fichier.

/**
 * Vocabulaire de base inséré au premier lancement, en français. Chaque case
 * porte son picto ARASAAC (fichier `assets/pictograms/<id>.png`) et sa catégorie de mot.
 * Une fois publiées, ces positions deviennent le plan moteur des utilisateurs : on ajoute dans
 * les emplacements libres, on ne déplace jamais (invariant 3).
 */
object CoreVocabulary {{
{constants}

    fun build(label: (Int) -> String): VocabularySeed {{
        fun cell(
            boardId: Long,
            row: Int,
            column: Int,
            @StringRes text: Int,
            pictogramId: Long,
            category: WordCategory,
            linkedBoardId: Long?,
        ) = CellEntity(
            boardId = boardId,
            row = row,
            column = column,
            label = label(text),
            linkedBoardId = linkedBoardId,
            pictogramId = pictogramId,
            category = category.name,
        )

        val boards = listOf(
{boards}
        )
        val cells = listOf(
{cells}
        )
        return VocabularySeed(boards, cells)
    }}
}}
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("step", choices=["select", "generate", "boards"])
    parser.add_argument("--cache", type=pathlib.Path)
    parser.add_argument("--sheet", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path)
    arguments = parser.parse_args()
    if arguments.step == "boards":
        if arguments.out is None:
            parser.error("boards demande --out")
        board_images(arguments.out)
        return
    if arguments.cache is None:
        parser.error(f"{arguments.step} demande --cache")
    arguments.cache.mkdir(parents=True, exist_ok=True)
    if arguments.step == "select":
        select(arguments.cache, arguments.sheet)
    else:
        generate(arguments.cache)


if __name__ == "__main__":
    main()
