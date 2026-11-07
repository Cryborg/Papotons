# MVP-11 : écrans personnalisés

Demande de Franck (2026-11-07) : pour chaque utilisateur, des écrans personnalisés (« Maison », « École »…), chacun avec ses propres cases, que les accompagnants ajoutent, suppriment, modifient et réorganisent.

Choix tranchés par Franck le 2026-11-07 :
- **un seul mécanisme** : techniquement il n'y a que des écrans ; l'accueil d'aujourd'hui devient l'écran créé par défaut ;
- **onglets pour l'enfant** : il passe seul d'un écran à l'autre ;
- **un nouvel écran reçoit une copie du vocabulaire de base** (mots de base aux mêmes places, dossiers compris), que l'adulte complète ou remplace ;
- **la taille de cases se choisit par écran**, à sa création ; la page « Taille des cases » disparaît ;
- **au lancement (et au changement d'utilisateur), le premier onglet s'ouvre** ; l'adulte ordonne les écrans ;
- **déplacer une case par glisser-déposer**, en édition, **partout** (écrans et dossiers).

L'appli n'est pas publiée (`versionCode = 1`) : comme pour PROFILS, la migration et la lecture des anciens formats restent (invariant 4), sans compatibilité au-delà.

## Contrat et fichiers touchés

Deux commits. A se livre seul ; B ne dépend pas de A (mêmes fichiers, donc l'un après l'autre).

### A. Les écrans

**Modèle.** Un écran est une grille de premier niveau d'un utilisateur : `boards.screen_position` non nul (entier, ordre des onglets). Une grille de dossier a `screen_position = NULL`. L'appli ne crée jamais de dossier qui vise un écran ; la sauvegarde ne le refuse pas (une base migrée depuis des données abîmées pourrait en avoir un : l'export doit rester possible), et `deleteScreen` refuse un écran encore visé. Les écrans d'un utilisateur : `WHERE profile_id = ? AND screen_position IS NOT NULL ORDER BY screen_position`. Index unique `(profile_id, screen_position)` (SQLite tient les `NULL` pour distincts : les dossiers ne gênent pas).

**Comportement.**
- **Enfant** : au-dessus de la grille, sous la barre du haut, une rangée d'onglets (nom de chaque écran) dès que l'utilisateur ouvert a **au moins deux écrans** ; avec un seul, rien ne change à l'écran. Toucher un onglet ouvre cet écran (pile = cet écran, page des formes fermée, phrase gardée). L'onglet de l'écran ouvert est marqué (couleur et état « sélectionné » pour TalkBack), même dans un de ses dossiers. « Accueil » ramène au bas de la pile, donc à l'écran d'où l'on est parti (inchangé). Rangée défilante si les onglets ne tiennent pas ; hauteur minimale fixe, nom sur une ligne avec points de suspension (grande police) ; les onglets arrivent dans la même émission que la grille (portés par `DisplayedBoard`), jamais une image sans onglets puis avec ; elle apparaît ou disparaît seulement quand l'adulte crée ou supprime le deuxième écran (la grille rétrécit alors d'un coup, aucune case ne change de ligne ni de colonne).
- **Lancement, changement d'utilisateur, restauration** : le premier écran (plus petit `screen_position`). Une pile sauvée (mort du processus) est gardée si son bas est un écran de l'utilisateur ouvert, quel qu'il soit (l'enfant était sur « Maison », il y revient).
- **Réglages** : la ligne « Taille des cases » devient « Écrans » (résumé : les noms, séparés comme les prénoms). Page « Écrans (Léo) » : la liste dans l'ordre, chaque ligne avec son nom, sa taille, « Monter », « Descendre » (absents aux bouts) et « Modifier » ; en bas « Ajouter un écran ».
  - Ajouter : modale « Nouvel écran » : nom (obligatoire, une ligne, 40 caractères au plus comme un prénom, espaces superflus retirés), taille (les trois cartes radio de l'ancienne page, présélection : la taille de l'écran affiché, sinon standard), « Créer ». L'écran arrive en dernier, il ne s'ouvre pas.
  - Modifier : modale (nom, Enregistrer, Supprimer, Annuler). Supprimer demande confirmation (« L'écran Maison et ses dossiers seront supprimés pour de bon, photos comprises. ») ; le dernier écran ne se supprime pas (bouton absent). Supprimer l'écran affiché ouvre le premier écran restant.
  - Monter / Descendre : échange avec le voisin, tout de suite ; boutons désactivés pendant l'écriture (un double appui ne défait pas le premier).
  - La modale de création défile (nom, puis trois cartes, clavier ouvert sur un téléphone couché) ; la taille présélectionnée vient de `BoardUiState.boardSize`, passée par `SettingsDialog`.
  - Échecs : message sous la liste (« L'écran n'a pas pu être créé. Réessayez. », etc.), comme les utilisateurs.
- **Taille affichée** : inchangée dans son principe (`BoardSize.withHome(rootCoreBoardId)`) : l'écran garde le `core_board_id` de l'accueil de sa taille, ses dossiers aussi, donc un dossier créé dans un écran en grandes cases est en 2 × 3. Un écran restauré d'avant (sans taille reconnue) donne des dossiers standard, comme aujourd'hui.

**Données (Room, version 5, migration manuelle 4 → 5).**
- `BoardEntity.screenPosition: Int? = null` (`screen_position`, en fin de constructeur, valeur par défaut pour `CoreVocabulary.kt` généré, comme `profileId`).
- `Migration4To5` (`data/board/Migration4To5.kt`) : `ALTER TABLE boards ADD COLUMN screen_position INTEGER` ; puis, pour chaque profil, son premier écran choisi par `firstScreenOf` (ci-dessous) reçoit la position 0 ; `CREATE UNIQUE INDEX index_boards_profile_id_screen_position`. Ajout de colonne seulement, aucune ligne supprimée, aucune table recréée (même raison que 3 → 4).
- `firstScreenOf(profile, boardsOfProfile, linkedBoardIds): Long?` (fonction pure, `data/board/Screens.kt`), partagée par la migration et la lecture des sauvegardes d'avant les écrans : `start_board_id` s'il est une grille du profil qu'aucune case ne vise ; sinon sa copie de l'accueil standard (`core_board_id = 1`) qu'aucune case ne vise ; sinon sa plus petite grille qu'aucune case ne vise ; sinon sa plus petite grille. Les accueils des autres tailles d'une base existante restent en base, sans position (invisibles, jamais supprimés) : seuls les appareils de développement en ont.
- `profiles.start_board_id` n'est plus lu ni écrit (toujours `null` à la création) ; la colonne reste (la retirer recréerait `profiles`, visée en RESTRICT). KDoc mis à jour.
- **Graine et nouvel utilisateur** : seulement le sous-arbre de l'accueil standard (`VocabularySeed.subtree(HOME_BOARD_ID)` : les grilles atteignables depuis la racine par les dossiers, et leurs cases), racine à la position 0. Le premier utilisateur garde les ids fixes (1 à 6) ; un nouvel utilisateur, ids renumérotés comme aujourd'hui (`forProfile`). `CoreVocabulary.kt` et l'instantané ne changent pas.
- **Nouvel écran** (`BoardDao.insertScreen(profileId, name, seed)`, une transaction) : refusé au-delà de `MAX_SCREENS` (20) écrans pour ce profil ou de `MAX_BOARDS` grilles en tout (`TooManyScreensException`) ; sinon `subtree(size.homeBoardId).forProfile(profileId, max(plus grand id + 1, 1000))`, racine renommée, position = max + 1. Rend l'id.
- **Supprimer un écran** (`deleteScreen(profileId, boardId)`, une transaction) : refusé si la grille n'est pas (ou plus) un écran de ce profil (`ScreenNotFoundException` : un id périmé après une restauration ne supprime jamais un dossier ni une grille cachée), si c'est le dernier écran de son profil (`LastScreenException`), ou si une case la vise encore (`ScreenStillLinkedException`, rien de supprimé) ; sinon son sous-arbre, sauf les grilles encore visées ailleurs ou qui sont des écrans, par la même boucle que `deleteCellAndOrphanBoards` (extraite en `deleteOrphanSubtree`). Rend les photos, effacées ensuite comme après `deleteCell`.
- **Renommer** (`renameScreen(profileId, boardId, name)`) : nom validé (`isValidScreenName`), seulement un écran de ce profil (`UPDATE … WHERE id = ? AND profile_id = ? AND screen_position IS NOT NULL`, 0 ligne → `ScreenNotFoundException`).
- **Ordonner** (`moveScreen(profileId, boardId, offset = ±1)`, une transaction, même vérification) : échange les positions avec le voisin dans l'ordre (pas forcément ±1 : une suppression laisse des trous) ; position temporaire `-1` pour l'index unique. Sans voisin : rien.
- **Grilles protégées à la suppression d'une case** : tous les écrans (de tous les utilisateurs), lus **dans** la transaction de `deleteOrphanSubtree` (plus avant `write {}`), plus la grille de la case et la racine ouverte.
- **Code mort retiré** : `BoardDao.setStartBoard`, `isStartBoardOf`, `startBoardCopyOf`, `copiesOf` ; `BoardRepository.setStartBoard` ; `SettingFailure`, `sizeChoice`, `onBoardSizeChosen` ; chaînes `settings_board_size_*` devenues inutiles.
- **Écran de départ** (`openableStartBoard`) : le premier écran du profil, sinon sa plus petite grille (`Log.e`).
- `BoardRepository` : `createScreen(name, size)`, `renameScreen`, `deleteScreen`, `moveScreen`, chacun dans `write {}` sur l'utilisateur ouvert ; `ensureHomeBoard` rend le premier écran ; `isScreenOf(boardId, profileId)` pour la pile sauvée ; `observeScreensBesides(boardId): Flow<List<Screen>>` (`domain/Screen.kt` : `id`, `name`, `size: BoardSize?`) : les écrans du profil **qui possède** la grille `boardId`, une seule requête (`profile_id = (SELECT profile_id FROM boards WHERE id = :boardId)`).
- `renameBoard` rend le nombre de lignes. KDoc de `CellChangedException` élargie (« la case a changé depuis sa lecture »).
- **Faux DAO des tests** (`FakeBoardDao`) : la commodité qui range une grille sans utilisateur chez l'utilisateur 1 pose aussi `screenPosition = 0` sur `firstScreenOf` de chaque profil (comme la migration) ; il imite l'index unique des positions d'écran.

**ViewModel.**
- `BoardViewModel` : `displayedBoard` combine dans le même `flatMapLatest` la grille du haut, le `core_board_id` du bas et `observeScreensBesides(stack.first())` ; `DisplayedBoard` gagne `rootId` (bas de la pile, onglet marqué) et `screens`. Grille et onglets arrivent ensemble, et la liste suit l'utilisateur de la pile, pas `openProfileId` (aucune course au changement d'utilisateur). `onScreenChosen(boardId)` : page des formes fermée, pile `[boardId]` si ce n'est pas déjà l'écran affiché seul. **Écran affiché supprimé** : un seul mécanisme, le repli existant de `display()` (grille introuvable : dépile, puis au bas de pile `reopenHome()`, qui rend le premier écran) ; son `Log.e` passe en `Log.w` (une grille supprimée dans les Réglages est un cas normal). `reopenHome` garde une pile sauvée dont le bas est un écran de l'utilisateur. Échec d'un déplacement de case : dans l'état (`moveFailure`, affiché dans le bandeau d'édition, effacé au déplacement suivant), pas de toast.
- `ScreensViewModel` (Hilt, calqué sur `ProfilesViewModel`) : écrans de l'utilisateur ouvert, travail en cours, échec ; créer, renommer, supprimer (photos effacées par `PhotoStore`), monter, descendre.

