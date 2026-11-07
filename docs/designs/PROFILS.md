# PROFILS : utilisateurs et accompagnants

Demande de Franck (2026-11-07) : une gestion des profils, pour les utilisateurs (les personnes qui communiquent) et pour les accompagnants ; seuls les accompagnants ont accès aux Réglages.

Choix tranchés par Franck le 2026-11-07 :
- chaque utilisateur a **ses propres grilles** (sa copie du vocabulaire de base, ses cases, ses photos, sa taille de cases, son écran de départ) ;
- chaque accompagnant a un **code à 4 chiffres** pour ouvrir les Réglages ;
- **tous les accompagnants ont les mêmes droits** ;
- au lancement, l'appli **rouvre le dernier utilisateur** ; changer d'utilisateur se fait dans les Réglages ;
- code oublié : **« Code oublié ? » ouvre une multiplication** (ex. 17 × 8) ; une bonne réponse permet de choisir un nouveau code pour cet accompagnant.

L'appli n'est pas publiée (`versionCode = 1`) : les bases en version 3 et les sauvegardes au format 1 n'existent que sur les appareils de développement et de test. La migration et la lecture du format 1 restent (invariant 4), sans code de compatibilité au-delà.

## Contrat et fichiers touchés

Deux commits. A se livre seul ; B vient après A.

### A. Utilisateurs : chacun ses grilles

