# TODO-3 : une base perdue n'est plus prise pour un premier lancement

## Contrat et fichiers touchés

Aujourd'hui `BoardRepository.ensureHomeBoard` réinsère le vocabulaire de base dès que la table `boards` est vide, et le callback par défaut de SQLite (`SupportSQLiteOpenHelper.Callback.onCorruption`) supprime le fichier d'une base corrompue. Une base abîmée devient donc, sans un mot, une installation neuve : les grilles personnalisées sont perdues et rien ne le dit (invariant 4).

Après :

1. **Marqueur « vocabulaire amorcé »** dans le DataStore de préférences `settings` (clé commentée « pas un réglage, ne jamais l'effacer »), écrit juste APRÈS l'insertion du vocabulaire de base, ou dès qu'une base non vide est lue sans lui (installations antérieures, restauration Android). Lecture et écriture au mieux : une `IOException` est journalisée et n'empêche jamais d'ouvrir une grille (le marqueur est une alarme, pas une condition pour parler).
2. **Base vide + marqueur = perte constatée.** Écart assumé avec l'entrée du backlog (« pas de vocabulaire réinséré ») : laisser la grille vide rendrait la personne muette tant qu'un adulte n'est pas passé, et un bouton « réinsérer » au milieu de l'écran serait tapé par elle, effaçant le seul signal. Donc : le vocabulaire de base est réinséré pour qu'on puisse parler, ET une clé « perte constatée » (horodatage) est écrite. Elle reste jusqu'à acquittement explicite. Ordre : l'alerte est écrite AVANT la réinsertion (un arrêt entre les deux ne doit pas la faire oublier), le marqueur APRÈS. Une copie présente dans `corrupted-databases/` vaut aussi perte (préférences abîmées avec la base).
3. **Alerte persistante pour l'adulte** : tant que la clé existe, la barre du haut montre une icône d'avertissement (comme l'alerte de voix) qui ouvre une modale : les grilles personnalisées ont disparu, le vocabulaire de base a été remis, une copie de la base abîmée a été gardée si elle existait. Boutons « Plus tard » (principal : c'est souvent la personne elle-même qui ouvre la modale) et « J'ai compris » (secondaire, efface la clé). Le texte ne promet aucune restauration avant le MVP-4. Le MVP-4 y ajoutera « Restaurer une sauvegarde » ; le MVP-3 protégera l'acquittement derrière le contrôle parental s'il est simple à brancher.
4. **Lecture du marqueur ratée sur une base vide** : on insère (comme un premier lancement) sans alerte, avec `Log.e`. Une fausse alerte sur une installation neuve inquiéterait pour rien ; ce cas suppose deux pannes en même temps.
5. **Copie avant suppression** : une fabrique d'`SupportSQLiteOpenHelper` enveloppe le callback de Room. L'enveloppe délègue TOUTES les méthodes (`onConfigure`, `onCreate`, `onUpgrade`, `onDowngrade`, `onOpen`, `onCorruption`), garde la version, et recopie les cinq champs de la `Configuration`. Dans `onCorruption`, elle copie la base et ses `-wal`, `-shm`, `-journal` présents dans un dossier unique `files/corrupted-databases/<numéro>-<suffixe>/` (copié sous un nom provisoire puis renommé), puis laisse le comportement par défaut supprimer l'original. Un fichier absent est ignoré ; une copie qui échoue est journalisée et n'empêche pas l'ouverture (sinon plantage en boucle). Une corruption détectée en cours d'usage copie des fichiers ouverts : copie possiblement incohérente, acceptée.
6. **Corruption en cours d'usage** : quand l'accueil (pile d'une seule grille) est lu absent, le ViewModel relance `ensureHomeBoard`, qui réinsère et lève l'alerte ; sinon les dossiers resteraient morts jusqu'à la mort du processus.
7. **Copies plafonnées à 3** : la première (grilles d'avant la première perte) et les deux plus récentes, numérotées par séquence et non par l'heure (une horloge qui recule ne doit rien changer). Elles partent dans la sauvegarde Android (25 Mo au plus, au-delà elle échoue en entier). Copie fichier par fichier : un `-wal` qui échoue ne coûte pas la base principale.
8. **Construction de la base factorisée** (`buildDatabase(context, name)`) : le module Hilt et le test instrumenté passent par la même fonction, la fabrique ne peut pas être oubliée.

Fichiers :
- `data/PreferencesModule.kt` (nouveau) : `DataStore<Preferences>` singleton, fichier corrompu remplacé par des préférences vides (`Log.e`).
- `data/CorruptionCopyingOpenHelperFactory.kt` (nouveau), `data/DatabaseModule.kt`, `data/PapotonsDatabase.kt` (fonction de construction).
- `data/board/BoardRepository.kt` : marqueur, perte constatée (`observeLostBoards`, `acknowledgeLostBoards`).
- `ui/board/BoardViewModel.kt`, `BoardUiState.kt`, `BoardScreen.kt`, `BoardTopBar.kt`, `LostBoardsDialog.kt` (nouveau).
- `res/values/strings_board.xml`, `res/xml/data_extraction_rules.xml` (commentaire : base et préférences se sauvegardent ensemble).
- `docs/TODO.md` : MVP-4 reprend « proposer les copies de `corrupted-databases/` » ; MVP-3 : l'accueil ne se supprime jamais (sinon fausse alerte).
- Tests : `BoardRepositoryTest`, `BoardViewModelTest`, `FakeBoardDao` (échec d'insertion), faux DataStore en mémoire, test instrumenté de la base.

Invariants : 4 (cœur du sujet), 3 (aucune position touchée), 2 (on peut toujours parler, même après une perte), 1 (aucune dépendance nouvelle : DataStore est déjà déclaré).

## Envisagé et écarté

- Marqueur dans la base elle-même : il disparaît avec elle.
- Marqueur en fichier vide ou en `SharedPreferences` : DataStore est déjà dans la pile et servira aux réglages des MVP suivants. Pas de classe autour : deux clés et quelques appels dans le dépôt.
- Ne rien réinsérer et attendre un adulte (texte du backlog) : la personne resterait muette ; écarté, voir point 2.
- Ne plus supprimer la base corrompue : plantage à chaque ouverture.
- `renameTo` au lieu de copier quand la base est fermée (disque plein) : reporté, la base pèse quelques centaines de Ko.
- Rendre la copie accessible (export) : c'est le MVP-4, noté dans son entrée.
- La sauvegarde Google ne garde que le dernier instantané : la base réinsérée finit par écraser celle qui contenait les grilles. Un `BackupAgent` qui saute la base tant qu'une perte n'est pas acquittée est une piste, notée au MVP-4.
- Un fichier DataStore à part pour le marqueur : le marqueur revient de lui-même dès que la base est non vide ; seul un futur « réinitialiser les réglages » devrait épargner la clé d'alerte (commentée).

## Observabilité

- Perte constatée : `Log.e` dans le dépôt, et l'alerte persistante à l'écran jusqu'à acquittement (signal principal, destiné à l'adulte).
- `onCorruption` : `Log.e` avec le chemin de la base et le dossier de la copie ; échec de copie en `Log.e` avec l'exception.
- DataStore : lecture ou écriture ratée en `Log.e` ; fichier corrompu remplacé en `Log.e`.
- Acquittement : `Log.i`.

## Retries et limites

Sans objet : ni réseau ni service externe. Une lecture ratée du DataStore pendant l'observation de l'alerte est réessayée toutes les 10 s ; une écriture ratée de l'alerte la garde en mémoire pour la session.

## Plan de test

- Dépôt (JVM, vrai DataStore sur fichier temporaire) :
  - premier lancement : vocabulaire inséré une fois, marqueur posé, aucune alerte ;
  - base vide + marqueur : vocabulaire réinséré, alerte levée ; acquittée, elle disparaît ;
  - base non vide sans marqueur : rien d'inséré, marqueur posé ; vidée ensuite : alerte (installation existante pas prise pour neuve) ;
  - insertion qui lève : marqueur absent (attrape un marqueur écrit avant l'insertion) ;
  - fichier de préférences rempli d'octets aléatoires : pas de plantage, la base non vide repose le marqueur ;
  - DataStore qui lève à l'écriture : la grille s'ouvre quand même.
- ViewModel (faux DataStore en mémoire, synchrone) : alerte visible quand la perte est constatée, `onAcknowledgeLostBoards` la retire ; le test existant « rien n'est montré avant la fin de l'insertion » vérifie aussi que le marqueur est absent tant que l'insertion est retenue.
- Instrumenté (émulateur seul), via `buildDatabase` sous un nom de test :
  - en-tête écrasé : copie octet pour octet dans `corrupted-databases/`, original supprimé, base rouverte vide avec ses tables (SQLite jette un `-wal` invalide avant de signaler la corruption : la copie des fichiers compagnons est testée sur la JVM) ;
  - `observeBoard` réémet après une insertion (rougit si `onOpen` n'est pas délégué : InvalidationTracker mort).
