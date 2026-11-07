# TODO-7 : images orphelines

## Contrat et fichiers touchés

Une fois par processus, au lancement, après l'ouverture de l'accueil (`ensureHomeBoard`, qui détecte une perte), les images que plus aucune case ne vise sont supprimées :

- `files/pictograms/<id>.png` dont l'id n'est le picto d'aucune case ;
- `files/photos/<nom>` qu'aucune case ne porte ;

Garde-fous (invariant 4) :

1. **Jamais tant que l'alerte « Grilles perdues » est levée** (écrite ou seulement connue du processus) : une base réinsérée ne vise plus les images des grilles perdues, qu'une restauration (MVP-4) remettrait en place.
2. **Jamais tant qu'une copie de base corrompue existe** (`files/corrupted-databases/`) : ces copies sont les grilles perdues, et leurs images doivent survivre pour qu'on puisse les récupérer. Une seule corruption suffit donc à arrêter le ménage pour de bon sur cet appareil : cas rare, une perte de place bornée (quelques Mo), contre une perte de données.
3. **Seulement les fichiers écrits avant le démarrage du processus** (date de modification antérieure) : un picto choisi ou une photo validée pendant que le ménage tourne n'est pas encore dans une case.
4. Une lecture de la base qui échoue n'efface rien.

Fichiers : `data/board/BoardDao.kt` (deux requêtes : tous les ids de pictos, toutes les photos), `data/board/BoardRepository.kt` (`sweepOrphanImages`), `data/pictogram/PictogramImages.kt` et `data/photo/CellPhotos.kt` (noms de dossiers partagés), `ui/board/BoardViewModel.kt` (appel après le premier `ensureHomeBoard`), tests, `docs/TODO.md`.

Invariants : 4 (garde-fous ci-dessus), 2 (une image visée n'est jamais supprimée : la case garde son image hors ligne), 3 (rien ne bouge).

## Révision après la revue du plan

- **Ordre : lire, puis vérifier, puis effacer.** Les références et le nombre de grilles se lisent dans une seule transaction ; base sans grille : rien n'est effacé. Les garde-fous (alerte, copies) se vérifient APRÈS la lecture, juste avant d'effacer : une corruption détectée pendant la lecture fait copier puis vider la base, que Room rouvre vide ; la copie ou l'alerte qui en résulte doit être vue.
- **Base trouvée vide à ce lancement** (`ensureHomeBoard`) : pas de ménage dans ce processus, même sans alerte (préférences illisibles ou remises à zéro avec la base, perte non retenue). Sur une vraie première installation, il n'y a rien à effacer.
- **Préférences illisibles** pendant le ménage : pas de ménage (lecture directe de `preferences.data`, jamais `observeLostBoards`, qui réessaie sans fin).
- **Copies** : le dossier `files/corrupted-databases/` existe, même vide ou illisible : pas de ménage. Contrat pour le MVP-4 : une restauration ne supprime jamais une copie.
- **Noms reconnus seulement** : `<chiffres>.png` pour les pictos, `<uuid>.jpg` pour les photos. Les temporaires ne sont pas concernés (ceux des pictos sont effacés par `loadBundled`, ceux des photos vivent dans `no_backup/`), et tout autre nom reste.
- **Date** : le dépôt retient l'heure de sa création (premier usage, avant toute édition possible) ; seuls les fichiers modifiés plus de 2 s avant sont candidats. La vraie protection reste l'ordre : aucune édition n'est possible avant l'affichage de l'accueil, et le ménage part juste après ; la date n'est qu'une défense de plus.
- **Une fois par processus** : drapeau posé par `compareAndSet` avant la première suspension (deux fenêtres, deux ViewModels).
- Requêtes vérifiées par un test instrumenté (vraie base) : case avec picto et photo, dossier avec picto, même picto sur deux cases.
- Accepté : une fois l'alerte acquittée et sans copie, les images des grilles perdues partent au lancement suivant (une sauvegarde `.obz` embarque les siennes) ; un mot de la phrase restaurée peut perdre son picto si sa case a été supprimée au processus précédent (son libellé reste, et il se dit).

## Envisagé et écarté

- Lire les références dans les copies corrompues pour garder seulement leurs images : ouvrir des bases abîmées pour un ménage, fragile ; le cas est rare.
- Supprimer l'image au moment où une case la quitte (comme les photos du MVP-7) : un picto téléchargé peut viser plusieurs cases, il faudrait compter les références à chaque écriture. Le ménage au lancement couvre aussi les restes d'un arrêt brutal.
- Un `WorkManager` périodique : une dépendance pour une tâche de quelques millisecondes au lancement.

## Observabilité

- `Log.i` du nombre d'images supprimées (aucun nom de photo : vie privée) ; `Log.w` d'une suppression qui échoue ; `Log.i` quand le ménage est sauté (alerte, copies).
- Lecture de la base ratée : `Log.e`, rien d'effacé.

## Retries et limites

Sans objet : pas de réseau. Une fois par processus (drapeau du dépôt, singleton).

## Plan de test

- JVM (`BoardRepositoryTest`, `FakeBoardDao`, dossier temporaire) : un picto et une photo visés restent, un picto et une photo non visés partent, un nom inconnu reste ; un fichier plus récent que le démarrage reste ; alerte levée (écrite, ou seulement en mémoire) : rien ne part ; copie corrompue présente : rien ne part ; base trouvée vide à ce lancement : rien ne part ; préférences illisibles : rien ne part ; base vidée pendant la lecture : rien ne part ; deuxième appel dans le même processus : rien ne se refait. Données asymétriques (deux pictos visés, un orphelin).
- ViewModel : l'ouverture de l'accueil lance le ménage (un orphelin part, le picto d'une case reste). L'ordre n'a pas besoin d'être testé là : le dépôt saute le ménage tant que `ensureHomeBoard` n'a pas abouti dans le processus.
