# Papotons : consignes `revue-code`

Lues par la tournée cloud (23 h) (programme : `~/.claude/scheduled-tasks/programme.md`). Le verrou, le temps, les lois communes, le passage et la forme du compte rendu sont dans `REGLES_COMMUNES.md`, la passe en général dans `passes/revue-code.md` ; ici, seulement ce qui est propre à Papotons. Écrites le 2026-11-07, à l'entrée du projet dans les routines.

Avant tout, lis le `CLAUDE.md` du dépôt (stack, architecture, conventions, invariants numérotés) : il l'emporte sur toute habitude prise sur un jeu. Papotons n'est PAS un jeu : c'est une appli de Communication Améliorante et Alternative, utilisée par des enfants qui ne parlent pas. Une case qui bouge ou une grille perdue, c'est la parole de l'enfant qui casse. La mémoire du projet est versionnée dans `.claude/memoire-auto/` (index : `MEMORY.md`).

Le projet est jeune et très actif (une cinquantaine de commits sa première semaine) : la revue tourne chaque jour, sur ce qui est arrivé depuis la veille.

1. La plage : `<commit noté>..HEAD` (règles communes, dernier passage). Premier passage : les 7 derniers jours.
2. Commence par le scan mécanique, qui ne demande ni Gradle ni SDK : `bash tools/audit/mechanical-scan.sh --pretty`. Ses constats dans les fichiers de la plage entrent dans la revue.
3. Invoque la skill `review-and-fix` du socle (`socle:review-and-fix`), ou lis et applique sa fiche si l'outil ne la trouve pas, sur cette plage. Personne ne répondra à une question : là où la skill dit de demander à l'utilisateur, applique les règles ci-dessous. La skill parle de builds entre deux passes : ici tu n'en lances aucun, tu fais donc UNE passe de revue et une passe de correction. Les agents du socle (`code-reviewer`, `invariants-checker`, `silent-failure-hunter`, `test-analyzer`) et ceux du socle Android (`accessibility-auditor` dès que l'UI bouge : le public a souvent un handicap moteur ou visuel) sont les bons relecteurs ; s'ils sont introuvables par leur nom, `general-purpose` qui lit leur fiche.
4. En plus de la revue générale, vérifie sur la plage, mécaniquement :
   - les **invariants numérotés** du `CLAUDE.md` (zéro collecte et seul réseau ARASAAC et Play, hors ligne d'abord, une case ne bouge jamais seule, aucune perte de données et export `.obz` complet, crédit ARASAAC) : un invariant cassé est le défaut le plus grave ;
   - toute entité Room modifiée a sa migration non destructive et son schéma exporté dans `app/schemas/` ; aucun `fallbackToDestructiveMigration` ;
   - aucune dépendance neuve de pub, d'analytics, de crash reporting, de paiement ou de réseau ;
   - aucune chaîne affichée en dur, aucun nombre magique ; tout en français dans `values/` ;
   - aucun tiret cadratin dans une chaîne affichée ou une description accessible ;
   - chaque `Dialog`/`AlertDialog` porte `DialogProperties(dismissOnClickOutside = false)` ;
   - chaque case et chaque bouton a une description accessible et une cible tactile suffisante.
- **Jamais à la main**, même pour une coquille : le vocabulaire de base et les pictos (générés par `tools/arasaac/fetch_pictograms.py`), `assets/conjugations.tsv` (généré par `tools/conjugaison/generate_conjugations.py`), les grilles de `docs/test-ferme/grilles/`. Un défaut dedans se corrige dans le générateur ou sa source (`choices.json`, `verbs.txt`), et la régénération est pour une session de jour : la nuit, ça va au TODO.
- **Jamais déplacé ni retiré** : une case de l'instantané `core_vocabulary_layout.txt` (invariant 3, testé par `CoreVocabularyLayoutSnapshotTest`), un picto livré, un nom de `WordCategory`.

CE QUI COMPTE LE PLUS : la loi 6 des règles communes, le nécessaire seulement. Ce qui la passe se CORRIGE, sans être repoussé en constat : un bug avec son scénario, un invariant cassé, une règle du CLAUDE.md enfreinte par le code de la plage, une doc que le code a rendue fausse, le test qui prouve le correctif (tests JVM dans `app/src/test`). Le code mort que la plage a créé se supprime, après vérification des appelants. Toujours la solution la plus propre, jamais de rustine (KISS : pas d'abstraction pour deux cas). Le reste (nommage, découpe, style, code plus ancien qui marche) ne se corrige pas et ne s'écrit nulle part. Un comportement clairement non voulu (une case qui change de place, une grille ou une photo perdue, une phrase mal dite) ne se pose JAMAIS en question : il se corrige, ou, trop risqué sans compilateur, il va au TODO (§ « Dette ouverte », avec un ID `TODO-<n>`) comme travail avec le correctif précis. Seul un vrai choix produit ou d'architecture devient une question, dans `docs/TODO.md` § « Décisions à trancher » (crée la section sous « Décisions actées » si elle manque), sous forme de question fermée. Ce qui est dans « Décisions actées » ne se rouvre pas.

Une entrée de `docs/TODO.md` livrée par ta correction est supprimée dans le même commit (règle du dépôt).

Un correctif renvoyé au TODO faute de compilateur, mais qui remplit les critères de `passes/chantier-todo.md` (correctif précis, sans arbitrage, prouvable par un test JVM, ni schéma Room, ni texte affiché neuf), finit par le marqueur `[chantier]` : le chantier du TODO le livrera une nuit suivante, et le gate de 5 h le prouvera ou l'annulera. Les bugs d'abord : c'est pour eux qu'il existe.
