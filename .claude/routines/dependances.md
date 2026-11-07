# Papotons : consignes `dependances`

Lues par la tournée cloud (23 h, le 15 du mois) (programme : `~/.claude/scheduled-tasks/programme.md`). Le verrou, le temps, les lois communes, le passage et la forme du compte rendu sont dans `REGLES_COMMUNES.md`, la passe en général dans `passes/dependances.md` ; ici, seulement ce qui est propre à Papotons. Écrites le 2026-11-07, à l'entrée du projet dans les routines.

Avant tout, lis le `CLAUDE.md` du dépôt (stack, architecture, conventions, invariants numérotés) : il l'emporte sur toute habitude prise sur un jeu. Papotons n'est PAS un jeu : c'est une appli de Communication Améliorante et Alternative, utilisée par des enfants qui ne parlent pas. Une case qui bouge ou une grille perdue, c'est la parole de l'enfant qui casse. La mémoire du projet est versionnée dans `.claude/memoire-auto/` (index : `MEMORY.md`).

1. L'inventaire : `gradle/libs.versions.toml`, `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`. Pour chaque dépendance, la version actuelle et la dernière stable (recherche web : Maven Central, Google Maven).
2. ⚠️ Kotlin, AGP et Gradle sont partagés entre TOUS les projets Android de Franck (un seul daemon Gradle, un seul daemon Kotlin) : une montée de l'un d'eux se fait dans tous les projets le même jour. Dis-le à côté de chaque ligne qui les concerne, avec les versions en vigueur dans les autres projets si elles diffèrent.
3. Invariant 1 : une dépendance (même transitive, quand une montée en tire une neuve) qui embarque de la pub, de l'analytics, du crash reporting, du paiement ou un appel réseau hors ARASAAC et Play se signale en tête, comme un défaut grave.
4. Les APIs dépréciées utilisées dans le code (fichier:ligne), et les breaking changes connus des montées proposées. Une montée de Room se lit avec ses notes de migration de schéma.
5. Les échéances Google Play des 6 prochains mois : targetSdk minimum exigé (le projet est en `targetSdk = 37`), politique « Familles » (l'appli s'adresse aussi à des enfants), fiche « Sécurité des données ». Pour chacune : date, ce que ça bloque, l'état du projet.
6. Le relevé dans `docs/DEPENDANCES.md` (fichier unique réécrit à chaque passage) : en tête « À faire avant le <date> » pour ce qui a une échéance, puis le tableau des dépendances en retard (actuelle, dernière stable, écart, risque, effort), les échéances Play, les APIs dépréciées.
7. Une échéance Play à moins de 3 mois qui n'est pas déjà dans `docs/TODO.md` y est ajoutée (« Dette ouverte », priorité haute, avec sa date).
