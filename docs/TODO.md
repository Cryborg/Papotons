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
- **2026-11-07, un bouton Réglages remplace le bouton « À propos »** : roue dentée seule dans la barre des adultes, avec le prénom de l'utilisateur ouvert et le bouton Bureau, toujours en bas, même en largeur (révisé le 2026-11-07 : en haut, elle côtoyait les boutons de l'enfant ; puis plus de rail en largeur). Contrôle dès l'appui ; l'écran Réglages réunit en sous-pages les grilles, la taille des cases, les utilisateurs, les accompagnants, la sauvegarde et « À propos » (`docs/designs/REGLAGES-UI.md`). Pourquoi : un seul point d'entrée pour l'adulte, rien d'ouvrable par l'enfant.
- **2026-11-07, sortie protégée par utilisateur** (`docs/designs/BARRE-BUREAU.md`) : barre de navigation du système masquée ; bouton Bureau dans la barre des adultes ; option « Protéger la sortie de l'appli », cochée par défaut : Bureau passe par le contrôle des adultes, et l'appli s'épingle (épinglage d'Android, avec sa confirmation), édition comprise. Seul Bureau désépingle. Pourquoi : un désépinglage verrouille la tablette protégée par un code, et seul l'épinglage empêche de sortir par le geste du système.
- **2026-11-07, les catégories de couleur restent fixes** (code couleur commun aux orthophonistes) ; un thème comme « Lieux » est un dossier.
- **2026-11-07, profils** (`docs/designs/PROFILS.md`) : chaque utilisateur (la personne qui communique) a ses propres grilles, sa taille de cases et son écran de départ ; au lancement, le dernier utilisateur se rouvre, on en change dans les Réglages. Les accompagnants ont chacun un code à 4 chiffres pour ouvrir les Réglages, tous avec les mêmes droits ; « Code oublié ? » passe par une multiplication. Sans accompagnant, l'addition d'aujourd'hui reste. Pourquoi : une tablette sert souvent à plusieurs enfants (fratrie, classe), et seuls les adultes touchent aux réglages.

## Dette ouverte

- **TODO-8** Récupérer dans l'appli les grilles d'une copie de base corrompue (`files/corrupted-databases/`) : `PRAGMA integrity_check` sur une copie recopiée à part, lecture des schémas 1 à 4, choix de la copie, puis la restauration commune (confirmation, transaction). Aujourd'hui les copies, et les images que plus aucune case ne vise, partent dans la sauvegarde `.obz` pour qu'un expert les récupère (M).
  **Prérequis** : une vraie copie corrompue sous la main, pour savoir si une base déclarée corrompue passe jamais le contrôle.

- **TODO-9** Brouillon de l'éditeur de case repris par une autre case après la mort du processus. `CellEditorDialog.kt` garde ses champs (libellé, picto, catégorie, photo, texte dit, dossier, confirmation de suppression) en `rememberSaveable(state.target)`, mais la restauration ne vérifie pas les `inputs` : une valeur sauvée et non lue survit d'une sauvegarde à l'autre. Scénario : éditeur ouvert, appli photo lancée (l'édition est gardée pendant l'attente), processus tué ; la première case ouverte ensuite reprend le brouillon de l'autre, photo comprise. Piste : `remember` sous un `key(state.target)`, comme `ProfileDialog` et `CaregiverDialog` (`docs/designs/REGLAGES-UI.md`), en gardant ce qui doit survivre au retour de l'appli photo (le ViewModel le porte déjà : `unsavedPhoto`, `chosenPictogram`). À reproduire d'abord sur l'émulateur (`adb shell am kill` pendant la prise de vue) (S).

## Feuille de route (MVP)

- **MVP-6** Test fermé avec des orthophonistes et des familles (ISAAC Francophone, associations). Faire valider le vocabulaire de base avant toute publication (disposition, mots manquants : `c'est`, `on`, `moi`, `là`, `mettre`, `ouvrir`, `qui ?`) : une fois installé, il devient le plan moteur des utilisateurs. Les trois tailles (grandes cases, moyennes, standard) sont à faire valider, surtout les mots des petites. Avec l'édition (MVP-3), un emplacement libre du vocabulaire de base peut être pris par une case de l'utilisateur : un mot ajouté à l'instantané après publication devra éviter ce conflit (index unique) ; les grilles du vocabulaire de base du premier utilisateur gardent les ids 1 à 999, celles de l'adulte et les copies des utilisateurs ajoutés partent de 1000 : un mot ajouté devra viser chaque copie par son `core_board_id`. Matériel prêt (grilles imprimables, invitation, questionnaire, déroulé) : `docs/test-ferme/`.
- **MVP-11** Écrans thématiques : l'adulte crée des écrans entiers (« Maison » avec maman et le vocabulaire de la maison, « École »…) et choisit dans les Réglages celui qui s'ouvre au départ, selon où se trouve l'enfant. Proposition : un écran est une grille de premier niveau, à la taille choisie (MVP-8), que les Réglages listent (créer, renommer, supprimer, choisir le départ). À concevoir : le retour « Accueil » ramène à l'écran de départ, la sauvegarde et le ménage des images couvrent tous les écrans.

## Idées

- **IDEE-1** Grammaire française, suite de la page des formes d'un verbe (appui long, `docs/designs/IDEE-1.md`) :
  - Conjugaison automatique selon le sujet déjà dans la phrase (« tu » puis « vouloir » donne « veux »). Tranché : en option dans les Réglages, désactivée par défaut, la page des formes reste toujours là.
  - Appui long risqué pour les troubles moteurs (un appui qui traîne ouvre la page) : délai réglable ou geste désactivable dans les Réglages, repère sur les cases qui ont des formes (à voir au test fermé).
  - Page des formes pour les grandes cases (3 × 4) : la page fixe de 7 × 6 y est trop dense.
  - Accords : petit, petite, petits ; « suis allée » (le passé composé est au masculin).
  - Verbes pronominaux (« se laver ») et impersonnels (« pleuvoir »), absents de `tools/conjugaison/verbs.txt`.
  - Locution négative (« veux pas ») : le reste du libellé suit chaque forme, ce qui donne « ai voulu pas » au lieu de « n'ai pas voulu ». Refuser la page, ou placer la négation.
  - Verbe stocké sur la case (migration Room non destructive, invariant 4), si la recherche par le libellé bute sur une vraie ambiguïté.
- **IDEE-2** Grilles évolutives : cases masquées qu'on dévoile au fur et à mesure.
- **IDEE-4** Statistiques d'usage locales pour l'orthophoniste (jamais envoyées nulle part).
- **IDEE-5** Autres bibliothèques de pictos via Global Symbols.
- **IDEE-6** Réglages de parole : anti-rebond des appuis répétés (les mots touchés se mettent en file : une rafale se dit en entier, seul « Tout effacer » la coupe), retour à l'accueil après un mot pris dans un dossier, prononciation mot par mot désactivable, choix du moteur et de la voix.
- **IDEE-7** Importer un `.obz` ou un `.obf` d'une autre appli (Cboard, CoughDrop) **comme dossier** dans les grilles actuelles, sans rien remplacer : choix de l'emplacement, ids renumérotés, images en `path`, `data:` ou adresse ARASAAC (téléchargées seulement si le réseau est là), `action` des boutons, budget de 25 Mo de la sauvegarde Android. Aujourd'hui seule une sauvegarde de Papotons se restaure.
