# TODO-16 : une base neuve après une corruption vue en cours d'usage

Plan révisé deux fois. Première revue : la v1 (un proxy qui rouvre la même instance de Room) laissait une transaction d'un autre fil continuer sur la base neuve hors transaction, et pouvait rouvrir l'ancien fichier entre sa fermeture et sa suppression. La v2 ouvre une **instance neuve** de Room et abandonne l'ancienne. Seconde revue : ne pas fermer l'ancienne instance (sa fermeture annulerait en `CancellationException` ce qui y est en vol), ordre des drapeaux écrit, compte des remplacements lu au début du passage, relance sur « base perdue » quel que soit le type d'erreur. Revue du code : seul le premier `onCorruption` d'une instance copie et supprime (un second, concurrent ou en retard, supprimait la base neuve), une instance jamais remplacée laisse remonter l'erreur au lieu de figer l'écran, et l'instance de relève tourne sans WAL.

## Constat (vérifié sur le code et l'émulateur, 2026-11-08)

- Room 2.8 en mode compatibilité (`openHelperFactory`) n'ouvre qu'**une** connexion par instance et la garde à vie (`PassthroughConnectionPool.kt:47`, `SupportSQLiteDriver.android.kt:57`).
- Une corruption vue en cours d'usage appelle `onCorruption` avec la base ouverte : la fabrique copie, le callback par défaut ferme la base et supprime ses fichiers. Ensuite chaque requête de l'instance lève `android.database.SQLException: connection is closed` (`SupportSQLiteConnection.android.kt:34`), que les `catch (SQLiteException)` n'attrapent pas, jusqu'au relancement (`DatabaseCorruptionFixturesTest`, `boardsAfterwards = null`).
- La requête qui découvre la corruption lève `SQLiteDatabaseCorruptException` dans le flux qui l'a lancée : les quatre flux de Room collectés par les ViewModels (`observeBoard`, `observeCoreBoardId`, `observeScreensOfOwner`, `observeProfiles`) ne l'attrapent pas : plantage.
- Une instance abandonnée sans être fermée ne rafraîchit plus rien : en mode compatibilité, `notifyInvalidation` s'arrête dès que la base est fermée (`onAllowRefresh`, `InvalidationTracker.android.kt:115`). La fermer (`RoomDatabase.close`) annulerait sa portée, donc tout appel en vol par une `CancellationException` que les ViewModels relancent ou laissent passer : un écran figé.
- `BoardDao` n'est injecté que dans `BoardRepository` (`DatabaseModule.kt:20`).
- L'appli sait déjà repartir d'une base vidée (`BoardViewModel.reopenFromStart` après une restauration, `ensureHomeBoard` qui réinsère le vocabulaire et lève l'alerte « Grilles perdues »).

## Contrat et fichiers touchés

Après une corruption vue en cours d'usage :
1. La copie est faite comme aujourd'hui.
2. L'instance de Room touchée est **perdue** dès l'entrée dans `onCorruption`, puis **remplacée** une fois la copie tentée et les fichiers supprimés. Android rappelle `onCorruption` pour chaque requête qui bute sur la page abîmée, et plusieurs connexions lisent en parallèle : seul le premier appel d'une instance copie et supprime, tout appel suivant est journalisé et ignoré (sinon il supprimerait le fichier de l'instance neuve, recréé au même chemin). `isOpen` est lu avant `lost` : qui voit la base fermée voit aussi qu'elle est perdue.
   L'instance de relève tourne en journal TRUNCATE, sans WAL, jusqu'au relancement : la dernière connexion de l'instance perdue, fermée après coup, supprime le `-wal` et le `-shm` de son chemin par leur nom (fermeture SQLite d'une base en WAL), qui seraient ceux de la base neuve. L'appel suivant au dépôt passe par une instance neuve, sur une base neuve, vide ; l'ancienne est abandonnée sans être fermée. Ordre : `isLost` à l'entrée, puis copie, suppression, `isReplaced` (seulement si le fichier a bien disparu), enfin le compteur.
