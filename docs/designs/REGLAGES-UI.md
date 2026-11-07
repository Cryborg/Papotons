# REGLAGES-UI : barre du bas et Réglages en sous-pages

Demande de Franck (2026-11-07) : l'écran des Réglages est « affreux », il le veut plus joli, plus pro, plus ergonomique ; la roue dentée doit quitter la barre du haut (à côté des boutons de l'enfant) pour une barre de navigation en bas, qui accueillera d'autres boutons plus tard.

Choix validés sur maquette par Franck (2026-11-07) :
- Réglages : accueil en liste groupée avec icônes et résumés, une sous-page par sujet, retour vers l'accueil ;
- barre du bas : à gauche le prénom de l'utilisateur ouvert (petit, non cliquable), à droite la roue dentée **seule** (icône sans mot).

## Contrat et fichiers touchés

**Grille.** Une barre des adultes à épaisseur fixe (`BoardAdultBar`, 48 dp : la cible minimale, pas plus), toujours composée, quel que soit l'état (chargement, grille, page des formes) : la roue dentée reste atteignable même si la grille ne se charge pas.
- **Écran en hauteur** (hauteur ≥ largeur) : barre **en bas**, sous la grille. Son fond se prolonge sous la barre de navigation du système (l'encart du bas est appliqué dans la barre, pas autour de la colonne).
- **Écran en largeur** : la même barre devient un **rail vertical à droite** de la grille. Raison chiffrée : en bas, sur un téléphone de 360 dp couché (environ 312 dp utiles), une ligne de grandes cases passerait de (312 − 104 − 32) / 3 ≈ 59 dp à (312 − 152 − 32) / 3 ≈ 43 dp, sous la cible de 48 dp, alors que « Grandes cases » promet « téléphone même couché » ; en largeur, la place est sur les côtés. C'est l'adaptation Material standard (barre en bas, rail sur le côté) de la barre demandée par Franck.
- Contenu : le prénom de l'utilisateur ouvert (icône personne, une ligne, ellipse ; en rail, l'initiale seule pour tenir dans 48 dp de large) et la roue dentée seule (même description « Réglages », même action qu'aujourd'hui). `BoardTopBar` perd la roue (`onSettingsClick` retiré). Le prénom vient de `ProfilesUiState`, passé par `BoardRoute` ; absent tant qu'il n'est pas lu.
- Aucune case ne change de ligne ni de colonne (invariant 3). L'appli n'est pas publiée : la petite perte de hauteur (portrait) ou de largeur (paysage) est sans conséquence sur un plan moteur appris. Après publication, l'épaisseur de cette barre ne bouge plus (même règle que `TOP_BAR_HEIGHT`). Exception assumée à « aucune hauteur fixe autour d'un texte » : le prénom tient sur une ligne avec ellipse, `bodyMedium` à 200 % tient dans 48 dp.