**Comportement.** Les Réglages gagnent une section « Utilisateurs » en tête : la liste (bouton radio sur l'utilisateur ouvert), « Ajouter un utilisateur », et pour chacun « Modifier » (renommer, supprimer). Toucher un autre utilisateur l'ouvre derrière les Réglages : son écran de départ, phrase vidée (c'était celle de l'autre), éditeur et recherche fermés. Un nouvel utilisateur reçoit un vocabulaire de base neuf, en taille standard ; il ne devient pas l'utilisateur ouvert. Supprimer un utilisateur demande confirmation (« Les grilles de Léo seront supprimées pour de bon, photos comprises. ») ; le dernier ne se supprime pas (bouton absent) ; pour supprimer l'utilisateur ouvert, on passe d'abord au plus ancien des autres, puis on supprime. La taille des cases s'applique à l'utilisateur ouvert (titre « Taille des cases (Léo) », sans élision à gérer) ; la sauvegarde `.obz` couvre tous les utilisateurs. Au lancement, le dernier utilisateur ouvert se rouvre ; l'enfant ne voit jamais d'écran de choix.

**Données (Room, version 4, migration manuelle 3 → 4).**
- Nouvelle table `profiles` (`id` auto, `name`, `start_board_id` nullable, sans clé étrangère : revalidé à chaque lecture).
- `boards` gagne `profile_id` (NOT NULL, clé étrangère vers `profiles` en RESTRICT, indexée) et `core_board_id` (nullable : l'id, dans `CoreVocabulary`, de la grille dont elle est la copie ; `null` pour une grille de l'adulte).
- Migration 3 → 4 (`data/profile/Migration3To4.kt`, classe qui reçoit le nom par défaut ; construite dans `buildDatabase` avec le `Context`, et réutilisable par `MigrationTest`) :
  `CREATE TABLE profiles` (copie exacte du `createSql` de `4.json`) ; `INSERT` du profil 1 (« Utilisateur ») ;
  `ALTER TABLE boards ADD COLUMN profile_id INTEGER NOT NULL DEFAULT 1 REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT` ;
  `ALTER TABLE boards ADD COLUMN core_board_id INTEGER` ;
  `UPDATE boards SET core_board_id = id WHERE id < 1000 AND NOT (id IN (7, 13) AND id IN (SELECT linked_board_id FROM cells WHERE linked_board_id IS NOT NULL))` (avant les tailles, un dossier de l'adulte pouvait avoir l'id 7 ou 13 : il ne doit pas passer pour un accueil de taille ; les ids 7 et 13 viennent de `BoardSize`) ;
  `CREATE INDEX index_boards_profile_id`.
  **Aucune recréation de `boards`** : un `DROP TABLE boards` avec les clés étrangères actives viderait `cells` par la CASCADE ; l'ajout de colonnes ne touche à aucune ligne. Room active les clés étrangères dans `onOpen`, après la migration ; si un jour il les activait avant, SQLite refuserait l'`ADD COLUMN … REFERENCES` à valeur par défaut non nulle : échec bruyant, base intacte, et `MigrationTest` le verrait. Le `DEFAULT 1` en base ne gêne pas la validation de Room (il ne compare la valeur par défaut que si l'entité en déclare une).
- `BoardEntity` : `profileId` et `coreBoardId` ajoutés en fin de constructeur, avec valeurs par défaut (`profileId = NO_PROFILE` = 0, `coreBoardId = null`), seulement parce que `CoreVocabulary.kt` est généré (`render_kotlin` de `tools/arasaac/fetch_pictograms.py`) et que sa régénération demande le cache d'images. Un oubli se voit : sur appareil, la clé étrangère refuse `profile_id = 0` ; sur la JVM, `FakeBoardDao` lève sur toute grille dont le profil n'existe pas (comme il imite déjà RESTRICT). Chaque chemin d'écriture pose les deux champs explicitement, et le test d'aller-retour de la sauvegarde vérifie `coreBoardId` et `start_board_id`.
- Graine (`seedIfEmpty`) : s'il n'existe aucun profil, insère le profil 1 ; puis le vocabulaire à ses ids fixes, au plus petit profil, `coreBoardId = id` ; les autres profils, restés sans grille (base abîmée), sont supprimés dans la même transaction (`Log.w` avec leur nombre). Une installation neuve a exactement les ids d'aujourd'hui. La détection de perte (`countBoards`) reste globale.
- Nouvel utilisateur (`insertProfileWithCoreVocabulary`, une transaction) : insère le profil, puis `CoreVocabulary` passé par `VocabularySeed.forProfile(profileId, firstBoardId)` (fonction pure : ids contigus à partir de `max(plus grand id + 1, FIRST_USER_BOARD_ID)`, dossiers renumérotés, `coreBoardId` = id d'origine, positions intactes, cases à id 0).
- Nouveau dossier (`insertFolder`) : la grille prend le `profile_id` **de la grille parente**, lu dans la transaction, jamais l'utilisateur ouvert. Un dossier ne relie donc jamais deux utilisateurs, même si l'éditeur écrit pendant un changement d'utilisateur.
- Suppression d'un utilisateur (`deleteProfile`, une transaction) : refusée si c'est le dernier qui a des grilles ; photos de ses cases lues ; ses cases supprimées d'abord (RESTRICT des dossiers), puis ses grilles, puis le profil. Rend les photos, effacées ensuite comme après `deleteCell`.
- Utilisateur ouvert : clé DataStore `current_profile_id`, écrite **hors de `write {}`** (pas dans le `.obz`, la pastille « non sauvegardé » ne s'allume pas). Lecture ratée (`IOException`) : `Log.e` et repli comme ci-dessous, on parle quand même.
- **Écran de départ, chaque étape filtrée sur l'utilisateur** (`ensureHomeBoard`) :
  1. utilisateur = `current_profile_id` s'il existe et a au moins une grille, sinon le plus petit utilisateur qui a des grilles (`Log.w`, clé réécrite) ;
  2. `profiles.start_board_id` si la grille existe, appartient à cet utilisateur et qu'aucune case ne la vise ;
  3. sinon la grille de cet utilisateur dont `core_board_id = HOME_BOARD_ID` et qu'aucune case ne vise ;
  4. sinon sa plus petite grille (`Log.e`).
  Rend l'id de la grille. Un `start_board_id` restauré qui vise la grille d'un autre utilisateur tombe à l'étape 3.
- Choisir une taille (`setStartBoard(size)`) : la grille de l'utilisateur ouvert dont `core_board_id = size.homeBoardId` et qu'aucune case ne vise ; écrit `profiles.start_board_id` dans `write {}` (c'est désormais dans le `.obz`) ; rend son id, ou `null` si l'utilisateur ne l'a pas. L'ancienne clé DataStore `start_board_id` n'est plus lue (appli non publiée : le choix de taille d'un appareil de développement repart en standard).
- **Taille affichée** : `BoardSize.withHome(coreBoardId)`. Le DAO expose `observeCoreBoardId(boardId): Flow<Long?>` ; `displayedBoard` combine la grille du haut de la pile avec le `core_board_id` du bas ; `DisplayedBoard.rootCoreBoardId` le porte ; `BoardUiState.boardSize` et la taille d'un nouveau dossier (`BoardViewModel.kt:556`) le lisent. `BoardSize.homeBoardId` garde son sens : l'id de l'accueil DANS `CoreVocabulary`.
- Grilles protégées à la suppression d'une case : celles dont `core_board_id` est un accueil de taille (requête), plus la grille de la case et la racine ouverte.
- Le ménage des images reste global (références de tous les utilisateurs).

**Changement d'utilisateur (ViewModel).** `BoardRepository.switchProfile(id)` écrit la clé puis monte un compteur `profileSwitches` (comme `replacements`). Le `BoardViewModel` l'écoute : il annule `sizeChoice` et `homeOpening` (une ouverture en cours a pu lire l'ancien utilisateur), ferme éditeur, recherche et photo en attente, vide la phrase et la pile, puis relance `reopenHome()`. `onBoardsReplaced` fait de même (annulation comprise). Une pile sauvée dans le `SavedStateHandle` qui ne commence pas à l'accueil rendu est déjà abandonnée (`BoardViewModel.kt:663`).

**Sauvegarde `.obz` (format 2).** Tous les utilisateurs. Manifeste : `ext_papotons_profiles` (`id`, `name`, `start_board_id`) ; chaque grille : `ext_papotons_profile_id`, `ext_papotons_core_board_id`. `BackupContent(profiles, boards, cells)`. `validate` refuse en plus : aucun profil, plus de `MAX_PROFILES` (100), id de profil répété ou hors bornes, nom vide ou trop long (`MAX_PROFILE_NAME_LENGTH`, la règle `isValidProfileName` que vérifient aussi la création et le renommage), grille d'un profil absent, profil sans grille, dossier qui vise une grille d'un autre profil. `start_board_id` n'est pas validé (repli à la lecture). Lecture du format 1 : un seul profil (id 1, nom par défaut), `core_board_id` comme dans la migration. `replaceAll` remplace aussi les profils (cases, grilles, profils supprimés ; profils, grilles, cases insérés). Racine OBF : l'accueil standard du premier profil, sinon la plus petite grille.

**Confirmation de restauration.** Elle nomme ce qui part et ce qui arrive : « Les grilles actuelles (Léo, Marie) seront remplacées par celles de la sauvegarde (Léo), photos comprises. » (`PendingRestore` porte les profils ; `BackupViewModel` lit les noms actuels par `observeProfiles`).

**UI.** `ui/settings/ProfilesSection.kt` (liste, ajout, « Modifier ») et `ui/settings/ProfileDialog.kt` (nom, Enregistrer, Supprimer, Annuler ; confirmation de suppression), branchés par un `ProfilesViewModel` (Hilt, comme `BackupViewModel`) : profils observés, id ouvert, échecs affichés. Nom obligatoire, une ligne, espaces superflus retirés, 40 caractères au plus. Modales : `DialogProperties(dismissOnClickOutside = false)`.

**Fichiers.** `data/{PapotonsDatabase,DatabaseModule}.kt`, nouveaux `data/profile/{ProfileEntity,Migration3To4}.kt`, `data/board/{BoardEntity,BoardDao,BoardRepository,BoardWithCells,ProfileVocabulary}.kt`, `data/backup/{BackupArchive,BoardBackup}.kt`, `domain/{Board,BoardSize}.kt` (KDoc), `ui/board/{BoardViewModel,BoardUiState,BoardScreen}.kt`, `ui/backup/{BackupViewModel,RestoreConfirmDialog}.kt`, `ui/settings/{SettingsDialog,ProfilesSection,ProfileDialog,ProfilesViewModel}.kt`, `res/values/{strings_profiles,strings_backup,strings_settings}.xml`, `app/schemas/…/4.json`, tests (inventaire plus bas).

### B. Accompagnants : un code pour les Réglages

**Comportement.**
- Aucun accompagnant (installation neuve, mise à jour) : le contrôle par addition d'aujourd'hui, inchangé. La section « Accompagnants » des Réglages invite à en créer un.
- Au moins un accompagnant : la roue dentée (et « J'ai compris » / « Restaurer » des grilles perdues) ouvre le contrôle des accompagnants : choix du nom (présélectionné s'il n'y en a qu'un ; sinon « Valider » et « Code oublié ? » attendent le choix), code à 4 chiffres (« Valider » attend 4 chiffres ; clavier `Number` masqué, jamais `NumberPassword` que l'autoremplissage d'Android enregistrerait ; focus à l'ouverture s'il est seul ; Entrée valide). Mauvais code : toast « Code incorrect », le contrôle se referme (pas d'essais en rafale).
- « Code oublié ? » : multiplication (12 à 29 × 3 à 9) ; mauvaise réponse : le contrôle se referme ; bonne réponse : « Nouveau code pour Marie » (code puis confirmation), enregistré, puis l'action demandée s'ouvre. Si l'écriture échoue : toast « Le code n'a pas pu être changé », l'action s'ouvre quand même (l'adulte a répondu). Aucun de ces états n'est sauvé (`remember`, jamais `rememberSaveable` ni `SavedStateHandle`) : après la mort du processus, tout reprend au contrôle.
- Un code changé par « Code oublié ? » est signalé dans la section « Accompagnants » (« Code changé par Code oublié le 7 novembre »), jusqu'au prochain changement de code ordinaire : le vrai verrou de secours est la multiplication, l'accompagnant doit pouvoir voir qu'elle a servi.
- Réglages, section « Accompagnants » : la liste, « Ajouter un accompagnant » (nom, code, confirmation), et pour chacun « Modifier » (renommer, changer le code, supprimer avec confirmation ; supprimer le dernier prévient que les Réglages ne seront plus protégés que par un calcul). Tous ont les mêmes droits.
- En mode édition, la roue dentée ouvre toujours les Réglages sans contrôle (l'adulte l'a passé pour entrer ; l'édition se termine à tout départ de l'appli, `ON_STOP`). Inchangé.

**Stockage : un DataStore à part (`caregivers`), hors de Room.** Une table Room partirait dans le `.obz` avec une copie de base corrompue (`BoardBackup.putRecoveryMaterial`). Un seul champ texte JSON (liste : `id`, `name`, `codeHash`, `codeSalt` en Base64, `codeResetAtMs`), lu et écrit dans un seul `updateData`/`edit`. Code jamais en clair : PBKDF2WithHmacSHA256 (API 26+), sel de 16 octets (`SecureRandom`), comparaison en temps constant (`data/caregiver/CaregiverCode.kt`, testé sur la JVM). Fichier illisible : `ReplaceFileCorruptionHandler` repart vide, `Log.e` ; le contrôle retombe sur l'addition (un adulte peut toujours entrer, un enfant reste arrêté par le calcul). Comme tout `files/datastore/`, ce fichier part dans la sauvegarde Android (décision actée « sauvegarde Android complète ») : chiffrée par Google, de bout en bout seulement si l'appareil a un verrouillage d'écran. Assumé ; il ne part jamais dans un `.obz`.

**Fichiers.** nouveaux `data/caregiver/{CaregiverCode,Caregiver,CaregiverRepository,CaregiverModule}.kt`, `domain/Caregiver.kt`, `ui/edit/{RecoveryQuestion,CaregiverGateDialog}.kt` (`AdultGateDialog` y aiguille vers le calcul ou le code), `ui/settings/{CaregiversSection,CaregiverDialog,CaregiversViewModel}.kt`, `ui/board/BoardScreen.kt`, `ui/settings/SettingsDialog.kt`, `res/values/strings_caregivers.xml`, tests.

### Invariants

1. Rien de réseau, rien de collecté.
2. Parler ne dépend que des grilles locales de l'utilisateur ouvert ; une lecture ratée de l'utilisateur ouvert ouvre quand même des grilles.
3. Aucune case ne bouge : un nouvel utilisateur reçoit le vocabulaire aux positions de l'instantané ; la migration ne renumérote rien.
4. Migration par ajout de colonnes (vérifiée par `MigrationTest` : cases et liens comptés avant et après) ; suppression d'un utilisateur seulement sur demande confirmée ; un dossier ne relie jamais deux utilisateurs (sinon l'export serait refusé) ; l'export couvre tous les utilisateurs ; la restauration dit qui elle remplace ; le format 1 se restaure toujours.
5. Inchangé.

### Conflit à prévoir

Une session de conjugaison a un worktree `feat/conjugaison` (IDEE-1, non commité au moment du plan) qui modifie `BoardViewModel`, `BoardScreen`, `BoardUiState`, `CommunicationGrid`. La seconde livraison sur `main` fusionnera à la main.

## Envisagé et écarté

- Grilles communes à tous les utilisateurs : écarté par Franck.
- Renuméroter le profil 1 ou réserver une plage d'ids par profil : risqué pour rien ; `core_board_id` suffit.
- Recréer `boards` dans la migration (ou `AutoMigration`, qui le ferait) : un `DROP TABLE` ne serait sûr que tant que Room laisse les clés étrangères désactivées pendant la migration.
- Écran de choix de l'utilisateur au lancement : écarté par Franck.
- Accompagnants en Room : leurs hachés partiraient dans le `.obz` avec une copie de base corrompue.
- Exclure le fichier des accompagnants de la sauvegarde Google : contredit la décision actée « sauvegarde Android complète » ; à rouvrir avec Franck s'il le souhaite.
- Droits différents entre accompagnants : écarté par Franck. Savoir quel accompagnant a fait quoi : reporté.
- Photo ou avatar d'utilisateur, dupliquer les grilles d'un utilisateur, export d'un seul utilisateur : reportés.
- Délai ou blocage après plusieurs mauvais codes : refermer à la première erreur suffit (MVP-3) ; de toute façon la multiplication de secours est le vrai plancher.
- `ProfileRepository` séparé : les opérations d'utilisateurs écrivent des grilles et doivent passer par le même `write {}`.
- Supprimer les valeurs par défaut de `BoardEntity` et changer le générateur : régénérer `CoreVocabulary.kt` demande le cache d'images ; la clé étrangère (et sa copie dans `FakeBoardDao`) rend l'oubli bruyant.
- Recopier l'ancienne clé `start_board_id` sur le profil 1 : appli non publiée.
- Demander le prénom au premier lancement : un écran de plus avant de parler ; « Utilisateur », renommable.

## Observabilité

- Migration 3 → 4 qui échoue : Room lève à l'ouverture (plantage dans Android vitals), la transaction de migration laisse la base d'avant.
- Création, suppression, changement d'utilisateur : `Log.i` avec l'id et le nombre de grilles (jamais le nom). Échec d'écriture : `Log.e` avec l'exception, et un message dans les Réglages (« L'utilisateur n'a pas pu être créé. Réessayez. », etc.).
- Utilisateur ouvert introuvable ou sans grille : `Log.w` avec l'id, repli. Écran de départ invalide : `Log.w` avec l'id, repli (gardé en base : l'effacer allumerait « non sauvegardé »).
- Sauvegarde format 1 lue : `Log.i`. Refus de `validate` : raison détaillée au journal comme aujourd'hui.
- Codes : jamais au journal. Mauvais code : `Log.i` sans le code. Code changé par « Code oublié ? » : `Log.i` avec l'id de l'accompagnant, et le repère visible dans les Réglages. Fichier des accompagnants illisible : `Log.e`, retour à l'addition.

## Retries et limites

Aucun réseau. PBKDF2 : itérations en constante nommée, calcul sur `Dispatchers.Default`, une vérification par ouverture du contrôle (à mesurer sur l'émulateur : sous 300 ms visé).

## Plan de test

JVM :
- `ProfileVocabularyTest` (écrit) : ids contigus depuis une base asymétrique, liens renumérotés, `coreBoardId`, positions.
- `BackupArchiveTest` : aller-retour avec deux profils (2 et 1 grilles), `coreBoardId` et `start_board_id` compris ; format 1 lu en un profil ; refus : dossier entre deux profils, grille d'un profil absent, profil sans grille, nom vide.
- `BoardRepositoryTest` (`FakeBoardDao` avec clé étrangère de profil) : un nouvel utilisateur (grilles ≥ 1000, `start_board_id` nul) s'ouvre sur SA grille, pas sur 1 ; `start_board_id` qui vise la grille d'un autre → son propre accueil ; utilisateur ouvert disparu → plus petit ; écran de départ par utilisateur (le choix de l'un ne change pas l'autre) ; `setStartBoard(MEDIUM)` chez l'utilisateur 2 rend sa grille, pas 7 ; un dossier ajouté dans une grille de l'utilisateur 2 pendant que l'utilisateur 1 est ouvert appartient à 2 ; suppression du dernier refusée ; suppression laisse les grilles de l'autre ; `switchProfile` n'allume pas « non sauvegardé ».
- `BoardViewModelTest` : changer d'utilisateur vide la phrase, ferme l'éditeur, ouvre l'accueil du nouveau ; un choix de taille en cours n'écrase pas le changement ; la taille affichée de l'utilisateur 2 (ids ≥ 1000) se lit par `coreBoardId`, y compris dans un dossier ; un dossier créé sous ses grandes cases est en 2 × 3.
- `CaregiverCodeTest`, `RecoveryQuestionTest` (écrits) ; `CaregiverRepositoryTest` (sur un DataStore de test) : ajout, vérification, changement par secours (repère posé), changement ordinaire (repère retiré), fichier vide → aucun accompagnant.

Instrumentés (émulateur) :
- `MigrationTest` 3 → 4 : profil 1 créé, ids, cases et liens comptés avant et après (chaîne de dossiers où le parent a un id plus grand que l'enfant), `core_board_id` posé sur les ids du vocabulaire mais pas sur un 7 visé par un dossier, `foreign_key_check` vide.
- `BoardDaoTest` : `insertProfileWithCoreVocabulary` puis `deleteProfile` sur un profil avec dossiers imbriqués (RESTRICT réel), l'autre intact ; une grille à `profile_id = 0` refusée.
- Contrôle des accompagnants : mauvais code referme, bon code ouvre les Réglages (calqué sur `EditEntryTest`).

Tests existants à adapter : `BoardDaoTest` (6 sites), `BoardBackupTest` (5), `DatabaseCorruptionTest` (2) insèrent des grilles sans profil (aide de test qui insère le profil 1 et pose `profileId`) ; `BoardSizeTest` (ancienne clé, `setStartBoard(): Boolean`) ; `BackupArchiveTest`, `BoardRepositoryTest`, `BackupViewModelTest`, `BoardViewModelTest` (`BackupContent` à trois champs, `FakeBoardDao` avec profils) ; `MigrationTest` passe la migration construite.