**UI.** `ui/board/ScreenTabs.kt` (rangée d'onglets de l'enfant) ; `ui/settings/ScreensPage.kt` et `ui/settings/ScreenDialog.kt` (création et modification) ; `BoardSizePage.kt` devient le choix de taille réutilisé par `ScreenDialog` (cartes radio gardées, page retirée). Modales : `DialogProperties(dismissOnClickOutside = false)`. Aucun tiret cadratin.

**Sauvegarde `.obz` (format 3).** Chaque grille : `ext_papotons_screen_position`. `BackupManifest` porte la version lue. `validate` refuse en plus : profil sans écran, deux écrans à la même position dans un profil, position négative (rien sur un dossier qui viserait un écran, voir Modèle). Lecture des formats 1 et 2 : positions posées par `firstScreenOf` (au format 2 avec le `start_board_id` lu), puis `start_board_id` remis à `null` avant l'insertion ; au format 3, une position absente vaut dossier. Racine OBF : premier écran du premier profil. `start_board_id` n'est plus écrit.

**Fichiers.** `data/{PapotonsDatabase}.kt`, nouveaux `data/board/{Migration4To5,Screens}.kt`, `data/board/{BoardEntity,BoardDao,BoardRepository,ProfileVocabulary,VocabularySeed}.kt`, `data/profile/ProfileEntity.kt` (KDoc), `data/backup/BackupArchive.kt`, nouveau `domain/Screen.kt`, `domain/BoardSize.kt` (KDoc), `ui/board/{BoardViewModel,BoardUiState,BoardScreen}.kt`, nouveau `ui/board/ScreenTabs.kt`, `ui/settings/{SettingsDialog,SettingsPage,BoardSizePage}.kt`, nouveaux `ui/settings/{ScreensPage,ScreenDialog,ScreensViewModel}.kt`, `res/values/{strings_screens,strings_settings}.xml`, `app/schemas/…/5.json`, `docs/TODO.md` (MVP-11 supprimée, décision actée ajoutée, décision « une taille = un jeu de grilles » révisée), tests.

