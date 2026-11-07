# MVP-3 : mode édition protégé

## Contrat et fichiers touchés

Aujourd'hui les grilles sont figées : le vocabulaire de base ne se modifie pas, rien ne s'ajoute. Après, un adulte peut, depuis l'appli, ajouter, modifier et supprimer des mots et des dossiers, et leur donner un picto ARASAAC cherché par mot-clé, téléchargé puis gardé sur l'appareil. L'enfant ne peut pas y entrer par hasard.

Trois commits, chacun livrable seul :

### A. Le mode édition (sans réseau)

1. **Entrée** : un bouton « Modifier les grilles » dans la modale « À propos » (déjà ouverte par l'icône (i) : aucune icône de plus dans la barre du haut, donc rien ne bouge). Il ouvre le **contrôle parental** : « Réservé aux adultes. Combien font 14 + 7 ? », un nombre de 12 à 19 plus un chiffre de 3 à 9 (14 réponses possibles, de 15 à 28 : un « 15 » vu par-dessus l'épaule ne suffit pas). Clavier numérique (`NumberPassword`, sans point ni signe), validation par la touche Entrée, focus à l'ouverture, question en texte (TalkBack lit « plus »). **Une mauvaise réponse referme le contrôle** et ramène à la grille : pas d'essais en rafale.
2. **En édition** : la phrase et ses boutons laissent place, dans la même barre (hauteur fixe, invariant 3), à « Modification » (court : la police agrandie ne le coupe pas ; annoncé par TalkBack) et au bouton « Terminer ». Navigation (retour, accueil) et « À propos » restent. Toucher une case ouvre l'**éditeur** au lieu de parler ou d'ouvrir le dossier ; les emplacements vides deviennent des cases « + » qui ouvrent l'éditeur d'une nouvelle case à cette position. Rien ne parle en édition. **L'édition ne survit pas à un départ** : elle se termine dès que l'appli passe en arrière-plan (`ON_STOP` : écran éteint, autre appli) et n'est jamais sauvée à la mort du processus. Tout ce qui est derrière le contrôle (édition, éditeur ouvert, recherche) vit dans le ViewModel sans `SavedStateHandle` : rien ne peut se rouvrir sans contrôle. En entrant en édition, la voix se tait (mots en file) ; la boîte de mise à jour attend la fin de l'édition comme elle attend la fin d'une phrase.
3. **Éditeur** (modale plein écran, `imePadding`) : libellé (obligatoire, une ligne, espaces superflus retirés), picto (aperçu et « Retirer » ; « Chercher un picto » arrive au commit B), catégorie (couleur, avec son nom : « Personnes », « Actions », « Descriptions », « Choses », « Social », « Questions », « Négation », « Petits mots », ou « Aucune »), et pour une nouvelle case : « Mot » ou « Dossier ». Une case existante : « Supprimer » (confirmation dont le bouton ne tombe pas sous celui qui l'a ouverte ; pour un dossier : « Le dossier et tout ce qu'il contient seront supprimés »), et pour un dossier « Ouvrir le dossier » (pour éditer son contenu). Enregistrer (désactivé pendant l'écriture) / Annuler. TalkBack : une case s'annonce « Modifier : <libellé> », un emplacement vide « Ajouter une case, ligne 2, colonne 3 ».
4. **Données** :
   - mot : une `CellEntity` à la position touchée (index unique : deux cases ne partagent jamais un emplacement) ;
   - dossier : une nouvelle grille 4 × 6 (comme les dossiers du vocabulaire de base), nommée d'après le libellé, et la case qui la vise, insérées dans une transaction ;
   - modification : libellé, picto, catégorie ; jamais la position (invariant 3) ; renommer un dossier renomme sa grille (titre lu par TalkBack) ;
   - suppression d'un dossier, dans une transaction du DAO (`@Transaction open fun`, bâtie sur des requêtes simples, donc héritée par `FakeBoardDao`) : la case d'abord ; puis, parmi les grilles atteignables depuis la grille visée (parcours avec ensemble de grilles vues : un cycle ne boucle pas), on supprime **une à une** celles que plus aucune case ne vise, jusqu'à ce qu'il n'y en ait plus. RESTRICT se vérifie ligne à ligne en SQLite : une grille n'est supprimée que quand rien ne la vise, et la cascade retire ses cases, donc les liens vers ses enfants. Une grille encore visée ailleurs (import du MVP-4) reste. Jamais supprimées : l'accueil, la grille qui porte la case, la racine de la pile ouverte (`ensureHomeBoard` peut ouvrir une autre grille que l'accueil). Une base ne se vide donc jamais par l'édition (sinon fausse alerte « Grilles perdues »).
5. Une case existante garde son picto (embarqué) ; le commit A ne sait que le retirer.

### B. La recherche ARASAAC (réseau)

