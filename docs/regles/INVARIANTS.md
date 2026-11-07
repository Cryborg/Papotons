# Invariants : ce qui les garde

Les invariants sont définis dans le `CLAUDE.md` (numérotés, jamais renumérotés). Ce fichier dit, pour chacun, pourquoi il existe et ce qui l'empêche de casser sans bruit. Un invariant sans garde automatique le dit ici.

## 1. Zéro collecte, zéro coût

Pourquoi : la fiche « Sécurité des données » à « aucune donnée collectée », les règles Familles de Google et la licence ARASAAC (usage non commercial) en dépendent.

Gardes :
- `InvariantsTest` : permissions du manifeste limitées au réseau ; aucune bibliothèque de pub, de mesure d'audience, de rapport de plantage, de paiement ou de compte dans le catalogue de versions ni dans `app/build.gradle.kts` ; aucune adresse dans le code hors des hôtes d'ARASAAC.
- Revue : le segment « dépendances » de `.claude/socle.md` lance `socle-android:security-auditor`.

## 2. Hors ligne d'abord

Pourquoi : l'appli sert de voix ; elle doit parler en avion, en zone blanche, sans forfait.

Gardes :
- `VoiceSelectionTest` : seule une voix locale est retenue, jamais une voix réseau.
- `RecoveringSpeakerTest` : une voix absente ou réseau est signalée, pas tue.
- Pictos : ceux du vocabulaire de base sont embarqués (`CoreVocabularyTest` vérifie que chaque case a son image) ; un picto ajouté depuis ARASAAC est téléchargé puis gardé sur l'appareil avant d'être posé.

## 3. Une case ne change jamais de place toute seule

Pourquoi : la planification motrice. On finit par taper de mémoire ; une case qui bouge fait dire autre chose.

Gardes :
- `CoreVocabularyLayoutSnapshotTest` : l'instantané `core_vocabulary_layout.txt` ; une case publiée ne bouge plus, on ajoute.
- `GridLayoutTest` : la position vient de la ligne et de la colonne stockées, jamais du rang dans la liste.
- `BoardScreenLayoutTest` (instrumenté) : alertes, phrase, boutons de navigation, police agrandie, écriture de droite à gauche ne déplacent aucune case ; une alerte qui disparaît ne laisse pas « Tout effacer » sous le doigt.
- Base : index unique (grille, ligne, colonne).

## 4. Les données de l'utilisateur ne se perdent pas

Pourquoi : des grilles personnalisées, c'est des heures de travail d'un orthophoniste ou d'une famille.

Gardes :
- `InvariantsTest` : aucune migration destructive ; un schéma exporté pour chaque version de la base ; la sauvegarde Android n'exclut rien.
- `MigrationTest` (instrumenté) : chaque migration garde grilles et cases.
- `BoardRepositoryTest`, `DatabaseCorruptionTest`, `CorruptionCopyingOpenHelperFactoryTest` : une base perdue lève l'alerte « Grilles perdues » au lieu de passer pour un premier lancement ; une base corrompue est copiée avant sa suppression.
- `BoardViewModelTest` : la phrase et la grille ouverte survivent à la mort du processus.

## 5. Crédit ARASAAC visible

Pourquoi : condition de la licence CC BY-NC-SA.

Gardes :
- `CreditStringsTest` : le crédit nomme l'auteur, l'origine, la licence et le propriétaire, dans chaque langue de l'appli.
- Fiche Play : vérifiée à la main à chaque publication (pas de garde automatique).
