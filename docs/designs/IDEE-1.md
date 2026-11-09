# IDEE-1 : formes d'un verbe par appui long

Source : `docs/TODO.md` § Idées, IDEE-1. Choix de Franck (2026-11-07) : cinq temps (imparfait compris), la case montre la forme seule (« ai besoin »), la page s'ouvre à la place de la grille comme un dossier. Conjugaison automatique : option des Réglages, désactivée par défaut. L'appui long est livré actif sans attendre son interrupteur (MVP-9), comme dans Proloquo : son délai suit le réglage d'accessibilité Android « Délai de pression », et Retour referme la page.

Cette livraison est la **première tranche** : la page des formes. Restent dans IDEE-1, pour une tranche suivante : la conjugaison automatique et le réglage du délai d'appui long (tous deux attendent l'écran Réglages, MVP-9, en cours dans une autre session), les accords (petit, petite, petits ; « suis allée »), les verbes pronominaux.

## 1. Contrat et fichiers touchés

### Comportement

- **Appui long sur une case verbe** (catégorie `VERB`, pas un dossier, hors édition) : si le verbe est dans la table de conjugaison, la grille laisse place à la **page des formes** ; la barre de phrase reste en haut. Sinon l'appui long vaut un appui simple (la case parle). Seul un appui parti pendant le chargement d'une autre grille reste sans effet, comme pour un dossier.
- **Page des formes**, disposition fixe pour tous les verbes (invariant 3 : une forme est toujours au même endroit) : 7 lignes × 6 colonnes.
  - Ligne 0 : coin = l'infinitif (case touchable, avec l'image de la case d'origine), puis les en-têtes des temps dans l'ordre du temps qui passe : passé composé, imparfait, présent, futur proche, futur.
  - Lignes 1 à 6 : en-tête de personne (je, tu, « il, elle », nous, vous, « ils, elles »), puis les cinq formes.
  - En-têtes non touchables, sans contour, lus comme titres par TalkBack. Formes en texte seul, fond vert des verbes.
- **Toucher une forme** (ou l'infinitif) : elle se dit, entre dans la phrase avec la catégorie `VERB` et l'image de la case d'origine, et la grille revient. Comme un mot de la grille.
- **Élision** : une forme prise dans la page porte la marque « conjuguée » (`SentenceWord.isConjugated`, sauvée avec la phrase) ; « je » s'élide alors devant toute voyelle et devant h (« j'ouvre », « j'habite »). Les verbes en h de la table sont tous à h muet, un test le fige. L'infinitif du coin n'a pas la marque (« je avoir »).
- **Une locution** (« avoir besoin ») conjugue son premier mot et garde le reste : « ai besoin », « ai eu besoin », « avais besoin », « vais avoir besoin », « aurai besoin ».
- **Retour** (bouton de la barre et retour arrière) referme la page ; **Accueil** la referme et revient à l'accueil. **Toute pile changée la referme** (un collecteur de la pile, pas une liste de cas : dossier introuvable, grilles restaurées ; la restauration la ferme aussi explicitement), et entrer en édition aussi. La page survit à la rotation et au départ de l'appli, comme un dossier ouvert.
- La page compte comme « occupée » (`isBusy`) : rien ne doit l'interrompre (la mise à jour, qui s'en servait, ne se propose plus que dans les Réglages depuis le 2026-11-08).
- **Toutes les cases** passent de `Surface(onClick)` à `combinedClickable` (appui long seulement sur un verbe, hors édition) et gardent `minimumInteractiveComponentSize()` : la zone de toucher ne change pas.
- TalkBack : l'action d'appui long est annoncée (« voir les formes du verbe ») ; chaque forme est lue d'abord, puis sa personne et son temps (« ai eu besoin, je, passé composé ») : Voice Access la reconnaît ainsi.
- La page n'est pas sauvée à la mort du processus (la phrase, si) : c'est un passage, comme une modale de choix.

### Trouver le verbe d'une case

Aucune donnée nouvelle en base : le verbe se retrouve depuis ce que dit la case (`Cell.spoken`), en minuscules, sans ponctuation finale.

