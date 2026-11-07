# MVP-8 : nombre de cases réglable

État : **tranché le 2026-11-07 (option A, trois tailles) et livré.** Les questions ci-dessous sont gardées pour mémoire ; ce qui a été fait est dans « Tranché et plan », corrigé par « Après la revue du plan » là où les deux diffèrent (positions des grandes cases, pas de flux de préférence, pile remplacée d'un coup). Les mots des petites tailles restent à valider au test fermé.

## Ce que dit l'entrée

Quelques tailles prédéfinies (moins de cases plus grosses, la taille standard, plus de cases), chacune avec ses positions fixes ; les petites tailles gardent les mots essentiels dans le même ordre de colonnes ; le générateur produit chaque taille ; réglage protégé comme le mode édition. Indispensable avant toute diffusion sur téléphone.

## Question 1 : une taille, c'est quoi ?

### A. Un jeu de grilles par taille (recommandé)

Chaque taille a son propre vocabulaire de base, généré par `tools/arasaac/fetch_pictograms.py` avec ses positions, et ses propres grilles en base (accueil de la taille, dossiers). Changer de taille ouvre l'accueil de l'autre jeu.

- Pour : chaque taille a un plan moteur pensé pour elle (un accueil de 3 × 4 n'est pas un 7 × 8 rogné) ; « mêmes colonnes dans le même ordre » se garantit dans le générateur (pronoms, puis verbes d'intention, puis actions…) ; aucune migration des grilles existantes : la taille standard reste la base actuelle.
- Contre : une case ajoutée par l'adulte vit dans la taille où il l'a ajoutée ; il faut une règle pour l'id de l'accueil (`HOME_BOARD_ID` = 1 vaut pour la taille standard ; les autres prennent des ids réservés, à décider avant toute publication, voir MVP-6) ; la sauvegarde embarque toutes les tailles.

### B. Une seule grille, une fenêtre par taille

La taille réduite n'affiche que les premières lignes et colonnes de chaque grille, en plus grand.

- Pour : une seule grille, les ajouts de l'adulte valent pour toutes les tailles, rien à générer de plus.
- Contre : impose de ranger la grille standard par importance depuis le coin haut gauche, ce qu'elle n'est pas (la colonne des dossiers est la dernière : elle disparaîtrait en petite taille) ; le plan moteur change quand on change de taille de toute façon.

## Question 2 : quelles tailles, quels mots ?

Proposition à faire valider :

| Taille | Accueil | Dossiers | Pour qui |
|---|---|---|---|
| Grandes cases | 3 × 4 (12) | 2 × 3 | débutants, malvoyants, troubles moteurs, téléphone en portrait |
| Moyennes | 5 × 6 (30) | 3 × 4 | téléphone couché, petite tablette |
| Standard | 7 × 8 (56, l'actuelle) | 4 × 6 | tablette |

Mots de l'accueil en 3 × 4, dans l'ordre des colonnes de la taille standard (proposition) : je, veux, manger, boire, jouer, pas, encore, fini, oui, non, toilettes, et un dossier « Nourriture ». À valider : c'est le choix le plus lourd, il devient le plan moteur des débutants.

## Question 3 : le réglage

Où (modale « À propos », derrière le contrôle parental, comme « Modifier les grilles ») et ce qu'il emporte (la phrase en cours est vidée ; la pile repart de l'accueil de la taille).

## Ce que je ferai une fois tranché

Plan complet (`socle:livrer`) : générateur multi-tailles et instantanés de positions par taille, ids réservés, réglage en DataStore, choix de l'accueil au lancement, sauvegarde et ménage des images étendus à toutes les tailles, tests de positions par taille.

## Tranché (2026-11-07) et plan

Option A, trois tailles. Écrans thématiques (MVP-11) à venir : le réglage stocke donc l'**écran de départ** (id d'une grille), et une taille n'est qu'un écran de départ prédéfini. MVP-11 ajoutera à la même liste les écrans créés par l'adulte.

### Contrat

- Trois jeux de grilles, tous insérés au premier lancement (vocabulaire de base), ids fixes et contigus :
  - standard 7 × 8 : ids 1 à 6 (accueil, Nourriture, Jeux, Personnes, Émotions, Lieux), inchangé ;
  - moyennes 5 × 6 : accueil 7, dossiers 3 × 4 (Nourriture, Jeux, Personnes, Émotions, Lieux) 8 à 12 ;
  - grandes cases 3 × 4 : accueil 13, Nourriture 2 × 3 : 14.
- Mêmes mots, même picto, même chaîne qu'en standard (une clé de mot = un picto pour toutes les tailles). Ordre des colonnes de la standard :
  - moyennes : `je tu il elle` | `veux peux aime vais` | `manger boire jouer aller` | `pas encore fini ça` | `oui non merci où ? toilettes` | `Nourriture Jeux Personnes Émotions Lieux` ; ligne 4 libre sous les quatre premières colonnes ; dossiers remplis dans l'ordre de la standard, 4 par ligne (tous tiennent en 12) ;
  - grandes cases : `je veux manger` | `boire jouer toilettes` | `pas encore fini` | `oui non Nourriture` ; Nourriture : pomme, banane, pain, gâteau, eau, lait.
