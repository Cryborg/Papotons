# TODO-8 : récupérer les grilles d'une copie de base corrompue

Ce document porte d'abord ce qu'on sait des copies corrompues, mesuré le 2026-11-07 (le prérequis, qui a changé le plan : pas de `PRAGMA integrity_check` comme contrôle d'entrée), puis le plan de livraison.

## Prérequis levé : de vraies copies corrompues

- **Fixtures** : `app/src/androidTest/assets/corrupted-databases/`, fabriquées par `tools/corruption/make_corrupted_fixtures.py` (déterministe, jamais éditées à la main). Une base saine au schéma 5, que Room ouvre comme celle de l'appli (identity hash compris) : 2 utilisateurs, 7 grilles (ids 1 à 4 et 1000 à 1002), 224 cases dont une sur cinq avec photo et une sur trois avec une phrase à elle ; 14 pages de 4 Kio, table `cells` sur trois feuilles. Puis six copies abîmées chacune d'une seule façon.
- **Test** : `DatabaseCorruptionFixturesTest` (instrumenté). Chaque base est posée à la place de la base de l'appli et ouverte par `buildDatabase`, donc par `CorruptionCopyingOpenHelperFactory`. On note ce que rend la première requête de l'appli (`countBoards`), si une copie existe alors, ce que rend la lecture complète de l'export (`snapshot`), puis, sur la copie (ou la base restée en place) recopiée à part et ouverte en lecture seule avec un gestionnaire d'erreurs muet : `PRAGMA integrity_check`, et les lignes relues une à une par leur id, comparées à la base saine.
- **Où** : émulateur API 37 (SQLite 3.50.6) et émulateur API 26 (SQLite 3.18.2, celui d'Android 8, minSdk). Les deux lisent les fixtures de la même façon ; seule différence : sur `cells-page-random-bytes`, le 3.18 abandonne l'`integrity_check` que le 3.50 mène au bout. L'appli ne s'en sert pas.

## Constats

| Panne | Déclarée corrompue (copiée) | Quand | `integrity_check` sur la copie | Relu intact (utilisateurs / grilles / cases) | Relu altéré |
|---|---|---|---|---|---|
| Base saine | non | | `ok` | 2 / 7 / 224 | 0 |
| En-tête : les 16 octets « SQLite format 3 » à zéro | oui | à l'ouverture | s'arrête (« file is not a database ») | 0 / 0 / 0 | 0 |
| Fichier coupé au milieu d'une page (7,3 pages sur 14) | oui | à l'ouverture | s'arrête (« malformed ») | 0 / 0 / 0 | 0 |
| Page de la table `boards` remplie d'octets aléatoires | oui | lecture complète ; `countBoards` rend 7 | s'arrête | 2 / 0 / 224 | 0 |
| Page de l'index unique des cases remplie d'octets aléatoires | oui | lecture complète | s'arrête | 2 / 7 / 224 | 0 |
| Feuille de `cells` : seconde moitié à zéro | oui | lecture complète | liste 57 problèmes | 2 / 7 / 180 | 1 |
| Feuille de `cells` : trois rafales de 16 octets aléatoires | oui | lecture complète | liste 2 problèmes | 2 / 7 / 221 | 2 |

1. **Aucune base déclarée corrompue ne passe le contrôle.** Et dans quatre cas sur six, `integrity_check` ne va même pas au bout : il lève. Comme porte d'entrée (« `ok`, on restaure la copie telle quelle »), il refuserait toutes les copies, y compris celles dont on relit tout.
2. **Pourtant, presque tout se relit dans quatre cas sur six**, ligne par ligne par l'id (alias du rowid : seul l'arbre de la table est lu, aucun index). La perte reste dans la page abîmée : un index détruit ne coûte aucune ligne, une feuille de cases détruite coûte ses cases (les 83 à 125), une page de grilles détruite coûte les grilles mais laisse leurs 224 cases orphelines.
3. **Une ligne relue sans erreur peut être fausse**, et rien ne dit laquelle : la case 126 garde un nom de photo coupé par des zéros ; les 166 et 201 reviennent avec libellé, picto, catégorie ou phrase brouillés (« cont\xC9\x1E\xD4 », picto 6029). La 181 est perdue, son rowid réécrit en 180.
4. **En-tête et fichier coupé : rien ne se lit par SQLite.** Réécrire les 16 octets de l'en-tête sur une copie rend tout (`ok`, 224 cases, vérifié sur le poste), mais une vraie panne d'en-tête touche rarement ces seuls octets. Le fichier coupé reste illisible même en recalant la taille de l'en-tête : le schéma vise des pages disparues. Seule une lecture page par page (`.recover` de l'outil `sqlite3`, absent du SQLite d'Android) en tirerait quelque chose : affaire d'expert, la copie dans le `.obz` reste la voie.
5. **La corruption n'est vue qu'à l'ouverture ou quand une requête touche la page.** Seules les deux pannes de l'en-tête et du fichier coupé sont vues à l'ouverture. `countBoards`, première requête de l'appli, compte par un index : une page de grilles détruite lui échappe. Les quatre autres pannes sont vues en cours d'usage (ici à l'export ; dans l'appli, dès qu'une grille touchée s'affiche).
6. **Vue en cours d'usage, la corruption laisse l'instance de Room morte.** La fabrique copie bien les trois fichiers (base, `-wal`, `-shm`), puis le callback par défaut ferme la base et la supprime ; ensuite chaque requête sur la même instance lève `android.database.SQLException: connection is closed` : Room ne rouvre jamais l'unique connexion d'une instance. Corrigé par le TODO-16 (`docs/designs/TODO-16.md`) : `PapotonsDatabases` ouvre alors une instance neuve sur une base neuve, et l'écran repart de l'accueil avec l'alerte. Le test fixe désormais `countBoards` à 0 après chacune de ces pannes.

## Ce qui change pour le plan

- **Plus d'`integrity_check` comme contrôle.** Au mieux un indice affiché, et encore : il lève plus souvent qu'il ne répond.
- **Une récupération ligne par ligne** : copie recopiée à part, ouverte en lecture seule avec un `DatabaseErrorHandler` muet (le gestionnaire par défaut supprimerait la copie), puis chaque table relue par id. Les ids vont de 1 au dernier donné, que `sqlite_sequence` retient (les trois tables sont en `AUTOINCREMENT`) ; si cette page-là est abîmée, un parcours `SELECT *` jusqu'à la première erreur.
- **Chaque ligne relue est validée avant d'entrer** : case dont la grille est retrouvée, position dans la taille de la grille et pas déjà prise, catégorie connue (sinon sans catégorie), picto présent sur l'appareil (sinon sans picto), photo présente (sinon sans photo), libellé non vide et sans caractère de remplacement ni de contrôle. Une case qui échoue reste dehors : son emplacement reste vide, aucune case ne glisse à sa place (invariant 3).
- **L'adulte voit ce qui revient avant de confirmer** : « 7 grilles, 180 cases sur 224 », avec les grilles à trous. Une valeur fausse mais plausible ne se détecte pas : la récupération est un « mieux que rien » à relire, pas une restauration à l'identique. La confirmation et la transaction restent celles de la restauration commune (MVP-4).
- **Hors champ au départ** : rebâtir des grilles perdues dont les cases survivent (nom et taille inconnus) ; réparer l'en-tête ; lire page par page. Le `.obz` garde la copie pour un expert.
- **Mesuré sur l'API 26** : les mêmes fixtures, la même détection, et la récupération rend les mêmes nombres de lignes qu'à l'API 37 (`CorruptedCopyRecoveryFixturesTest`, 19 tests), copie prise en WAL sans `-shm` comprise. La dernière feuille abîmée se relit en 3 s, comme à l'API 37.

## Plan de livraison

### Contrat

Pour l'adulte, en édition (derrière le contrôle parental) : dans la modale Sauvegarde, un bouton « Récupérer les grilles abîmées » apparaît tant qu'une copie existe dans `files/corrupted-databases/`. Il relit chaque copie et montre ce qui revient. S'il y en a plusieurs, l'adulte en choisit une (date de la copie, utilisateurs, nombre de grilles et de cases). Puis la confirmation de la restauration commune, avec le bilan (« 7 grilles et 180 cases sur 224 ») et un avertissement : des cases peuvent manquer ou être fausses, à relire. Confirmer remplace toutes les grilles, en une transaction, comme une restauration. Rien de lisible : un message le dit, et la copie reste dans le `.obz` pour un expert.

L'enfant ne voit rien de nouveau. La modale « Grilles perdues » dit qu'on peut essayer de récupérer les grilles depuis la Sauvegarde.

### Lecture d'une copie (`data/backup/CorruptedCopyReader.kt`, Android)

1. La base de la copie et son `-wal` (les dernières écritures) sont recopiés dans `no_backup/recovery/`, vidé avant et après. SQLite n'ouvre jamais la copie elle-même. Ni `-shm` (SQLite le rebâtit depuis le WAL ; pris à un autre instant, il le contredirait) ni `-journal` : en lecture seule, un journal chaud rendrait la base illisible (`SQLITE_READONLY_ROLLBACK`). Sans lui, une transaction à moitié écrite (base en TRUNCATE : appareil à faible RAM, instance de relève du TODO-16) se relit comme toute page abîmée, et la validation la filtre.
2. Ouverture `OPEN_READONLY | NO_LOCALIZED_COLLATORS` avec un `DatabaseErrorHandler` muet : le gestionnaire par défaut supprimerait le fichier. En lecture seule, Android ne touche ni au journal ni à la langue.
3. `PRAGMA user_version` : hors de 1 à 5, la copie est illisible.
4. Par table (`profiles` à partir du schéma 4, `boards`, `cells`) : une table qui ne se prépare pas (absente, schéma brouillé) est sautée. D'abord la table entière (`SELECT *`) : une requête sur une table saine, et un parcours qui suit les cellules sans comparer les clés. Sur le SQLite 3.46 du poste, une clé brouillée en tête de feuille fait rendre zéro ligne à `BETWEEN 1 AND 100`, alors que le parcours rend tout. Les SQLite d'Android se rattrapent seuls, le 3.18 de l'API 26 comme le 3.50 de l'API 37 : sans le parcours, la fixture `cells-first-key-garbled` rend quand même ses 224 cases (mutation mesurée sur les deux émulateurs). Le parcours reste une garde peu coûteuse pour une clé brouillée autrement. S'il lève : des tranches `WHERE id BETWEEN a AND b` de 512 ids, de 1 au plus grand. `min(id)` n'est pas un départ : la première clé de la feuille de gauche, brouillée, se lit sans erreur. Le plus grand : `max(id)` (chemin le plus à droite), relevé par `sqlite_sequence` ; si `max(id)` ne se lit pas, la séquence n'est qu'une borne basse et la lecture va jusqu'à 10 millions (`MAX_RECOVERY_ID`, aussi le plafond d'un `max(id)` brouillé). Une tranche qui lève est jetée et relue id par id. La lecture s'arrête après 64 tranches abîmées de suite sans un id relu (32 768 ids) : au-delà du vrai dernier id, chaque recherche descend dans la dernière feuille abîmée. Une tranche saine et vide coûte une requête. Pas de lecture jusqu'à la première erreur : la fenêtre du curseur se remplit d'un bloc, et l'erreur ferait perdre les lignes déjà lues. Entre deux tranches, une lecture abandonnée (fin d'édition) s'arrête et rend le verrou. Limites connues : une feuille de gauche abîmée devant un premier id au-delà de 32 768 arrête la lecture avant lui ; une clé brouillée sans erreur dans une table qui lève par ailleurs peut cacher des lignes aux tranches.
5. Chaque ligne est rendue colonne par nom, avec le type que SQLite lui donne. Un texte est décodé en UTF-8 strict depuis ses octets : `getString` ne vérifie pas les octets de suite, et un texte brouillé passerait. Un texte qui ne se décode pas reste en octets, donc n'est pas du texte pour la validation.
6. Une page abîmée est une `SQLiteException`, ou une `IllegalStateException` avant Android 9 (ligne trop grosse pour la fenêtre ; ensuite `SQLiteBlobTooBigException`) : la ligne ou la tranche est illisible, la lecture continue. Une erreur d'E/S, d'ouverture, d'accès ou de mémoire (`SQLiteDiskIOException`, `SQLiteCantOpenDatabaseException`, `SQLiteAccessPermException`, `SQLiteOutOfMemoryException`) peut venir d'un pointeur brouillé (page lue au-delà du fichier, longueur démesurée sur un vieux SQLite) : pendant la lecture, y compris celle du schéma, c'est une page abîmée, sauf si les 512 ids d'une tranche pleine échouent ainsi (une vraie panne ; pas une tranche courte, la seule ligne d'une petite table abîmée s'y lirait comme une panne). À l'ouverture du fichier, elle ne vient pas du contenu : la copie compte comme non relue. `SQLiteFullException`, en lecture seule, ne vient que d'un numéro de page brouillé : une page abîmée. `CursorWindowAllocationException` (reconnue par son nom, publique seulement depuis Android 13) et un manque de mémoire : une panne, la copie compte comme non relue.
7. Le nombre de cases annoncé par `SELECT count(*) FROM cells`, qui passe par un index : `null` s'il lève, s'il est plus petit que ce qu'on a relu ou s'il dépasse `MAX_CELLS`.

### Validation (`data/backup/CorruptedCopyRecovery.kt`, pur, testé sur la JVM)

Ligne par ligne, dans l'ordre des ids. Le premier id gagne une place disputée. Un id d'utilisateur ou de grille en double est écarté ; pas un id de case : une clé brouillée qui en double une autre porte une autre case, et la restauration donne des ids neufs. Au-delà des plafonds de la sauvegarde (utilisateurs, grilles, cases, images distinctes), le reste est écarté. Ce qui revient passe donc toujours `validate`.

- **Utilisateur** : id dans `1..MAX_BOARD_ID`. Un nom illisible ou invalide (`isValidProfileName`) : le nom de repli (« Utilisateur »), pour qu'un octet ne fasse pas tomber l'utilisateur et toutes ses grilles. Un verrou de sortie brouillé revient verrouillé (protégé, comme un nouvel utilisateur). Un id relu deux fois (une clé brouillée sans erreur prend l'id d'un autre) : aucun des deux noms n'est cru, l'utilisateur revient sous le nom de repli, verrouillé ; jamais les grilles d'un enfant sous le prénom d'un autre. Un utilisateur que visent les grilles mais qu'on n'a pas relu (ligne perdue, ou clé brouillée vers un id libre) revient sous le nom de repli, verrouillé, si aucun utilisateur ne se relit ou s'il a au moins deux grilles : un utilisateur naît avec tout un vocabulaire, un `profile_id` brouillé ne touche qu'une grille. Deux utilisateurs refaits portent le même nom : la confirmation le montre.
- **Grille** : id dans `1..MAX_BOARD_ID`, utilisateur relu (schéma 4 et plus), taille dans `1..MAX_GRID_SIDE`. Un nom illisible : « Grille retrouvée ». Une taille brouillée mais plausible passe : la géométrie change, l'avertissement « à relire » le couvre. `core_board_id` hors du vocabulaire de base (`1..999`) : `null` ; brouillé, il changerait la taille des cases et ce que visent les ajouts de vocabulaire.
- **Écrans** : un rang brouillé, hors bornes ou déjà pris chez cet utilisateur ne fait pas tomber l'écran. Il est rangé après les autres. Un utilisateur dont aucun écran ne survit reçoit le premier écran que choisirait la migration 4 → 5 (`legacyFirstScreenOf`). Ainsi, une seule grille perdue ne fait jamais tomber tout un utilisateur. Seul l'ordre des onglets change, aucune case ne bouge. Un utilisateur sans aucune grille est écarté.
- **Case** : grille relue, position dans la taille de la grille et libre, libellé lisible et non vide. Un dossier vers une grille absente ou d'un autre utilisateur : case écartée. Picto absent de l'appareil, ou id hors bornes : sans picto. Photo au nom inconnu ou absente de l'appareil : sans photo. Catégorie inconnue : sans catégorie. Phrase illisible : sans phrase (la case dit son libellé).
- **Lisible** : du texte décodé (pas un autre type), sans U+FFFD ni caractère de contrôle (l'éditeur n'en écrit aucun : une seule ligne, espaces normalisés), dans `MAX_TEXT_LENGTH`.

Puis le passage par `backupContent`, qui fait le travail des migrations : les schémas 1 à 3 se lisent comme le format 1 (un seul utilisateur, au nom par défaut, copies du vocabulaire reconnues), le schéma 4 comme le format 2 (premier écran choisi), le schéma 5 comme le format 3. Écart connu : les grilles visées par un dossier se déduisent des seules cases gardées, pas de toutes les cases comme dans les migrations. Ces schémas n'existent que sur des appareils de développement (aucune version publiée avant le schéma 5). Enfin `validate` : un refus là est un bug, journalisé, et la copie n'est pas proposée.

Les parties perdues (picto, photo, catégorie, phrase, nom remplacé, utilisateur refait) sont comptées pour le journal, avec les lignes écartées. Un `user_version` brouillé dans 1 à 4 sur une base au schéma 5 la ferait lire comme un schéma ancien (un seul utilisateur) : rare, la confirmation nomme les utilisateurs.

Le bilan : la date de la copie (`lastModified` de sa base, que la copie pose et qu'une restauration de la sauvegarde Android rend ; le dossier, qui change de date à chaque fichier recréé dedans, en repli ; le nom porte un numéro, pas une heure), les utilisateurs, les grilles, les cases gardées, les cases annoncées par l'index, les lignes écartées.

### Restauration

`PendingRestore` porte `recovery: RecoveryReport?` (`null` pour une sauvegarde). `BoardBackup.restore` saute l'étape des images de l'archive pour une récupération : elle ne vise que des images présentes sur l'appareil, et le ménage des images ne tourne pas tant qu'une copie existe. `BoardRepository.replaceAll(content, isSaved)` : une récupération ne marque pas les grilles sauvegardées (rien ne les porte hors de l'appareil), la pastille « changements non sauvegardés » reste. Le reste de `replaceAll` vaut aussi pour une récupération : l'alerte de perte est acquittée (l'adulte a agi), l'écran repart du premier écran, et un marqueur impossible à écrire (`markEdited`) fait échouer la récupération avant la base (« La restauration a échoué »). Les grilles refaites depuis la perte sont remplacées sans retour, comme par une restauration : la confirmation nomme les utilisateurs remplacés.

Une copie au moins qui n'a pas pu être relue (stockage, mémoire, refus de `validate`, donc un bug) et aucune autre lisible : « La restauration a échoué », pas « rien de lisible ». Si d'autres se relisent, elles sont proposées et l'échec va au journal. Annuler la confirmation d'une copie choisie ramène au choix, sans tout relire.

L'analyse et la restauration prennent le même verrou (`restoreFileLock`) : une analyse relancée après une fin d'édition attend la précédente, jamais deux lectures dans le même dossier de travail. Une copie élaguée pendant l'analyse (nouvelle corruption) ne se relit pas ; les autres se tentent. Les copies ne s'effacent jamais d'elles-mêmes : le bouton reste, et une copie illisible se relit à chaque appui (quelques secondes au pire).

### Fichiers

- Neufs : `data/backup/CorruptedCopyReader.kt`, `data/backup/CorruptedCopyRecovery.kt`, `ui/backup/RecoveryChoiceDialog.kt`.
- Touchés : `data/backup/BoardBackup.kt` (`BackupStore` : `hasCorruptedCopies`, `analyzeCorruptedCopies`), `data/board/BoardRepository.kt` (`replaceAll`), `ui/backup/BackupViewModel.kt`, `ui/backup/BackupDialog.kt`, `ui/backup/RestoreConfirmDialog.kt`, `ui/board/BoardScreen.kt`, `res/values/strings_backup.xml`, `strings_board.xml` (texte de la perte), `docs/TODO.md` (TODO-8 retiré).
- Tests : `CorruptedCopyRecoveryTest` (JVM), `BackupViewModelTest`, `BoardRepositoryTest`, `CorruptedCopyRecoveryFixturesTest` (instrumenté).

### Invariants

- **3** : aucune case ne glisse. Une case écartée laisse sa place vide ; les positions relues sont gardées telles quelles.
- **4** : rien n'est écrit sans confirmation, en une transaction (celle de la restauration). La copie n'est ni modifiée ni supprimée : elle reste dans les sauvegardes, et on peut récupérer une autre copie ensuite.
- **1** et **2** : aucun réseau. **5** : rien ne change au crédit.

### Envisagé et écarté

- `integrity_check` comme porte : il refuse toutes les copies mesurées (constat 1).
- Lecture id par id de 1 au plus grand id : jusqu'à un million de requêtes après des restaurations successives (les ids de cases ne reviennent jamais en arrière). Les tranches n'interrogent un par un que les ids d'une tranche abîmée.
- Ouvrir la copie de travail en écriture pour laisser SQLite rejouer un journal chaud : Android y réglerait le mode du journal, une écriture sur une base abîmée, pour un cas rare que la validation couvre déjà.
- Ne lire que le schéma 5 (aucune version publiée n'a eu un schéma plus ancien) : l'entrée TODO-8 demande les schémas 1 à 5, et `backupContent` les lit déjà pour les sauvegardes. Le coût est le seul passage par `backupContent`.
- Les grilles devenues inaccessibles (leur dossier perdu) changées en écrans : elles pourraient dépasser les 20 onglets. Elles restent, comptées dans le bilan. Reporté.
- Les grilles à trous nommées dans le bilan (compte par grille dans l'index) : reporté. Le total et l'avertissement « à relire » suffisent en V1.
- Rebâtir des grilles perdues dont les cases survivent, réparer l'en-tête, lire page par page : hors champ (constat 4).
- Un choix automatique de la copie la plus riche : la plus riche n'est pas toujours la bonne (grilles refaites après la perte). Une seule copie lisible : pas de choix, la confirmation directement.
- Une abstraction du lecteur : le lecteur touche SQLite (instrumenté), la validation est une fonction pure qui prend ses lignes. Pas d'interface.

### Observabilité

- Par copie, au journal : version du schéma, lignes relues par table, tranches abîmées, lignes écartées par règle (le nombre, jamais le contenu : des prénoms), cases annoncées, durée. Une copie illisible : la raison (`user_version`, ouverture, refus de `validate`) et la classe de l'exception.
- À l'écran : le bilan, ou « rien n'a pu être relu ». Après la récupération : un message qui demande de relire les grilles.
- Un échec du dossier de travail (`IOException`) : « La restauration a échoué. Les grilles n'ont pas changé. », comme une restauration.

### Retries

Aucun réseau. Pas de nouvel essai automatique : relire la même copie rendrait la même chose.

### Plan de test

- **JVM, validation** (données asymétriques, chaque règle casse un seul test) : chaque règle ci-dessus ; une place disputée garde le premier id ; schémas 3, 4 et 5 lus comme leurs formats ; utilisateur sans écran écarté ; rien de valide : `null` ; bilan (relues, écartées, annoncées).
- **JVM, ViewModel** : bouton présent seulement avec des copies ; aucune copie lisible : message ; une : confirmation directe ; deux : choix, puis confirmation de la choisie ; confirmer restaure celle-là et dit de relire ; fin d'édition : choix et confirmation oubliés.
- **JVM, dépôt** : une récupération ne marque pas les grilles sauvegardées, une restauration si.
- **Instrumenté, fixtures** (`CorruptedCopyRecoveryFixturesTest`) : chacune des bases posée en copie, ce qui revient (utilisateurs, grilles, cases, cases annoncées). Rien pour l'en-tête effacé, le fichier coupé et la page des grilles détruite. Mesuré : 181 cases sur 224 pour la feuille à moitié à zéro (la 126 revient sans sa photo), 222 pour les rafales aléatoires (la 166 revient sans sa photo ni sa phrase, la 201 au libellé brouillé est écartée), 224 pour l'index abîmé. Une huitième fixture, `cells-last-page-and-sequence-random`, abîme la dernière feuille des cases et `sqlite_sequence` : 163 cases reviennent, la lecture s'arrête d'elle-même (3 s sur l'émulateur).
- **Instrumenté, de bout en bout** : une base en WAL, une écriture restée dans le `-wal`, la corruption vue en cours d'usage (copie par la fabrique, avec son `-wal`), puis la récupération : l'écriture du `-wal` revient, et la base de l'appli porte les grilles récupérées.
- **Instrumenté, schéma 3** (`MigrationTestHelper`) : une copie d'avant les utilisateurs revient en un seul utilisateur.
- **Instrumenté, bords** : `RECOVERABLE_SCHEMAS` suit la version de la base (le prochain schéma fait rougir) ; séquence brouillée vers le haut (lecture finie en moins d'une seconde) ou en retard sur la table ; schéma 0 ou 6 ; une copie illisible par le processus n'empêche pas les autres, seule elle fait échouer ; dossier de travail vidé ; copie intacte après la récupération.
- **Non couvert** : les pannes de l'environnement (stockage plein, `CursorWindowAllocationException`, manque de mémoire) et l'`IllegalStateException` d'avant Android 9, sans moyen de les provoquer ; une base en TRUNCATE avec un journal chaud (aucune fixture). Le SQLite d'Android 8 (3.18) est mesuré sur un émulateur API 26 : mêmes résultats qu'à l'API 37, WAL en lecture seule sans `-shm` compris.
