# MVP-1 : grille, dossiers, barre de message, synthèse vocale

Source : `docs/TODO.md` § Feuille de route, MVP-1. Décisions prises avec Franck le 2026-11-06 : D-3 tranché pour Room (OBF seulement à l'import et à l'export, MVP-4) ; cases en texte seul au MVP-1, les pictos arrivent avec le vocabulaire de base (MVP-2).

Le contrat de la voix fait foi dans la KDoc de `speech/RecoveringSpeaker.kt` ; le choix d'une voix hors ligne dans `speech/VoiceSelection.kt`. Les reports sont dans `docs/TODO.md`.

## 1. Contrat et fichiers touchés

### Comportement

Au lancement, après le carton du studio, l'écran d'accueil provisoire est remplacé par la grille de communication. De haut en bas : une barre unique (hauteur fixe) qui porte la navigation, la phrase en cours, l'alerte de voix et « À propos », puis la grille (tout le reste).

- **Grille** : `rowCount` × `columnCount` emplacements de taille égale, sans défilement (tout est visible : condition de la planification motrice). Un emplacement sans case reste vide. Chaque case est placée à SA position (ligne, colonne) stockée, jamais à son rang dans une liste. La grille est forcée en `LayoutDirection.Ltr` (une locale RTL ne renverse pas les colonnes) et la rotation ne transpose jamais (ligne, colonne).
- **Rien ne fait bouger la grille** (invariant 3) : la barre au-dessus a une hauteur fixe quel que soit son contenu ; l'alerte de voix vit dans cette barre (réduite à son icône sur un écran étroit), jamais dans un bandeau inséré.
- **Case mot** : un appui prononce le mot tout de suite (en coupant ce qui se disait) et l'ajoute à la fin de la barre de message.
- **Case dossier** : un appui ouvre la grille liée, sans parler ni rien ajouter. Fond distinct et icône de dossier ; TalkBack l'annonce comme dossier (ressource traduite).
- **Libellé** : texte à taille adaptative (`BasicText` avec `TextAutoSize`, plafonnée par l'échelle de police) sur deux lignes au plus, pour tenir dans une case fixe même à 200 % ; rôle bouton ; contraste au moins 4,5:1 pour les deux couleurs de case.
- **Navigation** : le nom de la grille n'est pas écrit (la hauteur revient aux cases), TalkBack l'annonce comme titre de volet ; « Retour » (grille précédente) et « Accueil » (grille d'accueil, pile vidée) visibles seulement hors de l'accueil. Le retour arrière d'Android fait comme « Retour » hors de l'accueil, et n'est pas intercepté à l'accueil.
- **Barre de message** : les mots ajoutés, dans l'ordre, défilant horizontalement jusqu'au dernier ; une indication quand elle est vide. Un appui sur la barre ou sur « Dire la phrase » prononce toute la phrase (rien si elle est vide). « Effacer le dernier mot » et « Tout effacer ». Le message survit à la rotation (ViewModel), pas à la mort du processus (reporté).
- **Synthèse vocale** : moteur par défaut de l'appareil (`TextToSpeech`), sortie sur le flux média, langue tirée d'une ressource (`fr-FR` dans `values/`, `en-US` dans `values-en/`). Les mots touchés se prononcent à la suite, sans se couper ; « Parler » coupe ce qui se dit pour lire la phrase. Un mot touché avant la fin de l'initialisation est prononcé dès qu'elle aboutit.
- **La voix se rétablit** : statut `Initializing` / `Ready` / `LanguageMissing` / `Unavailable`.
  - Pas de réponse d'`onInit` sous `INIT_TIMEOUT_MS` : `Unavailable`.
  - `speak` refusé (moteur mis à jour ou tué) : le moteur est recréé une fois (`Initializing`) et le mot rejoué ; un second refus donne `Unavailable`.
  - Erreur asynchrone sur le dernier énoncé (`onError`) : `Unavailable`, sans rejeu.
  - Un mot touché en `Unavailable`, un `ON_START` ou « Réessayer » relancent le moteur.
  - Une voix qui exige le réseau ou dont les données manquent est remplacée par une voix locale de la langue, sinon `LanguageMissing` (invariant 2).
  - `LanguageMissing` et `Unavailable` affichent dans la barre du haut une alerte (icône + texte propre à chaque panne) qui ouvre une modale « Réessayer / Réglages de la voix ».
- **Mise à jour Play** : `isComposingMessage` de `MainActivity` est branché : la boîte « mise à jour prête » ne s'ouvre pas tant que la barre de message n'est pas vide.
- **Premier lancement** : des grilles de démonstration (`DemoBoards` : un accueil avec quelques mots, un emplacement libre et deux dossiers) sont insérées une fois, en une transaction, dans la langue de l'appareil. Rien n'est observé avant la fin de cette insertion ; une base qui a perdu son accueil ouvre sa plus petite grille (ni plantage en boucle ni chargement sans fin).

### Données (Room 2.8.5, base `papotons.db`, version 1)

- `boards` : `id` (clé, `autoGenerate = true` ; la démo pose des ids explicites 1, 2, 3), `name`, `row_count`, `column_count`.
- `cells` : `id` (auto), `board_id` (clé étrangère vers `boards`, CASCADE), `grid_row`, `grid_column` (pas `row`/`column`, mots-clés SQLite), `label`, `linked_board_id` (nullable, clé étrangère vers `boards`, RESTRICT : supprimer une grille visée par un dossier sera une action explicite au MVP-3, jamais une case qui change de nature en silence ; non nul = dossier). Index UNIQUE sur (`board_id`, `grid_row`, `grid_column`), index sur `linked_board_id`.
- Insertion en `OnConflictStrategy.ABORT` uniquement : `REPLACE` ferait DELETE + INSERT et la CASCADE effacerait les cases de la grille (noté dans `BoardDao`).
- Grille d'accueil : id fixe `HOME_BOARD_ID = 1`.
- Schéma exporté et versionné (`app/schemas/com.wobblebot.papotons.data.PapotonsDatabase/1.json`, plugin Gradle Room) pour les migrations automatiques. Jamais de `fallbackToDestructiveMigration`.
- `BoardDao` est une classe abstraite : `seedIfEmpty(boards, cells)` est une méthode `@Transaction` qui compte puis insère (Room sérialise les transactions d'écriture : pas de `Mutex`). L'`@Insert` multi-entités prend `boards` avant `cells` (ordre d'insertion = ordre des paramètres, les clés étrangères sont vérifiées immédiatement).

### Fichiers

Nouveaux :
- `data/board/BoardEntity.kt`, `CellEntity.kt`, `BoardWithCells.kt`, `BoardDao.kt`
- `data/PapotonsDatabase.kt`, `data/DatabaseModule.kt` (Hilt, `@Singleton`)
- `data/board/BoardRepository.kt` (`@Singleton`) : `ensureHomeBoard()`, `observeBoard(id): Flow<Board?>`
- `data/board/DemoBoards.kt` : construit la démo à partir d'une fonction `(Int) -> String`, sans `Context`
- `domain/Board.kt` : `Board`, `Cell`, et `layoutGrid(rowCount, columnCount, cells)` (pur)
- `speech/Speaker.kt` (interface vue du ViewModel), `speech/RecoveringSpeaker.kt` (machine à états), `speech/SpeechEngine.kt` (coutures de test : moteur, fabrique, planificateur), `speech/AndroidSpeechEngine.kt` (`TextToSpeech` sur `@ApplicationContext`), `speech/HandlerScheduler.kt`, `speech/SpeechStatus.kt`, `speech/VoiceSelection.kt` (choix de la voix hors ligne, pur), `speech/SpeechModule.kt` (binding dans `ViewModelComponent`, non scopé : une instance par ViewModel, éteinte par `onCleared`)
- `ui/board/BoardViewModel.kt`, `BoardUiState.kt`, `BoardScreen.kt`, `CommunicationGrid.kt`, `MessageBar.kt`, `BoardTopBar.kt`, `BoardCardStyle.kt`, `SpeechUnavailableDialog.kt`
- `res/drawable/ic_*.xml` : dossier, accueil, retour, effacer, tout effacer, parler, alerte (tracés Material Icons, Apache 2.0)
- `res/values/strings_board.xml` + `values-en/strings_board.xml`
- `app/schemas/.../1.json` (généré, à ajouter au commit)
- Tests JVM : `domain/GridLayoutTest.kt`, `data/board/DemoBoardsTest.kt`, `data/board/BoardRepositoryTest.kt`, `speech/RecoveringSpeakerTest.kt`, `speech/VoiceSelectionTest.kt`, `ui/board/BoardViewModelTest.kt`, `FakeBoardDao.kt`, `FakeSpeaker.kt`
- Tests instrumentés : `androidTest/.../data/board/BoardDaoTest.kt`, `androidTest/.../ui/board/BoardScreenLayoutTest.kt` (émulateur `Medium_Phone_API_37.0`, `ANDROID_SERIAL=emulator-5554`)

Modifiés :
- `gradle/libs.versions.toml`, `build.gradle.kts` (plugin Room `apply false`), `app/build.gradle.kts`
- `AndroidManifest.xml` : `<queries>` sur `android.intent.action.TTS_SERVICE` (sinon Android 11+ cache le moteur et l'appli reste muette)
- `MainActivity.kt` : `BoardRoute` à la place de `HomeScreen`, `isComposingMessage` branché
- `ui/theme/Theme.kt` : couleurs de case (mot, dossier) et d'alerte en tokens de rôle
- `CLAUDE.md` (Stack, Arborescence) et `.claude/socle.md` (Projet) : Room, `data/`, `domain/`, `speech/`
- `docs/TODO.md` : MVP-1 supprimé ; D-3 en décision actée ; MVP-2 reprend « image de case (picto) » ; MVP-5 reprend la ligne de version et la mention Apache 2.0 des icônes ; nouvelle dette (voir écarté)

Supprimés : `ui/home/HomeScreen.kt`, chaîne `home_tagline` (fr et en).

### Invariants

1. **Zéro collecte** : Room est local, aucune permission ni SDK ajouté ; `<queries>` ne donne que la visibilité du service TTS.
2. **Hors ligne** : base locale, moteur de l'appareil.
3. **Une case ne bouge pas** : position stockée, index unique, placement par position (`layoutGrid`), grille en LTR, barres de hauteur fixe, aucun bandeau inséré.
4. **Pas de perte** : schéma exporté dès la v1, ABORT seulement, RESTRICT sur les dossiers, aucune migration destructive, sauvegarde Android complète.
5. **Crédit ARASAAC** : pas de picto ARASAAC au MVP-1.

## 2. Envisagé et écarté

- **Room 3 (`androidx.room3`)** : écarté pour l'instant ; 2.8.5 est la branche stable la plus éprouvée, le passage ne touche pas aux données.
- **Image de case, texte prononcé distinct du libellé, couleur Fitzgerald, cases masquées** : reportés (MVP-2, MVP-3, IDEE-2), ajoutés alors par migration automatique non destructive.
- **Marqueur « installation déjà amorcée » pour distinguer premier lancement et base perdue** (corruption : le callback par défaut de SQLite supprime le fichier) : reporté. Au MVP-1 la base ne contient que la démo, rien de l'utilisateur ne peut s'y perdre. Dette ouverte pour le MVP-3 : marqueur + copie de la base dans `onCorruption`.
- **Alerte volume média à zéro** : reportée (TODO-4).
- **Rotation en `LanguageMissing`** : `ON_START` relance le moteur à chaque rotation, l'alerte disparaît le temps de l'init. La modale n'est fermée que par le retour de la voix (`Ready`), donc elle réapparaît si la panne persiste.
- **Anti-rebond des appuis répétés, retour automatique à l'accueil après un mot de dossier, choix du moteur et de la voix, délai d'inactivité avant la boîte de mise à jour** : reportés, ce sont des réglages.
- **Langue de la voix stockée avec la grille** : reporté ; elle suit la langue des ressources, qui est aussi celle de la démo au premier lancement.
- **Pile de navigation et message dans `SavedStateHandle`** : reporté.
- **Grille d'accueil désignée par un réglage** : écarté tant qu'il n'y a ni import ni création de grille (id fixe 1).
- **`RoomDatabase.Callback.onCreate`, `Mutex`** : écartés ; `seedIfEmpty` en `@Transaction` dans le DAO.
- **Robolectric pour jouer le test DAO sur la JVM** : écarté, l'émulateur du poste le joue (`connectedAndroidTest`).
- **Interface de dépôt** : non (classe concrète ; la couture de test est le DAO). `Speaker` est une interface parce que `TextToSpeech` est un mécanisme externe que les tests doivent simuler.
- **Icônes `material-icons`** : écarté (dépendance lourde pour sept icônes) ; vecteurs XML.
- **TODO-1 (invariants écrits et gardés par des tests)** : non traité ici ; les tests de ce lot gardent déjà l'invariant 3 au niveau du placement et de l'index.
- **Analogues imités** : `InAppUpdateManager` (ressource système emballée, état en `StateFlow`), `UpdateCheckPolicy` (logique pure à part, testée en JVM).


## 3. Observabilité

| Étape | Échec possible | Signal |
|---|---|---|
| Ouverture de la base, insertion de la démo | exception SQLite, contrainte violée, base illisible | exception non attrapée : plantage remonté dans Android vitals avec la trace (on ne montre pas une grille vide en silence) |
| `observeBoard` d'un dossier dont la grille manque | impossible avec RESTRICT, mais lu défensivement | `Log.e` avec l'id + retour à la grille précédente |
| accueil absent d'une base non vide | donnée corrompue, import futur | `Log.e` ; la plus petite grille s'ouvre à la place |
| `layoutGrid` : case hors des bornes | donnée corrompue ou import futur | `Log.w` avec id et position ; case non affichée, conservée en base |
| Init TTS | aucun moteur, erreur, pas de réponse sous 5 s | `Unavailable` → bouton d'alerte visible + `Log.e` avec le statut |
| `setLanguage` | voix de la langue absente | `LanguageMissing` → bouton d'alerte + `Log.w` avec le code |
| `speak` | `ERROR` | `Log.e`, recréation et rejeu ; second refus : `Unavailable` |
| `configure` | exception d'un moteur tiers | `Log.e`, `Unavailable` (pas de plantage) |
| `onError` d'énoncé | voix réseau, sortie audio | `Log.e`, `Unavailable` |
| Ouverture des réglages TTS | aucune activité ne répond | `Log.w`, rien d'autre (le bouton reste) |

## 4. Retries et limites

Aucun réseau. TTS : une recréation sur refus de `speak` (un refus au rejeu laisse `Unavailable`, pas de boucle), puis une à chaque relance (`ON_START`, « Réessayer », mot touché en `Unavailable`), bornées (au plus deux moteurs par geste de l'utilisateur). Insertion de la démo idempotente et atomique (transaction « compter puis insérer »).

## 5. Plan de test

JVM (`testDebugUnitTest`) :
- `GridLayoutTest` : grille 2 × 3, cases dans le désordre → chacune à sa (ligne, colonne) (attrape une inversion ligne/colonne et un rangement par index) ; emplacements vides à `null` ; case hors bornes ignorée.
- `DemoBoardsTest` : cases dans les bornes, aucune paire au même emplacement, chaque lien vise une grille existante, la grille d'accueil existe.
- `BoardRepositoryTest` (DAO factice héritant de la classe abstraite, donc de la vraie `seedIfEmpty`) : base vide → démo insérée une fois ; second appel → rien ; base déjà peuplée → rien.
- `VoiceSelectionTest` : codes de `setLanguage` (une voix fr-FR répond `LANG_COUNTRY_AVAILABLE`) ; choix d'une voix locale de la langue, pays exact d'abord, jamais une autre langue.
- `RecoveringSpeakerTest` : génération, délai, rejeu borné, budget, mots jamais dits en retard, `shutdown` terminal.
- `BoardViewModelTest` (DAO factice vide au départ, `FakeSpeaker`) : état `Loading` puis accueil ; mot → prononcé et ajouté en fin ; dossier → grille liée, rien de prononcé ni d'ajouté ; pile de 3 : « Retour » revient d'un cran, « Accueil » à la racine ; à l'accueil, pas de retour possible ; « Dire la phrase » prononce deux mots différents dans l'ordre ; message vide → `speak` jamais appelé ; « Effacer le dernier » retire seulement le dernier ; « Tout effacer » vide ; statut TTS indisponible reflété ; retour au premier plan → `recoverIfNeeded` appelé.

Instrumenté (`connectedAndroidTest` sur l'émulateur) — `BoardDaoTest`, base en mémoire :
- deux cases au même emplacement → `SQLiteConstraintException` (index unique, invariant 3) ;
- réinsérer une grille existante échoue et ses cases restent (ABORT, pas REPLACE) ;
- supprimer une grille visée par un dossier échoue (RESTRICT) ;
- `seedIfEmpty` puis `observeBoard` rend la grille et ses cases.

Non couvert automatiquement : `AndroidSpeechEngine` face au moteur réel. Vérification manuelle sur l'émulateur (capture de la grille, d'un dossier, de la barre de message).