### B. Déplacer une case (glisser-déposer)

**Comportement.** En édition seulement, dans toute grille (écran ou dossier) : appui long sur une case, elle se soulève (retour haptique par `LocalHapticFeedback`, aucune permission) et suit le doigt ; un appui long relâché sans bouger n'ouvre pas l'éditeur ; un appui long sur un « + » ne fait rien de plus qu'un appui ; l'emplacement survolé est entouré ; lâchée sur un emplacement libre (« + »), elle y va ; sur une case, les deux s'échangent ; lâchée hors de la grille ou sur sa propre place, rien ne change. Un simple appui ouvre toujours l'éditeur. Hors édition, l'appui long garde son rôle (page des formes). Pour TalkBack et Switch Access, chaque case en édition a quatre actions (« Déplacer vers la gauche », « … la droite », « … le haut », « … le bas »), présentes seulement quand l'emplacement voisin existe : même échange, case par case.

**Données.** `BoardDao.moveCell(cellId, fromRow, fromColumn, toRow, toColumn)` (une transaction) : case disparue → `CellNotFoundException` ; plus à `(fromRow, fromColumn)` → `CellChangedException` ; cible hors de la grille → refus ; si une case occupe la cible, elle prend `(fromRow, fromColumn)`, la case déplacée passant par `(-1, -1)` le temps de l'échange (index unique ; une case restaurée à cette place, absurde, fait échouer la transaction sans rien écrire). `BoardRepository.moveCell(boardId, cell, toRow, toColumn)` dans `write {}`.