6. **Pictos téléchargés** : `PictogramImages` les décode **à la demande** (`image(id)` lance le décodage de `files/pictograms/<id>.png` s'il existe et ne l'est pas encore, publie à la fin) : seuls les pictos affichés coûtent de la mémoire, le démarrage ne grossit pas. Toute publication passe par un seul point sous `Mutex`, qui fusionne (le chargement des embarqués ne remplace plus la carte). « Embarqué » se décide par la liste des assets, pas par « déjà décodé ». Sous-échantillonnage ×2, comme les embarqués.
7. Permission `INTERNET` (invariant 1 : l'API d'ARASAAC est le seul réseau permis ; `InvariantsTest` l'accepte déjà).
8. **Recherche** : `GET https://api.arasaac.org/v1/pictograms/fr/search/<mot-clé>`, le mot-clé encodé en pourcents comme un segment de chemin (espace en `%20`, jamais `+` : `pomme+de+terre` rend 2063 résultats et 1,2 Mo, `pomme%20de%20terre` 39) ; corps lu jusqu'à 2 Mo au plus (au-delà : échec). Réponse JSON (tableau d'objets `_id`, `keywords`, `sex`, `violence`) ; 404 = aucun résultat. Les résultats marqués `sex` ou `violence` sont écartés (appli pour enfants). Les 30 premiers. Vignettes : `https://static.arasaac.org/pictograms/<id>/<id>_300.png`, 1 Mo au plus chacune, décodées avec sous-échantillonnage, téléchargées 4 à la fois (sémaphore propre à chaque recherche), gardées en mémoire le temps de la recherche. Construction de l'URL et analyse du JSON en fonctions pures, testées sur la JVM avec une réponse réelle enregistrée (`org.json` réel en dépendance de test : `isReturnDefaultValues` rendrait sinon des valeurs vides).
9. **Ce qui part chez ARASAAC** : le mot cherché et l'adresse IP, rien d'autre ; User-Agent neutre (« Papotons », pas le modèle de l'appareil) ; redirections refusées (une réponse 3xx est un échec : on ne suit jamais vers un autre hôte). À reporter dans la politique de confidentialité (MVP-5). Annulation : une recherche remplacée ou abandonnée ferme ses connexions (`disconnect()`), la lecture bloquante n'attend pas son délai.
10. **Choix** : les octets de la vignette choisie sont gardés dans l'éditeur ; **à l'« Enregistrer »**, juste avant l'écriture en base, ils sont décodés (preuve que c'est une image), écrits dans un temporaire au nom unique, synchronisés (`fd.sync()`), renommés en `files/pictograms/<id>.png`, publiés dans `PictogramImages`. Un « Annuler » n'écrit rien. Un picto embarqué n'est jamais réécrit. Une fois posé, le picto ne dépend plus du réseau (invariant 2). Les temporaires restés d'un arrêt brutal sont supprimés au premier accès. Poids : environ 10 Ko par picto (25 au plus), dans le budget de 25 Mo de la sauvegarde partagé avec les copies de base et les photos du MVP-7.
11. **Écran** (modale plein écran) : champ prérempli avec le libellé, « Chercher », grille de vignettes ; états : chargement, aucun résultat, « Pas de connexion : la recherche de pictos a besoin d'internet, le reste de l'appli marche sans » avec « Réessayer ».

### C. Ce que le TODO-3 a laissé au MVP-3

Livré avec le commit A (c'est l'édition qui rend ces copies précieuses) :

12. « J'ai compris » de la modale « Grilles perdues » passe derrière le même contrôle parental.
13. Copies d'une base corrompue : la **première modification enregistrée après une copie** écrit le numéro de la dernière copie existante dans `files/corrupted-databases.edited` (HORS du dossier des copies : un nom sans numéro y compterait comme la plus ancienne). L'élagage garde la première copie, la première copie de numéro supérieur à ce marqueur (celle qui porte les grilles refaites depuis la perte), et les plus récentes, dans la limite de 3. Indépendant de l'acquittement, que l'adulte peut repousser. Limite assumée (plafond de 3) : après plusieurs pertes, seule la copie suivant la dernière modification est protégée ; une copie de grilles refaites entre deux pertes plus anciennes peut partir.

Ordre des commits : A (édition, 12 et 13), puis B (pictos téléchargés et recherche, 6 à 11).

Fichiers (estimation) : `data/board/{BoardDao,BoardRepository}.kt`, `data/pictogram/{PictogramImages,ArasaacClient}.kt`, `data/CorruptionCopyingOpenHelperFactory.kt`, `ui/board/{BoardViewModel,BoardUiState,BoardScreen,CommunicationGrid,MessageBar ou BoardTopBar,LostBoardsDialog}.kt`, `ui/about/AboutDialog.kt`, nouveaux `ui/edit/{ParentalGateDialog,CellEditorDialog,PictogramSearchDialog,PictogramSearchViewModel,CategoryNames}.kt`, `AndroidManifest.xml`, `res/values/strings_edit.xml`, tests.

