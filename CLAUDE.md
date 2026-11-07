# Papotons

Appli Android de Communication Améliorante et Alternative (CAA) : des grilles de pictogrammes qui parlent, pour les personnes qui ne s'expriment pas (ou pas assez) à l'oral. Pensée pour le français, 100 % gratuite, publiée sur le Play Store.

Ce fichier ne porte que ce qui change le code qu'on écrit. Le « pourquoi » vit dans `docs/regles/` et `docs/ETUDE.md`, les décisions dans `docs/TODO.md` § Décisions actées. Il est chargé à chaque session et par chaque sous-agent : chaque ligne se paie, garde-le court.

## Stack

- Kotlin 2.4.20, Jetpack Compose (Material 3), Hilt, DataStore, Room (schéma exporté dans `app/schemas/`, versionné). minSdk 26, targetSdk 37. Paquet `com.wobblebot.papotons`.
- Conventions et pièges de la stack : skill `socle-android:kotlin-compose`. Ce fichier l'emporte en cas de conflit.
- Briques de l'atelier posées : `gradle`, `appli`, `splash`, `mise-a-jour`, `version`, `file-gradle`, `scan-mecanique`.

## Commandes

Tout build passe par la file de la machine (le hook `garde-gradle` refuse `./gradlew` en direct) :

- Build : `bash .claude/scripts/gradle-queue.sh assembleDebug`
- Tests : `bash .claude/scripts/gradle-queue.sh testDebugUnitTest`
- Lint : `bash .claude/scripts/gradle-queue.sh lintDebug`
- Scan mécanique : `bash tools/audit/mechanical-scan.sh --pretty`
- Page de confidentialité (`docs/confidentialite.html`) en ligne : `python3 tools/publish_legal_pages.py`

## Architecture

- Arborescence : `domain/` (modèle pur : grille, case, placement, catégories, élision), `data/` (Room : grilles et cases, vocabulaire de base ; `profile/` : utilisateurs et migrations ; `pictogram/` : pictos embarqués), `speech/` (synthèse vocale et son rétablissement), `ui/` (écrans, `board/`, `about/`, `theme/`, `splash/`, `settings/`), `update/` (mise à jour Play depuis l'appli).
- Vocabulaire de base et pictos : générés par `tools/arasaac/fetch_pictograms.py`, jamais édités à la main. Un picto livré ne se retire jamais, un nom de `WordCategory` ne se renomme jamais, et depuis la première publication une case de l'instantané `core_vocabulary_layout.txt` ne bouge plus (on ajoute).
- Activité unique, écrans en Compose.

## Conventions

- Code et identifiants en anglais, sans abréviation (sauf acronymes usuels et `i`, `j`, `k`).
- Aucune chaîne affichée en dur, aucun nombre magique : ressources i18n et constantes nommées. Français seulement, dans `values/` (pas d'autre langue pour l'instant).
- KISS d'abord : pas d'abstraction pour deux cas, imiter l'existant plutôt que créer un mécanisme parallèle.
- Un commentaire n'existe que pour faire comprendre le code, expliquer une correction contre-intuitive, ou retenir une erreur qu'on referait. Jamais de récit, jamais de compte recopié.
- Aucun tiret cadratin dans un texte affiché.
- Toute modale : `DialogProperties(dismissOnClickOutside = false)`. Seuls ses boutons et le retour arrière la ferment.

## Invariants stricts

Numérotés, jamais renumérotés (des agents et des tests les citent par numéro). Un invariant retiré garde son numéro, marqué MORT.

1. **Zéro collecte, zéro coût** : aucun SDK de pub, d'analytics, de crash reporting ou de paiement, aucun serveur, aucun compte. Seul réseau permis : l'API publique d'ARASAAC (recherche et téléchargement de pictos) et Play (mise à jour). Sinon la fiche « Sécurité des données » et la licence d'ARASAAC (usage non commercial) tombent.
2. **Hors ligne d'abord** : parler ne dépend jamais du réseau. Tout picto posé dans une grille est stocké sur l'appareil.
3. **Une case ne change jamais de place toute seule** (planification motrice) : ajouter du vocabulaire ou agrandir la grille ne déplace aucune case existante.
4. **Les données de l'utilisateur ne se perdent pas** : aucune migration destructive, et l'export complet (`.obz`) reste toujours possible.
5. **Crédit ARASAAC visible** dans l'appli (Réglages > À propos, derrière le contrôle parental) et sur la fiche Play : auteur Sergio Palao, origine ARASAAC, licence CC BY-NC-SA, propriété du gouvernement d'Aragon.

## Git

- Conventional Commits `type(scope): description`, en français, un commit par sujet.
- Jamais d'attribution à l'IA (ni Co-Authored-By, ni « Generated with »).
- Commit et push via la skill `socle:push-own`. Jamais `stash`, `reset`, `restore`, `checkout -- <fichier>`, `clean`, `add -A`, `commit -a`, `commit --amend` : d'autres sessions travaillent peut-être sur la même copie.
- Une entrée de `docs/TODO.md` livrée est supprimée dans le commit qui la livre.

## Flux

- Plus de 4 fichiers ou un sujet délicat : skill `socle:livrer` (plan, revue du plan, code, revue).
- Clôture d'une feature : skill `socle:review-and-fix`.
- Une correction de Franck ou un piège découvert : skill `socle:lessons`.

## Carte des docs

| Quand | Lire |
|---|---|
| avant de concevoir une fonction CAA | `docs/ETUDE.md` |
| avant de rouvrir un choix | `docs/TODO.md` § Décisions actées |