1. Premier mot = un infinitif de la table : c'est lui.
2. Sinon, premier mot = une forme simple (présent, imparfait, futur) d'un verbe de la table : c'est lui. Ambiguïté (« suis » : être et suivre) : le premier verbe dans l'ordre de la table gagne ; la table est rangée par fréquence, être avant suivre.
3. Le reste du libellé (« besoin ») suit chaque forme.

« veux » donne vouloir, « aime » aimer, « vais » aller, « suis » être, « ai » avoir, « manger » manger.

### Table de conjugaison

- **Générée, jamais éditée** : `tools/conjugaison/generate_conjugations.py` lit `tools/conjugaison/verbs.txt` (une liste d'infinitifs, rangée par fréquence, écrite à la main : c'est l'entrée) et écrit `app/src/main/assets/conjugations.tsv`. Formes produites par `verbecc` 2.0.3 (LGPL-3.0, gabarits issus de Verbiste), installé dans un venv à part ; le script tourne à la main, sur le poste, jamais dans l'appli. Il coupe la prédiction par apprentissage : un infinitif inconnu de verbecc arrête le script en le nommant (pas de forme devinée).
- **Format** : une ligne par verbe, séparateur tabulation : l'infinitif puis une colonne par forme, temps par temps (passé composé, imparfait, présent, futur proche, futur), personne par personne (je, tu, il, nous, vous, ils). Formes sans pronom (« allais », pas « j'allais ») ; passé composé au masculin (« suis allé », « sommes allés ») ; futur proche = présent d'aller + infinitif.
- **Liste V1** : les verbes du vocabulaire de base et des verbes courants du quotidien d'un enfant ; pas de pronominaux (« se laver ») ni de verbes impersonnels (« pleuvoir »).

### Fichiers

- `tools/conjugaison/generate_conjugations.py`, `tools/conjugaison/verbs.txt` (nouveaux)
- `app/src/main/assets/conjugations.tsv` (généré)
- `domain/Conjugation.kt` (nouveau) : `Tense`, `GrammaticalPerson`, `VerbForms`, `ConjugationTable` (lecture du texte, recherche d'un libellé). Pur, testable sur la JVM.
- `data/conjugation/Conjugations.kt` + `ConjugationModule.kt` (nouveaux) : interface `Conjugations { suspend fun table(): ConjugationTable }` et son implémentation qui lit l'asset une fois, sur `Dispatchers.IO`. Interface = couture de test (lecture d'un asset Android), comme `PictogramStore`.
- `ui/board/BoardViewModel.kt` : table préchargée au démarrage ; `onCellLongClick`, `onVerbFormClick`, fermeture de la page dans `onBack`, `onHome`, `onStartEditing` et à tout changement de la pile.
- `ui/board/BoardUiState.kt` : `verbForms: VerbFormsPage?`.
- `ui/board/CommunicationGrid.kt` : `CellButton` passe à `combinedClickable` (appui long seulement sur un verbe, hors édition) ; le dessin d'une case devient `CellFace`, partagé avec la page des formes.
- `ui/board/VerbFormsGrid.kt` (nouveau) : la page.
- `ui/board/BoardScreen.kt` : la page à la place de la grille, `BackHandler`, `canGoBack` de la barre.
- `res/values/strings_conjugation.xml` (nouveau) : temps, personnes, descriptions TalkBack.
- Tests : `domain/ConjugationTableTest.kt`, `BoardViewModelTest.kt`, un test instrumenté de l'appui long.

### Invariants

