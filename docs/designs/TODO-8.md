# TODO-8 : récupérer les grilles d'une copie de base corrompue

Le plan n'est pas écrit. Ce document porte d'abord ce qu'on sait des copies corrompues, mesuré le 2026-11-07 : c'était le prérequis, et la réponse change le plan prévu (`PRAGMA integrity_check` comme contrôle d'entrée).

## Prérequis levé : de vraies copies corrompues

- **Fixtures** : `app/src/androidTest/assets/corrupted-databases/`, fabriquées par `tools/corruption/make_corrupted_fixtures.py` (déterministe, jamais éditées à la main). Une base saine au schéma 5, que Room ouvre comme celle de l'appli (identity hash compris) : 2 utilisateurs, 7 grilles (ids 1 à 4 et 1000 à 1002), 224 cases dont une sur cinq avec photo et une sur trois avec une phrase à elle ; 14 pages de 4 Kio, table `cells` sur trois feuilles. Puis six copies abîmées chacune d'une seule façon.
- **Test** : `DatabaseCorruptionFixturesTest` (instrumenté). Chaque base est posée à la place de la base de l'appli et ouverte par `buildDatabase`, donc par `CorruptionCopyingOpenHelperFactory`. On note ce que rend la première requête de l'appli (`countBoards`), si une copie existe alors, ce que rend la lecture complète de l'export (`snapshot`), puis, sur la copie (ou la base restée en place) recopiée à part et ouverte en lecture seule avec un gestionnaire d'erreurs muet : `PRAGMA integrity_check`, et les lignes relues une à une par leur id, comparées à la base saine.
- **Où** : émulateur API 37, SQLite 3.50.6 du système. Le SQLite d'Android 8 (minSdk) est bien plus ancien : messages et moments de détection peuvent différer, non mesuré (pas d'AVD API 26 sur le poste).

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
6. **Vue en cours d'usage, la corruption laisse l'instance de Room morte.** La fabrique copie bien les trois fichiers (base, `-wal`, `-shm`), puis le callback par défaut ferme la base et la supprime ; ensuite chaque requête sur la même instance lève `android.database.SQLException: connection is closed`, et Room ne rouvre pas une base vide dans le processus. Le design du TODO-7 supposait l'inverse (« que Room rouvre vide »). Suite : TODO-16.

## Ce qui change pour le plan

- **Plus d'`integrity_check` comme contrôle.** Au mieux un indice affiché, et encore : il lève plus souvent qu'il ne répond.
- **Une récupération ligne par ligne** : copie recopiée à part, ouverte en lecture seule avec un `DatabaseErrorHandler` muet (le gestionnaire par défaut supprimerait la copie), puis chaque table relue par id. Les ids vont de 1 au dernier donné, que `sqlite_sequence` retient (les trois tables sont en `AUTOINCREMENT`) ; si cette page-là est abîmée, un parcours `SELECT *` jusqu'à la première erreur.
- **Chaque ligne relue est validée avant d'entrer** : case dont la grille est retrouvée, position dans la taille de la grille et pas déjà prise, catégorie connue (sinon sans catégorie), picto présent sur l'appareil (sinon sans picto), photo présente (sinon sans photo), libellé non vide et sans caractère de remplacement ni de contrôle. Une case qui échoue reste dehors : son emplacement reste vide, aucune case ne glisse à sa place (invariant 3).
- **L'adulte voit ce qui revient avant de confirmer** : « 7 grilles, 180 cases sur 224 », avec les grilles à trous. Une valeur fausse mais plausible ne se détecte pas : la récupération est un « mieux que rien » à relire, pas une restauration à l'identique. La confirmation et la transaction restent celles de la restauration commune (MVP-4).
- **Hors champ au départ** : rebâtir des grilles perdues dont les cases survivent (nom et taille inconnus) ; réparer l'en-tête ; lire page par page. Le `.obz` garde la copie pour un expert.
- **À mesurer avant de coder** : les mêmes fixtures sur un émulateur API 26, si le SQLite d'Android 8 lit autrement.