**Réglages (`SettingsDialog`, plein écran).** Fond `surfaceVariant`, sections en cartes blanches arrondies, lignes avec pastille d'icône colorée, titre, résumé, chevron. Navigation interne (`SettingsPage` : `Home`, `Profiles`, `Caregivers`, `BoardSize`) **dans `BoardViewModel`**, à côté de `isSettingsOpen` : gardée à la rotation, jamais après la mort du processus, remise à `Home` à chaque ouverture et fermeture (un `rememberSaveable` survivrait, non lu, à la mort du processus et rouvrirait la sous-page plus tard). Pour la même raison, les modales ouvertes depuis les Réglages (`isAboutOpen`, utilisateur ou accompagnant en cours de modification) et leur contenu (prénom tapé, confirmation de suppression) passent de `rememberSaveable` à `remember`, sous un `key(id)` : sinon le brouillon d'un utilisateur reviendrait dans la modale d'un autre. Compromis assumé : une rotation ferme ces modales. L'accueil garde sa position de défilement au retour d'une sous-page. En largeur, le rail longe la grille seulement (la barre du haut garde toute sa largeur), le prénom en haut, la roue en bas ; l'encart du bas reste autour de la rangée grille plus rail. La bascule se décide sur les contraintes de la page (`BoxWithConstraints`), pas sur l'orientation déclarée. Retour arrière (et Échap) : routés dans `onDismissRequest` du `Dialog` (`page != Home` : accueil, sinon fermeture), sans `BackHandler` (Échap ne passe que par `onDismissRequest`) ; une modale ouverte au-dessus prend le retour d'abord. En-tête : bouton fermer (X) à l'accueil, flèche retour en sous-page, titre en `heading()` ; chaque page porte `paneTitle` (TalkBack annonce le changement de page).
- **Accueil** : carte de l'utilisateur ouvert (initiale, prénom, « Utilisateur ouvert », « Changer » vers Utilisateurs) ; section « Pour Léo » : Modifier les grilles (action d'aujourd'hui), Taille des cases (résumé : taille actuelle) ; section « Personnes » : Utilisateurs (résumé : prénoms), Accompagnants (résumé : prénoms, ou « Aucun : un calcul protège les Réglages ») ; section « Appli » : Sauvegarde (résumé : à jour ou non, action d'aujourd'hui), À propos (résumé : version ; ouvre `AboutDialog` comme aujourd'hui).
- Résumés sur deux lignes au plus, avec ellipse (jusqu'à 100 prénoms ; « Des changements ne sont pas sauvegardés » ne tient pas sur une à police agrandie). Valeurs encore inconnues : résumé vide (jamais « Aucun » à tort tant que les accompagnants ne sont pas lus) ; section « Pour Léo » titrée « Grilles » sans prénom ; taille inconnue : « Taille non reconnue ». Le résumé de la Sauvegarde vient de `hasUnsavedChanges` (nouveau paramètre).
- **Utilisateurs** : explication ; une ligne par utilisateur (initiale décorative, prénom, « Ouvert » et coche pour l'ouvert), ligne `selectable(role = RadioButton)` dans un `selectableGroup`, toucher la ligne l'ouvre ; à droite un bouton crayon « Modifier Léo » (même `ProfileDialog`) ; « Ajouter un utilisateur » en bouton contour pleine largeur ; message de plafond et d'échec comme aujourd'hui.
- **Accompagnants** : même forme (initiale, prénom, repère « Code changé par Code oublié »), mais une seule action : toute la ligne ouvre `CaregiverDialog` (le crayon est décoratif) ; bouton d'ajout ; sans accompagnant, l'explication seule.
- **Taille des cases** : trois cartes en colonne (groupe radio, bouton radio visible : la couleur ne porte pas seule le choix) avec un aperçu dessiné de la grille (décoratif, le texte « 3 × 4 » porte l'information), le nom et la description ; la carte choisie a aussi un contour `primary` ; message d'échec comme aujourd'hui. Le titre nomme l'utilisateur.

Le contenu fonctionnel ne change pas : mêmes actions, mêmes ViewModels, mêmes modales (`ProfileDialog`, `CaregiverDialog`, `AboutDialog`, sauvegarde).

**Fichiers.** `ui/board/{BoardTopBar,BoardScreen,BoardUiState}.kt`, nouveau `ui/board/BoardAdultBar.kt` ; `ui/settings/{SettingsDialog,ProfileDialog,CaregiverDialog}.kt`, `ProfilesSection.kt` et `CaregiversSection.kt` renommés en `ProfilesPage.kt` et `CaregiversPage.kt`, nouveaux `ui/settings/{SettingsComponents,BoardSizePage,SettingsPage}.kt` ; `domain/BoardSize.kt` (dimensions de chaque accueil, pour l'aperçu) ; `ui/board/BoardViewModel.kt` (page des Réglages) ; `ui/theme/Theme.kt` (rôles manquants, sinon Material prend ses violets par défaut : `primaryContainer`, `onPrimaryContainer`, `tertiaryContainer`, `onTertiaryContainer`, la famille `surfaceContainer*` (fond des `Card`) et `outlineVariant` à 3:1 au moins sur blanc et sur le fond gris des Réglages (contour d'`OutlinedButton`). Effet voulu sur toute l'appli : les `AlertDialog` passent du violet par défaut au gris bleuté de la palette, les contours des boutons foncent un peu ; les séparateurs, décoratifs, prennent `surfaceVariant` ; aucun nouveau composant à `secondaryContainer`, qui est ici le jaune des dossiers ; le `FilterChip` sélectionné de l'éditeur de case le prenait déjà avant ce changement) ; `docs/TODO.md` (décision actée « roue en haut à droite » réécrite) ; `res/values/strings_settings.xml` ; icônes `res/drawable/ic_{edit,grid_view,group,lock,info,chevron_right,check,person}.xml` (Material Icons, Apache 2.0, comme les autres) ; `res/values/strings_settings.xml` ; tests `androidTest/.../ui/board/BoardScreenLayoutTest.kt`, `androidTest/.../ui/edit/EditEntryTest.kt`.

**Invariants.** 1, 2, 4, 5 : inchangés (À propos garde le crédit ARASAAC, même nombre d'appuis). 3 : positions inchangées, deux barres à hauteur fixe.

**Règles du projet.** Aucune chaîne en dur, aucun tiret cadratin, aucune couleur hors `ui/theme/`, `dismissOnClickOutside = false` (le `Dialog` plein écran l'a déjà), cibles de 48 dp, police agrandie (aucune hauteur fixe autour d'un texte, tout défile).

## Envisagé et écarté

- Navigation Compose (`NavHost`) pour les sous-pages : une dépendance et un graphe pour quatre pages d'une modale ; un `when` sur un enum suffit.
- Pages À propos et Sauvegarde intégrées : leurs modales existent et marchent ; les garder évite de dupliquer l'écran de sauvegarde (qui vit en mode édition).
- Icônes `material-icons-extended` : une dépendance lourde pour huit icônes ; des drawables comme les existants.
- Mot « Réglages » à côté de la roue : écarté par Franck.
- Prénom cliquable dans la barre du bas : il ouvrirait le changement d'utilisateur à l'enfant.
- Barre en bas même en largeur : grandes cases sous 48 dp sur téléphone couché (voir plus haut).
- Réduire la barre du haut (104 dp) pour compenser : autre sujet, qui toucherait la barre de la phrase.

## Observabilité

Pas de nouveau chemin d'échec : les échecs (création, suppression, taille) restent journalisés et affichés comme aujourd'hui, dans leur page.

## Plan de test

- `BoardScreenLayoutTest` :
  - en hauteur, la roue dentée est sous la grille (son haut ≥ le bas de la dernière ligne) ; en largeur, à droite de la grille ;
  - les trois tests qui vérifiaient `clear.right <= settings.left` vérifient désormais que « Tout effacer » reste dans la barre du haut, et la roue hors de la barre du haut ;
  - le test d'alerte garde les bornes des boutons de la phrase (roue retirée de la liste) ;
  - l'arrivée du prénom (`null` puis « Léo ») et un prénom long à police 200 % ne déplacent aucune case (barre d'épaisseur fixe, ellipse) ;
  - en largeur (740 × 360 dp), la roue est à droite de la grille, à côté de la dernière ligne et pas dessous (assertion de structure : la taille forcée change la densité et donc les encarts, un seuil chiffré ne serait pas fiable) ;
  - la roue est présente pendant le chargement.
- `EditEntryTest` : la taille des cases se choisit depuis sa sous-page ; le reste (édition, sauvegarde, À propos et crédit) passe toujours par l'accueil.
- Nouveau `SettingsDialogTest` (Compose pur) : une ligne de l'accueil ouvre sa sous-page ; un vrai retour système (`Espresso.pressBack()`) revient à l'accueil, puis ferme ; une modale ouverte sur une sous-page se ferme au retour sans quitter la sous-page ; le résumé des accompagnants dit « Aucun » seulement quand la liste lue est vide.
- `BoardViewModelTest` : la page revient à l'accueil à chaque ouverture des Réglages.
