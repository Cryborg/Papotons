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
- **2026-11-07, une taille de cases = un jeu de grilles à part** (MVP-8, option A de `docs/designs/MVP-8.md`), en trois tailles : grandes cases 3 × 4, moyennes 5 × 6, standard 7 × 8. Pourquoi : chaque taille a un plan moteur pensé pour elle, sans migration des grilles existantes. Les mots des petites tailles restent à valider au test fermé.
- **2026-11-07, un bouton Réglages remplace le bouton « À propos »** : roue dentée en haut à droite, contrôle parental dès l'appui, l'écran Réglages réunit modifier les grilles, la taille des cases, la sauvegarde et « À propos ». Pourquoi : un seul point d'entrée pour l'adulte, rien d'ouvrable par l'enfant.
- **2026-11-07, les catégories de couleur restent fixes** (code couleur commun aux orthophonistes) ; un thème comme « Lieux » est un dossier.

## Dette ouverte

- **TODO-8** Récupérer dans l'appli les grilles d'une copie de base corrompue (`files/corrupted-databases/`) : `PRAGMA integrity_check` sur une copie recopiée à part, lecture des schémas 1 à 3, choix de la copie, puis la restauration commune (confirmation, transaction). Aujourd'hui les copies, et les images que plus aucune case ne vise, partent dans la sauvegarde `.obz` pour qu'un expert les récupère (M).
  **Prérequis** : une vraie copie corrompue sous la main, pour savoir si une base déclarée corrompue passe jamais le contrôle.

## Feuille de route (MVP)

- **MVP-6** Test fermé avec des orthophonistes et des familles (ISAAC Francophone, associations). Faire valider le vocabulaire de base avant toute publication (disposition, mots manquants : `c'est`, `on`, `moi`, `là`, `mettre`, `ouvrir`, `qui ?`) : une fois installé, il devient le plan moteur des utilisateurs. Sur tablette seulement tant que le MVP-8 n'est pas livré. Avec l'édition (MVP-3), un emplacement libre du vocabulaire de base peut être pris par une case de l'utilisateur : un mot ajouté à l'instantané après publication devra éviter ce conflit (index unique) ; de même, une grille ajoutée au vocabulaire de base ne peut plus prendre un id fixe (les dossiers créés par l'utilisateur prennent les suivants). Matériel prêt (grilles imprimables, invitation, questionnaire, déroulé) : `docs/test-ferme/`.
- **MVP-9** Écran Réglages derrière le contrôle parental, ouvert par une roue dentée qui remplace le bouton « À propos » de la barre du haut : modifier les grilles, sauvegarde, taille des cases (MVP-8), « À propos » (crédit ARASAAC, licence, confidentialité, version).
- **MVP-11** Écrans thématiques : l'adulte crée des écrans entiers (« Maison » avec maman et le vocabulaire de la maison, « École »…) et choisit dans les Réglages celui qui s'ouvre au départ, selon où se trouve l'enfant. Proposition : un écran est une grille de premier niveau, à la taille choisie (MVP-8), que les Réglages listent (créer, renommer, supprimer, choisir le départ). À concevoir après MVP-8 et MVP-9 : le retour « Accueil » ramène à l'écran de départ, la sauvegarde et le ménage des images couvrent tous les écrans.
- **MVP-8** Nombre de cases réglable, comme dans les logiciels de référence (malvoyants, débutants, troubles moteurs) : quelques tailles prédéfinies (moins de cases plus grosses, la taille standard, plus de cases), chacune avec ses propres positions fixes ; les petites tailles gardent les mots essentiels dans le même ordre de colonnes. Le générateur `tools/arasaac/fetch_pictograms.py` produit chaque taille. Réglage protégé comme le mode édition. Indispensable avant toute diffusion sur téléphone (cases trop étroites en portrait, inutilisables en paysage). Tranché : un jeu de grilles par taille, trois tailles (voir Décisions actées et `docs/designs/MVP-8.md`).

## Idées

- **IDEE-1** Grammaire française, sur le modèle de Proloquo : un appui long sur un verbe ouvre une page de ses formes, par personne (« avoir besoin » : ai besoin, as besoin, a besoin, avons besoin, avez besoin, ont besoin) et aux temps principaux (passé composé « ai eu besoin », futur proche « vais avoir besoin », futur « aurai besoin » ; l'imparfait à trancher). Toucher une forme l'ajoute à la phrase et ramène à la grille. Même geste pour les accords (petit, petite, petits) et les pluriels. À concevoir :
  - Une seule disposition pour tous les verbes (personnes en lignes, temps en colonnes) : la forme voulue est toujours au même endroit (invariant 3).
  - Tables de conjugaison générées par `tools/arasaac/` avec une bibliothèque de conjugaison (licence à vérifier), embarquées (invariant 2), jamais saisies à la main. Une locution (« avoir besoin ») conjugue son verbe de tête.
  - Lemme stocké sur la case : aujourd'hui seul le libellé l'est, et « veux » ne dit pas « vouloir ». Migration Room non destructive (invariant 4) ; pour une case de l'utilisateur, lemme deviné depuis le libellé et corrigeable dans l'éditeur.
  - Élision complète une fois les formes connues : « j'ouvre », « j'habite » (`Elision.kt` les écarte aujourd'hui).
  - Appui long risqué pour les troubles moteurs (un appui qui traîne ouvre la page) : délai réglable ou geste désactivable dans les Réglages, repère sur les cases qui ont des variantes, action TalkBack équivalente.
  - Conjugaison automatique selon le sujet déjà dans la phrase (« tu » puis « vouloir » donne « veux »). Tranché : en option dans les Réglages, désactivée par défaut, la page de formes reste toujours là.
- **IDEE-2** Grilles évolutives : cases masquées qu'on dévoile au fur et à mesure.
- **IDEE-3** Verrouillage de l'appli (épinglage d'écran / lock task mode).
- **IDEE-4** Statistiques d'usage locales pour l'orthophoniste (jamais envoyées nulle part).
- **IDEE-5** Autres bibliothèques de pictos via Global Symbols.
- **IDEE-6** Réglages de parole : anti-rebond des appuis répétés (les mots touchés se mettent en file : une rafale se dit en entier, seul « Tout effacer » la coupe), retour à l'accueil après un mot pris dans un dossier, prononciation mot par mot désactivable, choix du moteur et de la voix.
- **IDEE-7** Importer un `.obz` ou un `.obf` d'une autre appli (Cboard, CoughDrop) **comme dossier** dans les grilles actuelles, sans rien remplacer : choix de l'emplacement, ids renumérotés, images en `path`, `data:` ou adresse ARASAAC (téléchargées seulement si le réseau est là), `action` des boutons, budget de 25 Mo de la sauvegarde Android. Aujourd'hui seule une sauvegarde de Papotons se restaure.