**ViewModel.** `onCellMoved(cell, toRow, toColumn)` : en édition seulement, sur la grille affichée ; un déplacement à la fois (un second, parti d'une case périmée, est ignoré tant que le premier écrit) ; échec : `Log.e` et `moveFailure` dans l'état (texte dans le bandeau d'édition), rien d'écrit à moitié.

**UI.** `CommunicationGrid` : en édition, chaque case a, en plus de son `clickable`, un `pointerInput` intérieur (`detectDragGesturesAfterLongPress`, qui consomme le relâcher : le clic n'a pas lieu) ; les positions remontent en coordonnées de la grille. L'emplacement visé se calcule d'après la position du doigt, la taille de la grille et l'espacement (fonction pure `slotAt`, testée). La case portée est dessinée dans une couche au-dessus de toute la grille (un `Box` englobant, décalage lu dans `graphicsLayer`) ; sa place d'origine reste en pointillés. Focus TalkBack après un déplacement par action : il reste sur l'emplacement (qui annonce la case échangée) ; suivre la case est reporté.

**Fichiers.** `data/board/{BoardDao,BoardRepository}.kt`, `ui/board/{CommunicationGrid,BoardViewModel,BoardScreen}.kt`, `res/values/strings_edit.xml`, tests.

### Invariants

1. Rien de réseau, rien de collecté.
2. Parler ne dépend que des grilles locales ; un écran se copie depuis le vocabulaire embarqué.
3. Aucune case ne bouge toute seule : un écran reçoit le vocabulaire aux positions de l'instantané ; la migration ne touche à aucune position ; seul le geste explicite de l'adulte, en édition, déplace une case (et celle qu'elle remplace) ; les onglets n'apparaissent que sur un geste de l'adulte (création du deuxième écran), sans changer ligne ni colonne d'aucune case.
4. Migration par ajout de colonne (vérifiée par `MigrationTest`) ; suppression d'un écran seulement sur demande confirmée, jamais le dernier ; les accueils cachés des autres tailles restent en base ; l'export couvre tous les écrans ; les formats 1 et 2 se restaurent toujours.
5. Inchangé.

## Envisagé et écarté

- Écrans distincts des grilles (table `screens`) : une grille de premier niveau suffit, la pile, l'éditeur et la sauvegarde la connaissent déjà.
- Garder les accueils des trois tailles et un réglage de taille par utilisateur : écarté par Franck (taille par écran) ; il faudrait trois versions cachées de chaque écran.
- Rendre écrans les accueils cachés des autres tailles à la migration : chaque utilisateur existant aurait trois onglets « Accueil ». Ils restent en base, sans position.
- Écran vide, ou copie d'un écran existant : écarté par Franck (copie du vocabulaire). Dupliquer un écran : reporté.
- Dossiers partagés entre écrans (copie de la racine seule) : modifier « Manger » dans « Maison » le changerait dans « École », et la suppression d'un écran devrait raisonner sur les partages ; copie profonde, chaque écran a ses propres cases.
- Choix de l'écran de départ par l'adulte, ou dernier onglet rouvert : écartés par Franck (premier onglet).
- Glisser-déposer pour ordonner les écrans dans les Réglages : « Monter » et « Descendre » suffisent pour une liste courte et passent avec TalkBack.
- Déplacer une case vers une autre grille (dossier, autre écran) : reporté.
- Supprimer `profiles.start_board_id` : recréation de table, pour une colonne inerte.
- Observateur dédié qui ramène au premier écran quand l'écran affiché disparaît : doublon du repli de `display()`, et faux déclenchements pendant un changement d'utilisateur ou une restauration.
- Refuser à la sauvegarde un dossier qui vise un écran : une base migrée depuis des données abîmées ne pourrait plus s'exporter.
- « Annuler » après un déplacement de case : l'adulte, en édition, remet la case en la glissant de nouveau ; reporté si le test fermé montre des déplacements par erreur.
- Toast d'échec de déplacement : le ViewModel n'a pas de canal d'événements ; l'échec vit dans l'état, comme ceux de l'éditeur.
- Onglets toujours visibles, même avec un seul écran : une rangée qui ne sert à rien prend la place des cases.

## Observabilité

- Migration 4 → 5 qui échoue : Room lève à l'ouverture (Android vitals), la transaction laisse la base d'avant. Profil sans grille pendant la migration : `Log.w` avec l'id, aucun écran posé ; au lancement, `ensureHomeBoard` retombe sur la plus petite grille (`Log.e`, déjà là).
- Création, suppression, déplacement d'écran : `Log.i` avec l'id et le nombre de grilles (jamais le nom). Échec : `Log.e` avec l'exception et un message sous la liste des écrans.
- Utilisateur sans écran (base abîmée) : `ensureHomeBoard` journalise et ouvre son accueil ou sa plus petite grille (`Log.e`), on parle quand même.
- Pile sauvée abandonnée : `Log.w` avec la pile (ids seulement), comme aujourd'hui.
- Déplacement de case refusé : `Log.e` avec l'id de la grille et l'exception, toast à l'adulte.
- Sauvegarde d'un ancien format : `Log.i` avec le format. Refus de `validate` : raison au journal.

## Retries et limites

Aucun réseau, aucun nouvel essai automatique : un échec d'écriture se réessaie à la main.

## Plan de test

JVM :
- `ScreensTest` (`firstScreenOf`) : `start_board_id` valide gagne ; qui vise un dossier → accueil standard ; d'un autre profil → accueil ; sans accueil → plus petite grille non visée ; données asymétriques (accueil d'id plus grand que d'autres grilles).
- `ProfileVocabularyTest` / `CoreVocabularyTest` : `subtree` de chaque taille rend exactement ses grilles (standard 1 à 6, moyenne 7 à 12, grandes 13 et 14), cases et liens fermés, positions intactes.
- `BoardScreensTest` (`FakeBoardDao` avec écrans et index unique) : création en grandes cases (ids ≥ 1000, `core_board_id` 13 et 14, position après la dernière) ; nouvel utilisateur = un écran ; suppression d'un écran emporte ses dossiers mais pas ceux d'un autre écran ; dernier écran refusé ; monter puis descendre rend l'ordre ; monter après une suppression (trou) échange avec le vrai voisin ; supprimer une case-dossier ne supprime jamais un écran ; `ensureHomeBoard` rend le premier écran par position, pas le plus petit id.
- `BackupArchiveTest` : aller-retour avec deux profils et trois écrans (positions comprises) ; format 2 lu avec `start_board_id` → position 0 au bon écran ; refus : profil sans écran, deux écrans à la même position, dossier qui vise un écran.
- `BoardViewModelTest` : onglet choisi → pile de cet écran, phrase gardée ; écran affiché supprimé → premier écran ; pile sauvée sur le deuxième écran → gardée ; sur une grille qui n'est pas un écran → abandonnée ; changement d'utilisateur → son premier écran ; dossier créé dans un écran en grandes cases → 2 × 3 ; `onCellMoved` hors édition ne fait rien.
- `BoardEditingTest` : `moveCell` vers un emplacement libre ; échange avec une case (les deux positions vérifiées, données asymétriques) ; case déjà déplacée ailleurs → `CellChangedException`, rien d'écrit ; cible hors grille refusée.
- `slotAt` : coin, espacement entre deux cases (aucun emplacement), dehors.

Instrumentés (émulateur, `ANDROID_SERIAL=emulator-5554`) :
- `MigrationTest` 4 → 5 : position 0 posée sur l'écran de départ choisi (un accueil moyen) et pas sur l'accueil standard ; cases et liens comptés avant et après ; index unique présent.
- `BoardDaoTest` : `insertScreen` puis `deleteScreen` avec dossiers imbriqués (RESTRICT réel) ; `moveCell` en échange sous l'index unique réel ; deux écrans à la même position refusés.
- `SettingsDialogTest` : la page Écrans liste dans l'ordre ; pas de « Supprimer » pour le dernier écran.
- `BoardScreenLayoutTest` : aucun onglet avec un écran, onglets avec deux.

Tests existants à adapter : `BoardSizeTest` (setStartBoard disparaît : remplacé par la taille par écran), `BoardRepositoryTest`, `BoardProfilesTest` (nouvel utilisateur : 6 grilles au lieu de 14), `FakeBoardDao` (commodité et index des écrans), `ProfileSwitchTest`, `BoardViewModelTest` (tests de `onBoardSizeChosen` et `SettingFailure`), `EditEntryTest` et `SettingsDialogTest` (page Taille des cases), `BoardDaoTest` (`startBoardCopyOf`, `isStartBoardOf`, `copiesOf`), `BoardBackupTest`, `DatabaseCorruptionTest`, `MigrationTest` (`Migration4To5` enregistrée dans `buildDatabase`, ses tests rouvrent en version 5).

Ajouts après revue : `ScreensTest` et `BackupArchiveTest` avec un profil dont toutes les grilles sont visées (l'export passe) ; `MigrationTest` 4 → 5 avec un second profil sans `start_board_id`, un profil dont `start_board_id` vise la grille d'un autre, `foreign_key_check` vide et ids inchangés ; `BoardScreenLayoutTest` : avec deux écrans, les bornes d'une case ne changent pas entre la première grille affichée et l'état stable ; instrumenté : appui long immobile puis relâcher, l'éditeur ne s'ouvre pas.

Docs : `docs/TODO.md` (MVP-11 supprimée ; décision « écrans » ajoutée, avec les accueils cachés des appareils de développement ; décisions « une taille = un jeu », « profils » et « Réglages » révisées ; TODO-8 : schémas 1 à 5 ; MVP-6 : un mot ajouté vise chaque écran copié du vocabulaire), `.claude/socle.md` (faux positif R15 de `BoardSizePage.kt` suivi).
