# MVP-2 : vocabulaire de base, pictos ARASAAC, code couleur Fitzgerald

Source : `docs/TODO.md` § Feuille de route, MVP-2. Choix de Franck (2026-11-06) : le nombre de cases réglable (comme dans les logiciels de référence, pour les malvoyants et les débutants) devient un MVP à part (MVP-8) ; le MVP-2 livre la taille standard. Pris par défaut, sans objection : élision minimale « je » → « j' », code couleur de Goossens modifié.

## 1. Contrat et fichiers touchés

### Comportement

- **Vocabulaire de base** pour toute nouvelle installation, à la place de la démo : un accueil de **7 lignes × 8 colonnes** et quatre dossiers de 4 × 6 (Nourriture, Jeux, Personnes, Émotions). Environ 95 mots, chacun avec son picto ARASAAC.
- **Disposition de l'accueil** (planification motrice) : une colonne par fonction, de gauche à droite : pronoms, verbes d'intention (`veux`, `peux`, `aime`, `vais`, `suis`, `ai`), deux colonnes de verbes d'action à l'infinitif, petits mots et négation, qualificatifs, mots sociaux et questions, dossiers (et `toilettes`, indispensable). Chaque colonne a 6 mots et **une case libre en bas** ; une place reste libre sous les dossiers. Le vocabulaire ajouté plus tard (MVP-3) s'y place sans rien déplacer (invariant 3).
- **Verbes** : verbes d'intention conjugués à la première personne, verbes d'action à l'infinitif (« je veux manger »). La conjugaison selon le sujet relève d'IDEE-1.
- **Élision minimale** : dans la phrase affichée et dite, « je » devant un verbe conjugué qui commence par une voyelle devient « j' » collé au verbe (« j'ai faim », « j'aime »). Devant un infinitif (« je aller ») ou un autre mot (« je eau ») rien ne change : la voix dit ce qui a été touché. Le message garde les mots touchés (libellé et catégorie) ; une fonction pure en tire les mots affichés et dits. Le h et les conjugaisons en -re (« j'ouvre ») attendent IDEE-1. « Effacer le dernier mot » retire toujours le dernier mot touché.
- **Code couleur de Goossens modifié** : le fond de chaque case prend la couleur de sa catégorie : personnes et pronoms jaune, verbes vert, qualificatifs bleu, noms orange, social rose, questions violet, négation rouge (`pas`, `non`), petits mots gris. Une case sans catégorie (démo du MVP-1) garde le fond neutre.
- **Picto et libellé dans la case** : le picto occupe la part haute (`ContentScale.Fit`), le libellé la part basse, chacun avec un poids fixe : la taille adaptative du libellé ne peut plus écraser le picto, et la place du picto est réservée avant même que l'image ne soit décodée. Sous un picto, le libellé est plafonné pour que les mots courts aient tous la même taille. Un dossier prend la couleur de sa catégorie, avec un contour épais et une marque de dossier dans le coin, au-dessus du picto qui descend d'autant (devant le libellé, elle ne laissait plus de place au mot sur téléphone). Le picto est décoratif pour TalkBack (le libellé est lu). Une case sans picto reste en texte seul, centrée.
- **Crédits visibles** (invariant 5) : un bouton « À propos » (icône i) à droite de la barre du haut ouvre une modale défilante : crédit ARASAAC (texte ci-dessous), icônes Material sous Apache 2.0, appli sous GPL-3.0, ligne de version (`AppVersionLine` retrouve un écran). La barre garde sa hauteur fixe.

Texte du crédit, en français et en anglais : « Pictogrammes : Sergio Palao. Origine : ARASAAC (https://arasaac.org). Licence : CC BY-NC-SA 4.0 (creativecommons.org/licenses/by-nc-sa/4.0). Propriété : Gouvernement d'Aragon (Espagne). » Adresses en texte, non cliquables (on ne sort pas de l'appli).

### Données (Room, version 2)

- `cells` gagne deux colonnes nullables : `pictogram_id` (identifiant ARASAAC) et `word_category` (texte : nom de la catégorie). `AutoMigration(1 → 2)`, schéma `2.json` exporté.
- `WordCategory` (domaine) : `PEOPLE`, `VERB`, `DESCRIPTOR`, `NOUN`, `SOCIAL`, `QUESTION`, `NEGATION`, `LITTLE_WORD`. Stocké en `String` dans l'entité (pas de convertisseur Room, qui lèverait sur un nom inconnu). Lecture tolérante : un nom inconnu (base d'une version future) donne `null` et un seul `Log.w` par nom. **Un nom ne se renomme ni ne se retire jamais** (test qui fige la liste).
- `Cell` gagne `pictogramId: Long? = null` et `category: WordCategory? = null`.
- Une base déjà amorcée (démo) n'est pas touchée (invariant 4). L'appli n'est pas publiée : seules des bases de développement ont la démo.

### Pictos

