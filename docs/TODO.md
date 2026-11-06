# TODO

Une entrée par sujet, avec un ID stable. Une entrée livrée est SUPPRIMÉE dans le commit qui la livre : pas d'archive, la leçon va en mémoire (skill `socle:lessons`). Une entrée qui dépend d'une mesure ou d'une donnée porte un bloc **Prérequis**.

## Décisions actées

Ce qui a été tranché, avec la raison et la date. Une revue ou une routine ne rouvre pas ce qui est ici.

- **2026-11-06, public** : publication sur le Play Store, pour tout le monde. Pourquoi : Proloquo2Go et TD Snap n'existent que sur iOS, et l'offre Android en français est maigre.
- **2026-11-06, gratuité totale** : rien ne coûte d'argent, ni à Franck ni aux utilisateurs. Pas de pub, pas d'achat intégré, pas de serveur, pas de service payant. Pourquoi : c'est la condition de la licence ARASAAC et l'argument face à Proloquo2Go (environ 250 €).
- **2026-11-06, pictogrammes ARASAAC** : bibliothèque de référence en France, mots-clés français, API publique gratuite. Pourquoi : connue des orthophonistes et des familles ; sa licence CC BY-NC-SA n'est possible que parce que l'appli n'est pas commerciale.
- **2026-11-06, aucune collecte** : pas de Firebase ni de Crashlytics (contrairement aux jeux du studio). Les plantages se suivent dans Android vitals de la Play Console. Pourquoi : fiche « Sécurité des données » à « aucune donnée collectée », et règles Familles de Google simplifiées.
- **2026-11-06, minSdk 26** (Android 8). Pourquoi : les vieilles tablettes d'entrée de gamme des familles et des écoles.
- **2026-11-06, carton WobbleBot Studio à chaque lancement**, comme les jeux (1,8 s).
- **2026-11-06, orientation libre** : pas de `screenOrientation` ni d'`appCategory="game"`. Pourquoi : sur tablette, la grille se sert couchée comme debout.
- **2026-11-06, sauvegarde Android complète** (rien d'exclu). Pourquoi : les grilles personnalisées sont ce qu'il ne faut jamais perdre.
- **2026-11-06, dépôt public (open source)**.

## Décisions à trancher

- **D-1 Licence du code** : GPL-3.0 (recommandée : comme Cboard, empêche un fork fermé et payant), Apache-2.0 ou MIT (plus permissives). Les pictos ARASAAC restent sous CC BY-NC-SA quoi qu'il arrive. À trancher avant de publier le dépôt.
- **D-2 Repli de langue** : `values/` est en français, donc un téléphone en allemand affiche l'appli en français. Garder ce repli (public d'abord francophone) ou poser la brique `langue` pour renvoyer vers l'anglais ?
- **D-3 Format de stockage des grilles** : Room (requêtes, migrations) ou fichiers OBF en JSON (format d'échange natif, pas de conversion). Recommandation : Room pour l'appli, OBF seulement à l'import et à l'export.

## Dette ouverte

- **TODO-1** Pas encore de `docs/regles/INVARIANTS.md` ni de tests qui gardent les invariants 1 à 5 du CLAUDE.md : à écrire avec les premières fonctions (S).
- **TODO-2** Icône provisoire (bulle blanche sur bleu) : à faire dessiner, avec une version monochrome pour les icônes à thème d'Android 13 (S).

## Feuille de route (MVP)

- **MVP-1** Grille de cases (picto + texte), dossiers, barre de message, synthèse vocale (`TextToSpeech`, moteur installé).
- **MVP-2** Vocabulaire de base en français fourni avec l'appli, positions fixes, code couleur Fitzgerald.
- **MVP-3** Mode édition protégé : ajout et modification de cases, photo ou picto ARASAAC recherché par mot-clé (téléchargé puis gardé hors ligne).
- **MVP-4** Sauvegarde et restauration, import et export Open Board Format (`.obf`/`.obz`), rappel de sauvegarde.
- **MVP-5** Écran « À propos » avec le crédit ARASAAC, politique de confidentialité hébergée gratuitement.
- **MVP-6** Test fermé avec des orthophonistes et des familles (ISAAC Francophone, associations).

## Idées

- **IDEE-1** Grammaire française : appui long sur un mot pour ses variantes (conjugaison selon le sujet, accords, élisions).
- **IDEE-2** Grilles évolutives : cases masquées qu'on dévoile au fur et à mesure.
- **IDEE-3** Verrouillage de l'appli (épinglage d'écran / lock task mode).
- **IDEE-4** Statistiques d'usage locales pour l'orthophoniste (jamais envoyées nulle part).
- **IDEE-5** Autres bibliothèques de pictos via Global Symbols.