3. Les flux observés du dépôt suivent l'instance courante : quand la leur est perdue, leur erreur, quelle qu'elle soit, attend le remplacement (10 s au plus) et le flux reprend sur l'instance neuve. Jamais remplacée (fichiers restés là) : l'erreur remonte, comme avant le correctif, plutôt qu'un écran figé sans un mot.
4. L'écran repart de l'accueil (`reopenFromStart`, comme après une restauration) ; `ensureHomeBoard` lève l'alerte dès qu'un remplacement a eu lieu depuis son dernier passage, même si une écriture a déjà rempli la base neuve et même sans le marqueur du vocabulaire, et réinsère le vocabulaire de base. Le compte vu n'avance qu'une fois l'alerte levée. L'enfant reparle sans relancer l'appli.
5. Si l'ouverture de l'accueil (`ensureHomeBoard`, puis `isScreenOf`) échoue alors que la base est perdue depuis son début, quelle que soit l'erreur, elle attend le remplacement (10 s au plus) et se relance une fois sur l'instance neuve, depuis son accueil (la pile sauvée vise l'ancienne base), au lieu de planter ; un second échec porte les deux causes. Le collecteur des remplacements, abonné après elle au lancement, peut l'avoir manqué.
6. Une écriture qui bute sur l'instance perdue (« connection is closed », « already-closed object ») remonte en `SQLiteException`, ce qu'attendent les ViewModels ; seulement si la base est perdue depuis le début de l'écriture : hors perte, un bug remonte tel quel.
7. Le ménage des images (TODO-7) est sauté dès qu'une base a été remplacée dans le processus.

Une corruption vue par `SQLiteDatabase.open` ne change rien : Android supprime et rouvre lui-même la base dans la même instance (`db.isOpen` faux à l'entrée de `onCorruption`), qui reste bonne. Vue par une requête de Room pendant l'ouverture (`checkIdentity`), la base est ouverte : l'instance est remplacée, alors que son helper rouvre aussi le fichier neuf au bout de 500 ms ; deux instances visent alors ce même fichier, l'ancienne n'est plus appelée. Sans perte.

Fichiers :
- `data/CorruptionCopyingOpenHelperFactory.kt` : paramètre `onReplaced`, propriétés `isLost` (atomique, posée par le premier appel sur une base ouverte) et `isReplaced` ; `isReplaced` et `onReplaced` après le callback de Room, donc après la suppression, même s'il échoue, et seulement si la base et ses compagnons (`-wal`, `-shm`, `-journal`) ont disparu.
- `data/PapotonsDatabases.kt` (nouveau) : `OpenedDatabase` (le DAO et les deux drapeaux) et `PapotonsDatabases`, qui tient l'instance courante (sous verrou : l'export tourne hors du fil principal), la remplace quand elle est remplacée sur le disque, compte les remplacements (`replacements: StateFlow<Int>`), fournit `observe(query)`, `isLostSince` et `awaitReplacement`. Son constructeur prend la fonction qui ouvre une instance : couture de test, Room ne tourne pas sur la JVM ; la fabrique de production est `PapotonsDatabases(context, name)`.
- `data/PapotonsDatabase.kt` : `buildDatabase` reçoit la fabrique (par défaut celle d'aujourd'hui).
- `data/DatabaseModule.kt` : fournit `PapotonsDatabases` au lieu de `PapotonsDatabase` et `BoardDao`.
- `data/board/BoardRepository.kt` : reçoit `PapotonsDatabases` ; `boardDao` devient un accès à l'instance courante (le corps des méthodes ne change pas) ; les quatre flux passent par `observe` ; `databaseReplacements` exposé ; `ensureHomeBoard` compare le compte des remplacements lu à son début à celui lu au début du passage précédent ; `write` convertit la `SQLException` ; le ménage regarde le compte.
- `ui/board/BoardViewModel.kt` : `reopenFromStart` aussi sur `databaseReplacements` ; `reopenHome` relance une fois l'ouverture de l'accueil si la base est perdue depuis son début.
- Tests : JVM `CorruptionCopyingOpenHelperFactoryTest`, `PapotonsDatabasesTest` (nouveau), `BoardRepositoryTest`, `BoardViewModelTest`, et les constructions de `BoardRepository` dans les tests ; instrumentés `DatabaseCorruptionTest`, `DatabaseCorruptionFixturesTest` (par `PapotonsDatabases` : `boardsAfterwards` passe à 0), `BoardBackupTest`.
- Docs : `docs/TODO.md` (TODO-16 retiré), `docs/designs/TODO-8.md` (constat 6).

Invariants :
- 4 : la copie précède toute instance neuve, et l'instance neuve ne s'ouvre qu'après la suppression (drapeau posé à la fin du callback de Room) : jamais de connexion sur l'ancien fichier. L'alerte ne dépend plus d'une base encore vide. Le ménage des images (TODO-7) reste bloqué par la copie présente.
- 2 : l'enfant reparle sans relancer l'appli (vocabulaire de base réinséré hors ligne).
- 3 : rien ne bouge dans une grille existante ; le vocabulaire réinséré reprend ses positions.

## Envisagé et écarté

- **Un proxy de `SupportSQLiteDatabase` qui rouvre la même instance** (v1) : une transaction en cours sur un autre fil continuerait instruction par instruction sur la base neuve, hors transaction (écritures à moitié faites, alerte masquée par une base plus vide) ; `inTransaction` sur la base morte lève `IllegalStateException` ; une réouverture entre la fermeture et la suppression viserait l'ancien fichier. Le corriger demande un épinglage par fil des transactions : bien plus de surface que deux instances.
- **`setAutoCloseTimeout` de Room** : prototype mesuré, la fin de la transaction coupée lève une `IllegalStateException` qui remplace l'exception de corruption (`PassthroughConnectionPool.kt:147` n'attrape que `SQLiteException`) ; API expérimentale.
- **Relancer le processus** : sous l'épinglage d'écran, tuer l'appli peut désépingler, et un désépinglage verrouille une tablette protégée par un code (`BARRE-BUREAU.md`).
- **Ne plus fermer la base dans `onCorruption`** : la connexion continuerait sur un fichier supprimé, chaque écriture s'y perdrait.
- **Relancer un flux seulement sur `SQLiteDatabaseCorruptException`** : les flux voisins du même `combine` échouent autrement (connexion fermée). Le critère retenu est l'état de l'instance, pas le type d'erreur.
- **Ignorer seulement les `onCorruption` sur base fermée** : deux requêtes concurrentes entrent toutes deux base ouverte. D'où un `compareAndSet`.
- **Fermer l'instance perdue** : annulerait en `CancellationException` ce qui y est en vol (écran figé, export arrêté sans un mot), pour un suivi des invalidations qui s'arrête déjà seul sur une base fermée.
- **Une enveloppe de `SupportSQLiteDatabase` qui convertit toute erreur de l'instance perdue** : la conversion dans `write` couvre les écritures, `observe` les flux ; reporté pour le reste.

