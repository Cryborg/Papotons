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
- **2026-11-06, code sous GPL-3.0**. Pourquoi : comme Cboard, personne ne peut en tirer une version fermée et payante. Les pictos ARASAAC restent sous leur propre licence (CC BY-NC-SA).
- **2026-11-06, grilles stockées en Room** (ex-D-3). Open Board Format seulement à l'import et à l'export. Pourquoi : requêtes, migrations vérifiables (schéma exporté dans `app/schemas/`), contraintes qui gardent les positions (index unique) et les dossiers (RESTRICT).
- **2026-11-07, français seulement** (ex-D-2) : l'appli, son vocabulaire et sa voix sont en français, quelle que soit la langue de l'appareil ; les ressources anglaises sont retirées. L'anglais viendra plus tard, avec une langue portée par chaque grille (la voix suivra la grille, pas l'appareil). Pourquoi : public d'abord francophone, et une grille anglaise ne se réduit pas à traduire les libellés (ordre des mots, élisions).

## Feuille de route (MVP)

- **MVP-4** Sauvegarde et restauration, import et export Open Board Format (`.obf`/`.obz`), rappel de sauvegarde. L'export embarque les images des pictos téléchargés (`files/pictograms/`) et les photos (`files/photos/`), la restauration les remet. La modale « Grilles perdues » propose la restauration, et les copies des bases corrompues (`files/corrupted-databases/`) s'exportent pour tenter de les récupérer. Un `.obz` vide ne s'importe pas (fausse alerte de perte). Restaurer remplace la base (l'accueil réinséré a le même id), acquitte l'alerte, prévient que les changements faits depuis la perte seront écrasés, et vérifie l'intégrité d'une copie (`PRAGMA integrity_check`) avant de la restaurer. La sauvegarde Google écrase la dernière bonne base dès le lendemain d'une perte : piste d'un `BackupAgent` qui saute la base tant que l'alerte n'est pas acquittée.
- **MVP-5** Politique de confidentialité hébergée gratuitement, liée depuis la fiche Play et depuis la modale « À propos » (qui porte déjà les crédits et la version) ; crédit ARASAAC sur la fiche Play. Elle dit ce qui part chez ARASAAC pendant une recherche de pictos (le mot cherché et l'adresse IP, rien d'autre), et la fiche « Sécurité des données » est revue en conséquence avant de publier une version avec la recherche.
- **MVP-6** Test fermé avec des orthophonistes et des familles (ISAAC Francophone, associations). Faire valider le vocabulaire de base avant toute publication (disposition, mots manquants : `c'est`, `on`, `moi`, `là`, `mettre`, `ouvrir`, `qui ?`) : une fois installé, il devient le plan moteur des utilisateurs. Sur tablette seulement tant que le MVP-8 n'est pas livré. Avec l'édition (MVP-3), un emplacement libre du vocabulaire de base peut être pris par une case de l'utilisateur : un mot ajouté à l'instantané après publication devra éviter ce conflit (index unique) ; de même, une grille ajoutée au vocabulaire de base ne peut plus prendre un id fixe (les dossiers créés par l'utilisateur prennent les suivants).
- **MVP-8** Nombre de cases réglable, comme dans les logiciels de référence (malvoyants, débutants, troubles moteurs) : quelques tailles prédéfinies (moins de cases plus grosses, la taille standard, plus de cases), chacune avec ses propres positions fixes ; les petites tailles gardent les mots essentiels dans le même ordre de colonnes. Le générateur `tools/arasaac/fetch_pictograms.py` produit chaque taille. Réglage protégé comme le mode édition. Indispensable avant toute diffusion sur téléphone (cases trop étroites en portrait, inutilisables en paysage).

## Idées

- **IDEE-1** Grammaire française : appui long sur un mot pour ses variantes (conjugaison selon le sujet, accords, élisions).
- **IDEE-2** Grilles évolutives : cases masquées qu'on dévoile au fur et à mesure.
- **IDEE-3** Verrouillage de l'appli (épinglage d'écran / lock task mode).
- **IDEE-4** Statistiques d'usage locales pour l'orthophoniste (jamais envoyées nulle part).
- **IDEE-5** Autres bibliothèques de pictos via Global Symbols.
- **IDEE-6** Réglages de parole : anti-rebond des appuis répétés (les mots touchés se mettent en file : une rafale se dit en entier, seul « Tout effacer » la coupe), retour à l'accueil après un mot pris dans un dossier, prononciation mot par mot désactivable, choix du moteur et de la voix.