- **1** : aucune dépendance de l'appli, aucun réseau. verbecc ne sert qu'au poste.
- **2** : la table est embarquée ; tout se fait hors ligne.
- **3** : la grille ne bouge pas (la page la remplace le temps d'un choix, comme un dossier) ; la page a une disposition unique.
- **4** : aucun changement de stockage ; ni Room, ni sauvegarde `.obz`.

## 2. Envisagé et écarté

- **Lemme stocké sur la case** (colonne Room, champ de l'éditeur, export `.obz`) : reporté. La recherche par le libellé suffit pour le vocabulaire de base et les verbes courants ; la migration et l'éditeur viendront si un vrai cas d'ambiguïté gêne.
- **Moteur de règles en Kotlin** (conjuguer tout verbe, même hors table) : écarté. Le français irrégulier en ferait un chantier à part et les erreurs seraient dites à voix haute ; une table générée est vérifiable ligne à ligne.
- **Tout Verbiste embarqué** (7 000 verbes) : écarté pour la V1 (taille, et des verbes qu'aucun enfant ne dira). La liste s'allonge en ajoutant des lignes à `verbs.txt`.
- **Table en code Kotlin généré** (comme `CoreVocabulary.kt`) : écarté, 3 600 chaînes dépasseraient la taille d'une méthode JVM. Asset texte lu une fois.
- **Modale** : écartée par Franck, la page remplace la grille.
- **Repère visuel sur les cases verbes** : reporté (encombre la case ; à voir au test fermé).
- **Une page par taille de cases** (MVP-8 : grandes cases 3 × 4) : reporté. La page fixe de 7 × 6 est trop dense pour les grandes cases ; à repenser avec MVP-8.
- **Formes en lien avec le sujet de la phrase, accords, élision exacte** : tranches suivantes.
- **Imiter l'existant** : la page se branche comme un dossier (même place, même Retour) ; l'interface `Conjugations` imite `PictogramStore` + `PictogramModule`.

## 3. Observabilité

- **Asset absent ou illisible** : `IOException` attrapée dans `AssetConjugations`, `Log.e` avec la cause ; la table est vide, tout appui long parle comme un appui simple. Rien ne plante : parler reste possible. Le test JVM sur le vrai fichier (§ 5) attrape un fichier cassé avant toute livraison.
- **Ligne mal formée** (nombre de colonnes, forme vide, infinitif en double) : ignorée, `Log.e` avec son numéro ; même test.
- **Verbe introuvable** : pas une erreur (verbe hors liste) ; `Log.i` avec l'identifiant de la case et le nombre de verbes chargés (jamais le libellé : ce sont les mots de l'utilisateur) ; l'appui long parle.
- **Générateur** : un verbe inconnu de verbecc, ou une forme manquante (temps ou personne), arrête le script avec le verbe et le temps en cause, sans écrire de fichier à moitié.

## 4. Retries et limites

Sans objet : aucun réseau dans l'appli. Le générateur ne télécharge que verbecc, à l'installation du venv.

## 5. Plan de test

JVM (`ConjugationTableTest`, données asymétriques) :
- « veux » → vouloir ; forme (nous, futur) = « voudrons », (je, passé composé) = « ai voulu » : casse l'ordre des temps ou des personnes, ce test rougit.
- « avoir besoin » → (ils, imparfait) « avaient besoin », (je, futur proche) « vais avoir besoin » : attrape la perte du complément.
- « suis » → être, pas suivre : attrape une ambiguïté tranchée au hasard.
- « Manger ! » → manger : casse et ponctuation.
- libellé inconnu → `null` ; texte vide → `null`.
- ligne au mauvais nombre de colonnes ignorée, les autres lues.
- **Vrai fichier** : chaque verbe (catégorie VERB) du vocabulaire de base se retrouve dans `assets/conjugations.tsv`, et aucune ligne n'est écartée (nombre de colonnes, forme vide, infinitif en double).

`BoardViewModelTest` :
- appui long sur « ai » (verbe de la table) : la page s'ouvre sur avoir ; rien n'est dit.
- forme touchée : dite, ajoutée à la phrase avec l'image (80) et la catégorie de la case d'origine, page fermée.
- appui long sur un verbe hors table : dit et ajouté comme un appui simple.
- Retour referme la page sans dépiler la grille ; Accueil la referme et revient à l'accueil.
- en édition, l'appui long n'ouvre rien.

Instrumenté (`VerbFormsScreenTest`, `BoardScreen` sans état, émulateur) : l'appui long d'un verbe appelle l'action et TalkBack l'annonce ; ni un nom ni une case en édition n'offrent d'appui long ; la page remplace la grille, « Retour » s'affiche depuis l'accueil, une forme touchée appelle l'action. Toute la suite `connectedAndroidTest` repasse (toutes les cases ont changé de geste).

JVM, en plus : la table suit l'ordre de `verbs.txt` (une liste changée sans régénérer rougit) ; les verbes en h de la table sont à h muet ; une forme de la page est marquée conjuguée, pas son infinitif ; une restauration et une page ouverte (`isBusy`).