## Observabilité

- `onCorruption` : `Log.e` existant de la copie ; `Log.e` « database lost in use, replaced » une fois remplacée.
- Fabrique : `Log.e` d'un fichier resté là après la suppression (l'instance reste perdue, comme avant le correctif).
- `PapotonsDatabases` : `Log.w` à l'ouverture de l'instance neuve ; `Log.w` de chaque erreur de flux avalée, avec l'exception.
- `ensureHomeBoard` : le `Log.e` existant de la perte (« Boards lost ») ; `reopenHome` : `Log.w` de la relance.
- Côté utilisateur : l'alerte « Grilles perdues ».

## Retries et limites

Pas de réseau. Un flux ne reprend que si son instance est perdue : une erreur ordinaire (disque plein) remonte comme aujourd'hui. `ensureHomeBoard` ne se relance qu'une fois, et seulement si un remplacement a eu lieu pendant son passage. Chaque corruption sur une base neuve redonne une copie (élaguée à trois) et une instance : un stockage qui lâche en boucle se verrait aux copies et aux journaux.

Non couvert par un test : la relance de l'accueil qui part de l'accueil plutôt que de la pile sauvée, et le second échec qui porte les deux causes. Accepté : une lecture ponctuelle (hors flux, hors ouverture de l'accueil) en vol sur l'instance perdue peut échouer par une exception que son appelant n'attend pas, comme aujourd'hui. Un export lancé juste après un remplacement exporte la base réinsérée (déjà le cas après une perte vue au lancement).

## Plan de test

- **JVM, fabrique** (`CorruptionCopyingOpenHelperFactoryTest`) : base ouverte à l'entrée : `isLost` vrai avant la copie, `isReplaced` et `onReplaced` seulement après le callback de Room (rougit si posés avant la suppression), même s'il lève ; base fermée à l'entrée (corruption à l'ouverture) : ni perdue ni remplacée (rougit si on remplace une instance saine).
- **JVM, `PapotonsDatabasesTest`** (instances simulées, deux DAO distincts) : avant remplacement, le même DAO ; perdue mais pas encore remplacée : encore l'ancienne (rougit si on ouvre avant la suppression) ; après, le DAO de l'instance neuve ; `observe` : l'erreur d'une instance perdue est avalée et le flux émet celui de l'instance neuve (rougit sans reprise) ; l'erreur d'une instance saine remonte (rougit si tout est avalé) ; `isLostSince` vrai dès la perte et après le remplacement.
- **JVM, `BoardRepositoryTest`** : après un remplacement, `ensureHomeBoard` lève l'alerte même si la base neuve est déjà remplie (rougit sans le compte des remplacements) ; un remplacement au milieu d'un passage est vu par le suivant (rougit si le compte est lu à la fin).
- **JVM, `BoardViewModelTest`** : un remplacement de base ramène l'écran à l'accueil de la base neuve, ferme l'éditeur et lève l'alerte (rougit sans le collecteur) ; `ensureHomeBoard` qui échoue sur une base perdue est relancé et l'accueil s'ouvre avec l'alerte (rougit sans la relance).
- **JVM, compléments de la revue** : un second `onCorruption` (concurrent ou en retard) ne supprime pas la base neuve ; un `-wal` resté empêche le remplacement ; drapeau vu levé au moment du compteur et pendant la copie ; instance jamais remplacée : l'erreur du flux remonte au bout du délai, pas avant ; les quatre flux du dépôt passent bien sur l'instance neuve ; écriture convertie seulement sur base perdue ; alerte sans marqueur, et pas de seconde alerte après acquittement ; ménage sauté après un remplacement ; ouverture de l'accueil qui attend un remplacement pas encore fait.
- **Instrumenté** : deux lectures de la grille abîmée dans le même `combine`, l'instance de relève en `truncate`, puis la base neuve relue après fermeture et réouverture (une corruption en retard qui l'aurait supprimée se verrait). La course entre deux `onCorruption` ne s'est pas produite sur l'émulateur ; le test JVM à deux fils (barrière dans `isOpen`) la reproduit, mais ne distingue pas un `compareAndSet` d'un `set` (affaire d'entrelacement exact).
- Chaque test JVM ci-dessus a été vu rouge en cassant la ligne qu'il couvre.
- **Instrumenté, vraie base** (`DatabaseCorruptionTest`, fixture `cells-page-half-zeroed`) : par `PapotonsDatabases`, trois flux combinés comme dans `BoardViewModel` sur la grille dont les cases sont abîmées : pas de plantage, la copie existe, le flux émet `null` (base neuve), puis une grille insérée dans la base neuve est émise par le même flux (suivi des invalidations de l'instance neuve).
- **Instrumenté, constats** (`DatabaseCorruptionFixturesTest`, par `PapotonsDatabases`) : `boardsAfterwards = 0` pour les quatre pannes vues en cours d'usage ; inchangé pour les autres.