- Choisis et téléchargés une fois par `tools/arasaac/fetch_pictograms.py` (seul code réseau du projet, lancé à la main). Recherche française par mot-clé, premier résultat dont un mot-clé égale le mot, sans bordure de couleur, violence ni sexe ; sinon le script s'arrête en nommant le mot. Surcharges relues sur planche contact.
- **Verrou versionné** `tools/arasaac/choices.json` (clé de case → id) : `select` ne cherche que les mots absents du verrou ; relancer le script ne change jamais un choix existant.
- **Un picto livré ne se retire jamais** : `generate` ajoute des fichiers, n'en supprime aucun. Une base installée garde ses `pictogram_id` pour toujours.
- Le script vérifie que chaque PNG a un fond transparent (coins alpha nul) : sinon la couleur de la case serait masquée. Planche contact sur fond de couleur de catégorie.
- Fichiers : `app/src/main/assets/pictograms/<id>.png` (300 px). README § Pictogrammes : ce dossier est sous CC BY-NC-SA 4.0, pas sous GPL.

### Chargement des images

- `PictogramImages` (`@Singleton`) décode tous les pictos embarqués une fois, lancé par `PapotonsApplication` au démarrage (sur `Dispatchers.IO`, pendant le carton du studio), puis les publie d'un bloc dans un état Compose : une seule recomposition des cases.
- Décodage en `ARGB_8888` (pas `HARDWARE`, en panne sur Android 8), à demi-résolution (`inSampleSize = 2`) : les pictos s'affichent bien plus petits que leurs 300 px.
- Tout échec (liste, lecture, décodage, mémoire) est journalisé ; la case garde son libellé et sa zone de picto reste vide ; les images déjà décodées sont publiées quoi qu'il arrive ; le scope de l'Application a un gestionnaire d'erreur : une image ne peut pas empêcher de parler.
- Exposé à l'écran par un `CompositionLocal` dont la valeur par défaut ne rend aucune image (aperçus et tests sans fournisseur).

### Fichiers

Nouveaux :
- `domain/WordCategory.kt`, `domain/Elision.kt` (assemblage de la phrase)
- `data/board/CoreVocabulary.kt` (généré, versionné ; remplace `DemoBoards.kt`), `data/board/VocabularySeed.kt`
- `data/pictogram/PictogramImages.kt`, `ui/board/LocalPictogramImages.kt`, `ui/about/AboutDialog.kt`
- `res/values*/strings_vocabulary.xml` (remplace `strings_demo.xml`), `res/values*/strings_about.xml`, `res/drawable/ic_info.xml`
- `app/src/main/assets/pictograms/*.png`, `app/schemas/.../2.json`, `tools/arasaac/fetch_pictograms.py`, `tools/arasaac/choices.json`
- Tests : `CoreVocabularyTest`, `CoreVocabularyLayoutSnapshotTest` + `app/src/test/resources/core_vocabulary_layout.txt`, `WordCategoryTest`, `ElisionTest`, `CreditStringsTest`, `ThemeColorsTest`, `ResourceStrings.kt` (lecture des vraies chaînes en JVM) ; instrumentés : `MigrationTest`, `PictogramImagesTest`

Modifiés : `CellEntity`, `PapotonsDatabase` (v2, `autoMigrations`), `BoardWithCells`, `domain/Board.kt`, `BoardRepository`, `BoardViewModel` (phrase élidée), `CommunicationGrid`, `MessageBar`, `BoardTopBar`, `BoardScreen`, `MainActivity`, `ui/theme/Theme.kt` (huit couleurs de catégorie), `libs.versions.toml` + `app/build.gradle.kts` (`room-testing` en `androidTestImplementation`), `README.md`, `docs/TODO.md` (MVP-2 supprimé ; MVP-5 réduit à la politique de confidentialité ; MVP-8 ajouté), tests existants.

Supprimés : `DemoBoards.kt`, `DemoBoardsTest.kt`, `strings_demo.xml` (fr, en).

### Invariants

1. Aucun réseau à l'exécution ; `room-testing` en test instrumenté seulement.
2. Pictos dans l'APK.
3. Positions figées par un test d'instantané (on ajoute, on ne déplace jamais) ; places libres réservées ; picto et couleur ne changent pas la taille des cases.
4. Migration additive testée ; base existante jamais remplacée ; picto livré jamais retiré ; nom de catégorie jamais renommé.
5. Crédit ARASAAC visible dès ce lot.

## 2. Envisagé et écarté