Invariants : 1 (réseau vers ARASAAC seulement, aucune donnée envoyée hors du mot cherché), 2 (picto gardé sur l'appareil avant d'être posé), 3 (positions jamais modifiées ; ajout seulement dans un emplacement libre ; barre à hauteur fixe), 4 (suppression seulement sur demande explicite et confirmée ; l'accueil ne se supprime jamais ; transactions), 5 (inchangé).

## Envisagé et écarté

- Une icône d'édition dans la barre du haut : une cible de plus, à côté de la phrase, que l'enfant toucherait ; l'entrée par « À propos » ne change rien à la barre.
- Code PIN choisi par l'adulte : à oublier, à réinitialiser, à stocker ; l'addition suffit pour une zone qui ne contient rien de dangereux hors de l'appli.
- Déplacer une case, agrandir une grille : reporté (MVP-8 pour les tailles) ; ajouter dans un emplacement libre suffit au MVP.
- Texte dit différent du libellé, photo : MVP-7.
- Vignettes en cache disque : la recherche est ponctuelle ; seule l'image choisie est gardée.
- Bibliothèque HTTP (OkHttp, Ktor) ou d'images (Coil) : `HttpURLConnection` et `BitmapFactory` suffisent pour deux appels GET, sans dépendance de plus.
- Annuler une suppression : la confirmation suffit ; l'export (MVP-4) protège le reste.
- Supprimer les fichiers de pictos qui ne servent plus : quelques Ko chacun ; reporté (le MVP-4 les rassemblera à l'export).
- Délai croissant entre deux essais du contrôle : refermer à la première erreur suffit et se comprend mieux.
- Décoder au démarrage tous les pictos téléchargés : mémoire et démarrage sans borne ; à la demande.

## Observabilité

- Recherche : échec réseau (`IOException`), code HTTP inattendu (corps tronqué journalisé), JSON illisible : `Log.w` avec le code et le mot-clé, et l'état « Pas de connexion » ou « La recherche a échoué » à l'écran avec « Réessayer ».
- Vignette qui échoue : case grise, `Log.w` avec l'id ; les autres s'affichent.
- Écriture de l'image choisie qui échoue (disque plein) : `Log.e`, message dans l'éditeur, la case n'est pas enregistrée avec un picto absent.
- Écriture en base qui échoue (contrainte, disque) : `Log.e`, message dans l'éditeur, rien d'enregistré à moitié (transaction).

## Retries et limites

- Recherche : un essai, délai de 10 s (connexion et lecture) ; pas de nouvel essai automatique (l'adulte a « Réessayer ») ; requête idempotente (GET).
- Vignettes : 4 à la fois au plus (`Semaphore`), un essai chacune, 10 s ; 30 au plus par recherche. Une nouvelle recherche annule la précédente.
- ARASAAC ne publie pas de limite de débit ; un 429 est traité comme un échec, avec « Réessayer ».
- Aucun coût : API publique gratuite.

## Plan de test

- Contrôle parental (JVM) : la bonne somme passe, une autre non ; les nombres tirés restent dans 6 à 9.
- Dépôt (JVM, `FakeBoardDao` étendu) et DAO (instrumenté, vraie Room) : ajout d'un mot à sa position ; ajout d'un dossier = grille 4 × 6 + case liée ; modification sans déplacement ; suppression d'un dossier = sous-arbre entier, accueil jamais supprimé ; un emplacement déjà pris refusé (index unique).
- ViewModel : en édition, un appui ouvre l'éditeur (rien n'est dit, aucun dossier ouvert) ; hors édition, rien ne change ; « Terminer » revient au mode normal ; l'édition n'est pas restaurée après la mort du processus.
- Client ARASAAC (JVM) : analyse d'une réponse JSON réelle enregistrée (ids, mots-clés), 404 = liste vide, JSON illisible = erreur.
- `PictogramImages` : une image stockée est écrite puis publiée ; relue au démarrage suivant.
- Écran (instrumenté) : en édition, la barre garde sa hauteur et aucune case ne bouge (invariant 3) ; les emplacements vides deviennent des « + ».
- Contrôle parental (JVM) : 14 réponses possibles de 15 à 28 ; une mauvaise réponse referme.
- DAO (instrumenté, vraie Room) : 3 niveaux de dossiers dont un id enfant plus petit que l'id parent ; une grille visée deux fois, gardée ; un cycle, sans boucle ; mutation « supprimer les grilles avant la case » rouge.
- ViewModel : `ON_STOP` termine l'édition ; après la mort du processus, ni l'édition ni l'éditeur ne se rouvrent.
- Copies (JVM) : le marqueur n'est pas pris pour une copie ; après une modification, la première copie suivante survit à quatre corruptions.
- Client ARASAAC (JVM) : `pomme de terre` → `pomme%20de%20terre`, `l'eau` et `a/b` encodés ; résultats `sex`/`violence` écartés ; corps trop gros = échec.
