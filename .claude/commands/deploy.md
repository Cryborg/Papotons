---
description: Publier une version sur Google Play (notes, version, AAB, tests internes, promotion, tag)
argument-hint: "[version, ex: 1.4.0] [--ask] [--dry-run] [--production] [--internes]"
---

# Deploy

Publie le jeu sur Google Play. Le nom, le package et les chemins se lisent dans le projet : `bash .claude/scripts/deploy.sh precheck` les affiche, `.claude/deploy.conf` porte ce qui est propre au jeu (langues, promotion par défaut, étapes en plus). Les étapes propres au jeu qui ne sont pas des scripts (rapport de stabilité à lire avant, journal public des versions après, rappels ponctuels) sont dans la section déploiement du CLAUDE.md ou de `.claude/socle.md` : les lire avant de commencer.

La commande choisit la version et écrit les notes. Le script fait le reste jusqu'au tag : version montée et commitée, build dans un worktree de release, envoi en tests internes, notes relues en ligne, tag, push. Puis la commande promeut et termine les étapes du projet.

## Mode autonome (le défaut)

`/deploy` va au bout **sans rien demander** : notes et version se décident d'après les règles plus bas, s'ANNONCENT dans le fil (en clair, pas seulement dans un diff), puis la version part en tests internes et se promeut sur `PROMOTION_PAR_DEFAUT` s'il y en a une.

**Jamais la production** sans `--production` écrit par Franck dans la commande. `--internes` s'arrête aux tests internes même si le projet promeut d'habitude.

**Autonome ne veut pas dire passer outre.** Restent des STOP, où l'on rend compte au lieu de contourner :

- l'incohérence entre le dernier tag et la version du build (`INCOHÉRENCE` du précheck) ;
- `RETARD` : `origin` a des commits absents d'ici ;
- `RIEN À PUBLIER` : aucun `feat`, `fix` ni `perf` depuis le dernier tag (sauf version forcée en argument) ;
- `CLÉ ÉTRANGÈRE` : la clé de service appartient au projet GCP d'un autre jeu ;
- un échec du script, d'un test, du lint ou d'un garde-fou de build : on ne relance pas avec une option qui le désarme ;
- un rapport de stabilité au-dessus des seuils, si le projet en lit un avant de publier (souvent la version EST le correctif : le dire et demander).

Un doute sur le contenu d'une note joueur n'est pas un STOP : on prend la version la plus sobre (ne rien annoncer qu'on n'a pas vu dans le code) et on le signale au compte rendu.

`--ask` rétablit deux questions par `AskUserQuestion` : notes et version ensemble d'abord, tracks ensuite.

## Étapes

1. **Pré-vol** : `bash .claude/scripts/deploy.sh precheck`. Lire chaque ligne en capitales : ce sont les STOP ci-dessus. Du diff dans la copie de travail vient sans doute d'une session voisine : il ne part pas dans le binaire (construit dans le worktree), on n'y touche pas, on le dit au compte rendu.
2. **Version** : celle donnée en argument, sinon d'après les règles plus bas. Garder la forme du projet (`X.Y.Z`, ou `X.Y` s'il n'a jamais eu de troisième chiffre). Le `versionCode` monte de 1 tout seul.
3. **Notes** : les écrire en tête de `docs/RELEASE_NOTES.md` (ou le `NOTES_FILE` du conf), au format plus bas, une section par version, toutes les langues de `LOCALES`. Mettre à jour `docs/CHANGELOG.md` s'il existe (la section `[Non publié]` devient `[X.Y.Z] - date`, en rouvrir une vide, compléter avec les commits absents).
4. **Annoncer** dans le fil la version et les notes en français. Avec `--ask`, les soumettre et attendre.
5. **Publier**, en arrière-plan (plusieurs minutes), puis attendre la fin :

   ```bash
   bash .claude/scripts/deploy.sh release <version>
   ```

   - Les fichiers de la release que le jeu modifie en plus (une lettre aux joueurs, un écran de nouveautés...) s'ajoutent par `--fichier <chemin>` : ils entrent dans le commit de version, donc dans le binaire. Un fichier modifié mais oublié ici ne part PAS.
   - `--dry-run` s'arrête à l'AAB : aucun commit, aucun envoi, aucun tag. Le journal du site et la promotion se sautent aussi.
   - Sortie 75 : la file Gradle n'a jamais rendu la main, rien n'a été construit. Relancer plus tard.
   - Un échec s'arrête et se raconte, avec ce qui est déjà fait (commit, envoi, tag). Relancer avec la **même** version reprend sans remonter le `versionCode`.
   - « Version code already used » : un envoi de ce versionCode est déjà passé. Ne pas monter de version pour « réessayer » : regarder la Play Console, et finir à la main (tag, push) si l'envoi est bon.
   - Sans clé Play, le script construit l'AAB, tague et dit quel fichier déposer à la main. Le tag ne prouve alors pas la publication : le dire.
