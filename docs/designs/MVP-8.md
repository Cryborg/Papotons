# MVP-8 : nombre de cases réglable (décision à prendre)

État : **à trancher par Franck**, idéalement avec les orthophonistes du test fermé (MVP-6). Rien n'est codé : les deux choix ci-dessous fixent le plan moteur des utilisateurs (invariant 3) et le sort de leurs grilles personnalisées (invariant 4), ce qui ne se décide pas dans une session de code.

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
