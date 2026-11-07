# Papotons : consignes `gate`

Lues par la tournée du gate (pc-gate, 5 h) (programme : `~/.claude/scheduled-tasks/programme.md`). Le verrou, le temps, les lois communes, le passage et la forme du compte rendu sont dans `REGLES_COMMUNES.md`, la passe en général dans `passes/gate.md` ; ici, seulement ce qui est propre à Papotons. Écrites le 2026-11-07, à l'entrée du projet dans les routines.

Avant tout, lis le `CLAUDE.md` du dépôt (stack, architecture, conventions, invariants numérotés) : il l'emporte sur toute habitude prise sur un jeu. Papotons n'est PAS un jeu : c'est une appli de Communication Améliorante et Alternative, utilisée par des enfants qui ne parlent pas. Une case qui bouge ou une grille perdue, c'est la parole de l'enfant qui casse. La mémoire du projet est versionnée dans `.claude/memoire-auto/` (index : `MEMORY.md`).

- Les tâches dues sont celles du plan : `testDebugUnitTest` (tests JVM, rapides), `lintDebug`, et `assembleDebug` dès que `res/` ou le manifeste a bougé (seul aapt valide un `strings.xml`). Rien d'autre : ni `installDebug`, ni release, ni test instrumenté (ceux-là tournent sur l'émulateur, en journée).
- Tout build passe par `bash .claude/scripts/gradle-queue.sh <tâche>` : le hook du socle Android refuse `./gradlew` en direct, et la file partage le daemon Gradle de tous les projets du poste.
- **Le schéma Room** : après la série, si un fichier neuf ou modifié apparaît sous `app/schemas/` sans qu'un commit de la nuit l'ait versionné, un commit a changé une entité sans exporter son schéma ni écrire sa migration (invariant 4). Tu ne commites pas ce schéma à sa place et tu ne pousses pas ce commit-là : tu l'annules (`git revert`), et le motif va au TODO.
- **Relevé** : trois lignes en tête de `docs/NUITS.md` (crée le fichier s'il n'existe pas, avec un titre et une phrase d'explication) : tâches lancées et durée, ce qui part, ce qui a été réparé ou annulé. Commite-les avec `routine-commit.sh "docs(nuit): relevé du gate du <date>" docs/NUITS.md` avant le push.
- Un commit annulé et son motif vont dans `docs/TODO.md`, section « Dette ouverte », avec un ID `TODO-<n>`. Ce que tu vois de travers en passant aussi.
- Si le registre de la nuit est vide alors qu'il y a des commits non poussés, ce sont des commits de Franck ou d'une session de jour : tu ne les pousses PAS à leur place, tu le dis en une ligne et tu passes au projet suivant.