6. **Notes en ligne** : si le script affiche `RÉPARÉ` ou `NON vérifiées`, le dire à Franck et garder le log complet.
7. **Promotion** : sur `PROMOTION_PAR_DEFAUT` (sauf `--internes`), et en production seulement avec `--production` :

   ```bash
   bash .claude/scripts/deploy.sh promote <alpha|beta|production>
   ```

   Elle promeut la version taguée depuis `internal` sans renvoyer l'AAB.
8. **Étapes du projet après envoi** (journal public, rappels ponctuels...), décrites dans son CLAUDE.md ou `socle.md`. Ni avec `--dry-run`, ni après un échec.
9. **Compte rendu** : version et versionCode, notes FR, tracks atteints, tag poussé, notes réparées ou non vérifiées, arbitrages pris seul, diff d'une session voisine laissé en place, étapes du projet faites ou reportées.

## Règles de versioning

Le numéro dépend de ce que **le joueur voit** :

| Contenu | Version |
|---|---|
| Au moins un nouveau contenu jouable (✨) | MINOR +1, PATCH à 0 |
| Corrections, améliorations, équilibrage seuls (🔧 🐛 ⚖️) | PATCH +1 |

Un nouveau contenu jouable : ce que le joueur peut jouer ou débloquer de neuf (mode, monde, niveaux, quête, mécanique, section de l'app). **N'en est pas** une amélioration d'un flux existant : une modale de confirmation, une refonte visuelle, une animation, un nouveau retour visuel ou sonore, un gain de perf, de stabilité ou d'accessibilité, un équilibrage.

⚠️ **Un commit `feat:` ne suffit pas à faire une mineure.** Le précheck propose les deux candidates ; si les `feat` n'ajoutent rien de jouable (`feat(ui): modale de confirmation`), c'est un PATCH.

## Notes de version

Pour les **joueurs**, pas pour les développeurs :

- le bénéfice perçu, pas l'implémentation ; une ligne par sujet, regrouper ce qui va ensemble ;
- emojis : ✨ nouveau, 🔧 amélioration, 🐛 correction, ⚖️ équilibrage ;
- jamais de jargon (refactoring, ViewModel, coroutine, R8, noms de classes ou de fichiers), ni de fonction de debug ou d'admin, ni de phrase promotionnelle, ni de tiret cadratin ;
- rien d'invisible (migration, refacto, outillage, télémétrie interne) ;
- ne rien annoncer qu'on n'a pas vu dans le code ;
- une fonctionnalité introduite dans cette version : ne pas lister les corrections faites dessus, le joueur ne l'a jamais eue ;
- le tutoiement ou le vouvoiement du jeu ;
- l'anglais (et chaque langue) s'**écrit** pour son lecteur, avec les noms que le jeu affiche dans cette langue (`values-en/strings.xml`...), pas une traduction mot à mot.

| Commit | Note joueur |
|---|---|
| Fix d'une soustraction qui vidait l'inventaire | 🐛 Correction d'un bug qui pouvait vider tes ressources |
| Garde contre une course entre deux écrans | 🐛 Correction d'un bug de navigation |
| Migration du schéma de sauvegarde | (rien : invisible pour le joueur) |

**500 caractères au plus par langue**, contenu du bloc seul : le script compte et refuse avant le build, comme il refuse une langue manquante. Au-delà de 450, regrouper (« 🐛 Corrections diverses »).

```
## v1.4.0

<fr-FR>
✨ Nouveauté principale
🔧 Amélioration
🐛 Correction
</fr-FR>

<en-US>
✨ Main new feature
🔧 Improvement
🐛 Bug fix
</en-US>
```

Le titre `## v<version>` (ou `## <version>`) doit être exactement la version publiée. Le script en tire `app/src/main/play/release-notes/<langue>/default.txt` dans le worktree, jamais dans la copie partagée.

## Pourquoi le script fait ce qu'il fait

Chaque garde-fou a été payé par un des jeux. Avant de simplifier le script, relire ceci.

- **Le build se fait dans un worktree** calé sur le commit publié. La copie de travail est partagée avec d'autres sessions : du code non commité est déjà entré dans un AAB pendant les minutes de R8, et un AAB qui ne correspond à aucun commit part sur un versionCode consommé pour toujours. La fenêtre court jusqu'à la fin de l'envoi, car `publishReleaseBundle` relance `bundleRelease`.
- **Le tag pose le SHA publié**, jamais `HEAD` : la copie partagée a pu avancer pendant le build. Le push envoie ce SHA, pas la branche locale.
- **Le commit de version est restreint à ses fichiers** : ce qu'une session voisine a mis dans l'index n'y entre pas.
- **Les notes se vérifient avant le build** (langues, 500 caractères comptés en caractères UTF-8 et non en octets, tiret cadratin), et **se relisent en ligne après l'envoi** : quand le plugin envoie une liste vide, Play hérite en silence des notes de la version précédente, et un versionCode ne se renvoie pas.
- **Les secrets sont vérifiés et copiés avant le build** : la clé Play n'est vérifiée par aucune tâche Gradle, son absence ne se voyait qu'après le build.
- **Toujours `internal` d'abord, puis promotion.** Ne jamais changer `track` dans le bloc `play` pour envoyer ailleurs : « version code already used ».
- **Le track de promotion est une propriété Gradle** (`-PpromoteTrack`), plus une ligne injectée par `sed` dans `build.gradle.kts` : une interruption la laissait dans l'arbre partagé, et le `sed` devenait muet au premier reformatage du bloc, la promotion partant alors vers le mauvais track.
- **Gradle passe par la file** (`gradle-queue.sh`) quand le projet l'a : le hook qui l'impose ne voit pas les appels faits dans un script. Sortie 75 : jamais commencé, pas un échec de compilation.
- **Un secret vide dans le BuildConfig** (`BUILDCONFIG_REQUIS`) bloque : une version est déjà partie avec une connexion Google cassée pour tous.
- **Chaque jeu a son compte de service**, dans son projet GCP : en emprunter un autre marche, et c'est pourquoi ça passe inaperçu jusqu'au jour où l'autre jeu disparaît ou révoque sa clé.
- **Une clé se teste sans rien publier** : `bash .claude/scripts/deploy.sh cle`. Jamais par une tâche du plugin (`bootstrapListing` répond `PERMISSION_DENIED` quels que soient les droits, les autres publient pour de bon).
- **Les déclarations de la Play Console** (sécurité des données, publicités) ne se rappellent pas à chaque version : on n'y retourne que si la version change ce qui sort du téléphone (un SDK, une donnée, un identifiant publicitaire). Une checklist récitée à chaque fois apprend à ne plus la lire.

## Instructions d'exécution

Exécute toutes les étapes toi-même, sans déléguer à un sous-agent. Si une version est donnée en argument (`/deploy 1.4.0` ou `v1.4.0`), la prendre telle quelle.
