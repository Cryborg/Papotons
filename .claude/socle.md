# Socle : réglages de Papotons

Lu par les skills et agents du plugin `socle` (review-and-fix, lessons, push-own, plan-reviewer, invariants-checker) et de `socle-android`. Les règles de code restent dans le CLAUDE.md : ce fichier ne dit que COMMENT l'outillage s'applique ici. Il n'est pas chargé à chaque session, seulement quand une skill le lit.

## Projet

- Stack : Kotlin 2.4.20, Jetpack Compose (Material 3), Hilt, DataStore, Room. Aucun Firebase.
- Langue du code : anglais. Langue des textes affichés : français source (`values/`), anglais (`values-en/`)
- Branche : `main`
- Base du diff par défaut : travail du jour sur `main` (parent du premier commit depuis minuit) et l'arbre non commité

## Gates

- toujours : `bash .claude/scripts/gradle-queue.sh compileDebugKotlin`
- toujours : `bash .claude/scripts/gradle-queue.sh testDebugUnitTest`
- toujours : `bash .claude/scripts/gradle-queue.sh lintDebug` (`MissingTranslation` en erreur, aucune baseline)
- si ressources, manifeste ou catalogue de versions : `bash .claude/scripts/gradle-queue.sh assembleDebug`
- si code instrumenté touché : `bash .claude/scripts/gradle-queue.sh connectedAndroidTest`

## Revue

- Checklist de stack : skill `socle-android:kotlin-compose`
- Checklist du projet : aucune pour l'instant
- Scan mécanique : `bash tools/audit/mechanical-scan.sh --pretty` (règles communes de l'atelier)
- Vérificateur d'invariants : `socle:invariants-checker`
- Sujets sensibles (déclenchent la boucle complète) : perte des grilles de l'utilisateur (stockage, migration, import, export, sauvegarde), toute dépendance ou appel réseau (invariant 1), position des cases (invariant 3), synthèse vocale, accessibilité

| Segment | Fichiers | Agents en plus de `socle:code-reviewer` |
|---|---|---|
| ui-compose | Composables, thème, `Modifier` | `socle-android:accessibility-auditor`, `socle-android:performance-deep` |
| données | stockage des grilles (`data/`, `app/schemas/`), import et export, sauvegarde | `socle:silent-failure-hunter` |
| dépendances | `gradle/libs.versions.toml`, `app/build.gradle.kts`, manifeste | `socle-android:security-auditor` (invariant 1 : rien qui collecte ou coûte) |
| gros changements | plus de 4 fichiers ou nouvelles abstractions | `socle-android:code-maintainability-guardian` |
| tests | `src/test/`, `src/androidTest/` | `socle:test-analyzer` |
| comments | KDoc ou commentaires ajoutés ou modifiés | `socle:comment-analyzer` |

## Faux positifs connus

- Fond `Color.Black` en dur dans `ui/splash/StudioSplashScreen.kt` : carton de l'éditeur, identique d'une appli à l'autre (brique `splash`).
- R15 du scan mécanique sur `ui/board/SpeechUnavailableDialog.kt` : ses boutons portent un texte, qui leur sert de description.

## Plan

Points que `plan-reviewer` doit attaquer en plus de sa checklist générique.

- Une nouvelle dépendance ou un appel réseau respecte-t-il l'invariant 1 (zéro collecte, zéro coût) ?
- Une modification de grille peut-elle déplacer une case existante (invariant 3) ?
- Un changement de stockage peut-il perdre des grilles (invariant 4) ?
- L'utilisateur peut-il encore parler sans réseau (invariant 2) ?

## Mémoire

- Règles normatives : `CLAUDE.md`
- Décisions actées : `docs/TODO.md` § Décisions actées
- Procédures : `docs/regles/`
- Contexte et étude : `docs/ETUDE.md`
- Bugs résolus et traces : `.claude/memory.md`
- Mémoire auto : `.claude/memoire-auto/` (lien symbolique depuis `~/.claude/projects/-home-franck-projets-Papotons/memory`)
- Dette : `docs/TODO.md`

## Backlog

- Dette, feuille de route et idées : `docs/TODO.md`, une entrée par sujet, avec un ID stable (`TODO-n`, `MVP-n`, `IDEE-n`, `D-n`)
- Signaux de production : aucun pour l'instant
- Décisions actées : `docs/TODO.md` § Décisions actées
- Plans et specs : `docs/designs/<ID>.md`
- Une entrée livrée est SUPPRIMÉE dans le commit qui la livre (pas d'archive, la leçon va en mémoire)

## Livraison

- Isolation : worktree dans `.claude/worktrees/<slug>` pour les sessions parallèles et les routines de nuit (préparation : copier `local.properties`)
- Destination : push direct sur `main`
- Feu vert avant d'envoyer : « Je pousse ? » et attendre le GO (dépôt public)
- Ne jamais : déployer, monter une version

## Git

- Langue des commits : français
- Conventional Commits, un commit par sujet, aucune attribution à l'IA
- Commit et push : commit local via `socle:push-own`, push sur GO
- Sessions parallèles sur la même copie de travail : oui