- `domain/BoardSize.kt` : `enum class BoardSize(val homeBoardId: Long, rows, columns)` : `LARGE_CELLS(13)`, `MEDIUM(7)`, `STANDARD(1)` ; le générateur vérifie ces ids (comme `HOME_BOARD_ID`).
- Préférence `start_board_id` (DataStore `settings`, sauvegardée par Android) ; absente : `HOME_BOARD_ID`.
- `BoardRepository.ensureHomeBoard()` rend l'écran de départ s'il existe, sinon l'accueil standard (journalisé), sinon la plus petite grille (inchangé). `observeStartBoardId()`, `setStartBoard(id)`.
- `deleteCellAndOrphanBoards` protège les trois accueils (en plus de l'accueil standard, de la grille et de la racine ouvertes).
- `BoardViewModel.onBoardSizeChosen(size)` : écrit la préférence, puis vide la pile et rouvre l'accueil (la phrase reste). `uiState.boardSize` : la taille dont l'accueil est l'écran de départ (null pour un autre écran).
- Réglages (MVP-9) : section « Taille des cases », trois choix (nom, dimensions, pour qui), appliqué tout de suite.
- Générateur : `BOARDS` porte un thème par grille (nom de la grille : `vocabulary_board_<thème>`, partagé entre tailles), les jeux moyens et grands sont décrits par des clés de mots de la standard ; unicité des clés vérifiée par jeu ; `boards --out` produit une image par grille des trois jeux.

### Invariants

- **3** : aucune case de la standard ne bouge (instantané : ajouts seulement, ids 7 à 14 nouveaux) ; chaque taille a ses positions fixes, dans l'instantané.
- **4** : changer de taille n'efface rien ; les grilles des autres tailles restent, éditées ou non ; la sauvegarde et le ménage des images couvrent toutes les grilles (déjà le cas : ils lisent toute la base).
- Les installations d'avant ce changement (développement seulement, rien n'est publié) n'ont que la standard : le choix d'une autre taille y retombe sur l'accueil standard. Réinstaller ou vider les données.

### Envisagé et écarté

- Insérer plus tard un jeu manquant (installations anciennes) : ses ids peuvent être pris par des dossiers de l'utilisateur ; inutile avant publication.
- Ids de départ en préférence par taille plutôt que fixes : utile seulement pour insérer après coup, même raison.
- Vider la phrase au changement de taille : rien ne l'impose, l'adulte la vide d'un appui.

### Plan de test

- `CoreVocabularyTest` : chaque `BoardSize` a son accueil, aux bonnes dimensions ; chaque dossier ouvre une grille du même jeu (pas de saut d'une taille à l'autre) ; tous les pictos embarqués (existant).
- Instantané des positions : les lignes 1 à 6 inchangées, les nouvelles ajoutées (test existant).
- `BoardRepositoryTest` : départ sur l'accueil choisi ; préférence vers une grille absente : accueil standard ; supprimer un dossier ne supprime jamais un accueil de taille.
- `BoardViewModelTest` : choisir une taille écrit la préférence et affiche son accueil, pile vidée, phrase gardée ; `boardSize` suit la préférence.
- Instrumenté (émulateur) : Réglages, Grandes cases : la grille 3 × 4 s'affiche ; retour en standard.

### Après la revue du plan

- **Ids réservés** : les grilles créées par l'adulte prennent des ids à partir de 1000 (`FIRST_USER_BOARD_ID`, calculé dans la transaction de `insertFolder`) ; 1 à 999 restent au vocabulaire de base, qui pourra encore s'agrandir après le test fermé.
- **Forme des grandes cases** : 3 lignes × 4 colonnes (paysage). Calcul sur un téléphone de 412 × 915 dp, barre de 104 dp : cases d'environ 95 dp debout et 85 dp couché, contre 130 et 64 dp pour 4 × 3 : la plus équilibrée dans les deux sens. Moyennes 5 × 6 : environ 65 dp debout, 50 dp couché. À vérifier sur l'émulateur avant de figer.
- **Positions** (ligne, colonne), ligne du haut comme la standard (« je veux manger… ») :
  - grandes cases : ligne 0 `je veux manger Nourriture`, ligne 1 `pas encore boire toilettes`, ligne 2 `oui non jouer fini` ; Nourriture 2 × 3 : `pomme banane pain` / `gâteau eau lait` ;
  - moyennes, par colonne : `je tu il elle` | `veux peux aime vais` | `manger boire jouer aller` | `pas encore fini ça` | `oui non merci où ? toilettes` | `Nourriture Jeux Personnes Émotions Lieux` ; dossiers 3 × 4, ligne par ligne.
- **Écran de départ valide** : une grille qui existe et qu'aucune case n'ouvre (`countLinksTo == 0`) ; sinon accueil standard, journalisé. Préférence illisible : accueil standard, journalisé, jamais de plantage.
- **Taille affichée** déduite de la racine réellement ouverte (`DisplayedBoard.rootBoardId`), pas de la préférence : aucun flux de préférence de plus dans `uiState`.
- **Changement de taille** : un seul job (le précédent annulé), attend l'ouverture en cours, `setStartBoard` rend l'id résolu, puis la pile devient `[résolu]` sans passer par une pile vide. Écriture ratée : rien d'appliqué, message dans les Réglages. Pas de drapeau « non sauvegardé » (le réglage n'est pas dans le `.obz`).
- **Taille d'un nouveau dossier** : celle des dossiers du jeu de la racine ouverte (standard 4 × 6, moyennes 3 × 4, grandes cases 2 × 3), standard pour un autre écran.
- **Générateur** : une chaîne par clé (dédoublonnée), une clé = une seule définition (vérifié), colonnes des dossiers par jeu, nom de grille par thème, constantes uniques.
- **Tests en plus** : partition des grilles atteignables par accueil, dernière ligne libre des moyennes, dossier qui vise un accueil de taille (la garde de suppression doit rougir), dossier utilisateur à l'id 7, préférence illisible, écriture ratée, pile sauvée `[13, 14]`, ids des nouveaux dossiers ≥ 1000.
- Grilles pleines (grandes cases, dossiers des moyennes) : à faire valider au test fermé.