- **Nombre de cases réglable** : MVP-8 (choix de Franck). Le générateur range les mots par colonne de fonction et par importance, ce qui permettra d'en dériver de plus petites tailles avec leurs propres positions fixes.
- **Remplacer la démo dans les bases existantes** : écarté (invariant 4).
- **Pictos en `drawable`** : écarté (`isShrinkResources` retire ce que le code ne référence pas ; l'id ARASAAC sert tel quel au MVP-3).
- **Coil ou Glide** : écarté (un décodage d'asset et un cache suffisent).
- **Décodage par case à l'affichage** : écarté (les pictos apparaîtraient après coup) ; **préchargement dans le ViewModel** : écarté (allongerait la fenêtre où un appui tombe sur l'ancienne grille).
- **Verbes tous à l'infinitif** : écarté pour les verbes d'intention.
- **Élision complète** (h muet, « le » → « l' », etc.) : IDEE-1.
- **Colonne `pictogram_id` générique** (photos du MVP-7, Global Symbols) : une colonne nullable de plus coûtera peu le moment venu.
- **Personnes en orange avec les noms** : écarté, la clé de Goossens les met en jaune avec les pronoms.
- **Mots fréquents en plus** (`c'est`, `on`, `moi`, `là`, `mettre`, `ouvrir`, `qui ?`) : gardés pour la validation avec des orthophonistes (MVP-6), pour ne pas consommer les places libres.
- **Langue des libellés figée à l'installation** : limite connue (TODO-5 traite déjà la voix).
- **Taille sur téléphone** : l'accueil fait environ 45 dp de large par case sur téléphone ; la tablette d'abord (`docs/ETUDE.md`, Play Store), MVP-8 apportera une taille réduite.

## 3. Observabilité

| Étape | Échec | Signal |
|---|---|---|
| Préparation : recherche | mot sans picto convenable, recherche sans résultat | le script s'arrête en nommant le mot |
| Préparation : téléchargement | code HTTP ≠ 200, fichier vide, fond opaque | le script s'arrête avec l'URL, le code ou l'id |
| Exécution : picto illisible | asset corrompu, liste des assets en échec | `Log.w` ou `Log.e` avec l'id, case en texte ; bilan `Log.i` du nombre décodé ; `PictogramImagesTest` l'attrape avant |
| Exécution : picto cité mais absent | asset retiré | case en texte, sans journal ; `CoreVocabularyTest` et l'instantané l'attrapent avant (à revoir au MVP-3 avec les pictos téléchargés) |
| Catégorie inconnue | base d'une version future | un `Log.w` par nom (couche données), case sans couleur |
| Migration 1 → 2 | schéma incompatible | Room lève à l'ouverture ; `MigrationTest` l'attrape avant |

## 4. Retries et limites

Réseau seulement dans le script : `ATTEMPTS` essais par requête avec une pause entre deux, une pause de politesse avant chaque requête, aucune concurrence (API gratuite sans clé). Un 404 de recherche vaut « aucun résultat » ; toute autre erreur arrête le script en citant l'URL et la cause. Le verrou est écrit après chaque choix et les images validées avant d'entrer dans le cache : un arrêt en cours ne perd rien et le script se relance sans risque.

## 5. Plan de test

- `CoreVocabularyTest` : cases dans leur grille, aucun emplacement partagé, liens de dossiers valides, accueil `HOME_BOARD_ID`, chaque case a une catégorie, chaque `pictogram_id` a son fichier dans `src/main/assets/pictograms`, chaque colonne de l'accueil garde une case libre en bas.
- `CoreVocabularyLayoutSnapshotTest` : chaque ligne de l'instantané (grille, ligne, colonne, ressource, picto, catégorie) existe à l'identique dans le vocabulaire généré. Rougit si une case bouge, change de picto ou de catégorie ; une case ajoutée passe.
- `WordCategoryTest` : la liste figée des noms persistés existe toujours dans l'enum ; un nom inconnu donne `null`.
- `ElisionTest` : formes conjuguées élidées (« j'ai », « j'aime », « j'écoute »), infinitifs et non-verbes inchangés, h, majuscule, deux « je » ; et chaque verbe français du vocabulaire qui commence par une voyelle, lu dans les vraies chaînes.
- `BoardRepositoryTest` : `toDomain` avec une case complète, une case nulle et une catégorie `FUTURE`.
- `BoardViewModelTest` : la phrase élidée dans l'état, effacer retire le dernier mot touché.
- `MigrationTest` (instrumenté, `MigrationTestHelper(instrumentation, PapotonsDatabase::class.java)`) : v1 avec une grille, un mot et un dossier ; `runMigrationsAndValidate(2)` ; relecture par le DAO : lignes intactes, nouvelles colonnes nulles.
- `BoardScreenLayoutTest` : dans des cases larges et basses à police 200 %, le picto garde au moins 40 % de la case et le libellé reste affiché ; une case ne bouge pas quand son picto arrive ; un libellé en texte seul est centré.
- `CreditStringsTest`, `ThemeColorsTest`, `PictogramImagesTest` : crédit complet dans chaque langue, contrastes du libellé et du contour sur chaque fond, chaque picto du vocabulaire décodé depuis l'APK.
- Sur appareil : MVP-2 installé par-dessus le MVP-1 sans effacer les données (la démo doit rester intacte : vraie migration 1 → 2), puis installation propre pour voir le vocabulaire ; émulateur et tablette de Franck (Galaxy Tab A9+), toujours avec `ANDROID_SERIAL`.
