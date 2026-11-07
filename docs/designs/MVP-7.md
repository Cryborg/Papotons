# MVP-7 : cartes personnalisées (photo, ce que dit la case)

## Contrat et fichiers touchés

En édition, une case peut porter une **photo** (prise sur le moment ou choisie dans la galerie) au lieu d'un picto, et dire **autre chose que son libellé** (« Mamie » affiché, « Mamie Jeanne » dit). Les photos sont souvent celles de proches : elles ne quittent jamais l'appareil (invariant 1) et sont gardées dans l'appli, jamais visées par leur adresse d'origine (une photo effacée de la galerie ne doit pas disparaître de la grille).

1. **Base, version 3** (migration automatique 2 → 3, ajout seulement, invariant 4) : `cells.photo_file` (nom du fichier dans `files/photos/`, `null` sans photo) et `cells.spoken_text` (`null` : la case dit son libellé). Schéma exporté. Valeur par défaut `null` dans `CellEntity` (le vocabulaire de base, généré, n'a ni l'un ni l'autre).
2. **Domaine** : `Picture` (scellé : `Pictogram(id)` ou `Photo(fileName)`). Une case expose `picture` (la photo l'emporte sur le picto) et `spokenText`. La phrase porte des `Picture` au lieu d'ids de pictos (`SentenceWord.picture`, `SentenceToken.pictures`). `SavedWord` gagne la photo (champ ajouté : une sérialisation Java relit un état ancien avec `null`).
3. **Parler** : une case dit `spokenText ?: label`, et c'est ce texte qui entre dans la phrase.
4. **Photos** (`data/photo/CellPhotos`) :
   - import depuis une `Uri` (appareil photo ou sélecteur) : dimensions lues d'abord, sous-échantillonnage, orientation EXIF appliquée (`androidx.exifinterface`, Android 8 et 9 ne la gèrent pas au décodage), recadrage carré au centre, réduction à 480 px, JPEG qualité 80 (environ 40 Ko : 25 Mo de sauvegarde Android tiennent environ 500 photos), écrit dans `files/photos/.pending-<uuid>.jpg` (synchronisé) ;
   - l'« Enregistrer » renomme le temporaire en `<uuid>.jpg` (dossier synchronisé) juste avant l'écriture en base ; « Annuler », « Terminer » ou un nouveau choix suppriment le temporaire ; les temporaires restants sont effacés au démarrage ;
   - affichage : décodage à la demande comme les pictos téléchargés (`ensureLoaded`), sous-échantillonné.
5. **Prendre et choisir** : `ActivityResultContracts.TakePicture` vers un fichier du cache exposé par un `FileProvider` (aucune permission CAMERA : le manifeste n'en déclare pas), et `PickVisualMedia` (images seulement, sans permission de stockage, disponible dès Android 8 via les services Play). Ces deux écrans font passer l'appli par `ON_STOP` : le ViewModel sait qu'un choix de photo est en cours et ne termine pas l'édition pour ça. Si Android tue l'appli pendant la prise de vue (fréquent sur tablette modeste), l'édition est perdue (jamais sauvée, par principe) et la photo ignorée.
6. **Éditeur** : section « Image » : « Prendre une photo », « Choisir une image », « Chercher un pictogramme », « Retirer » ; aperçu de la photo. Champ « Ce que dit la case (si différent) », facultatif. Une photo retirée ou remplacée d'une case existante reste sur l'appareil jusqu'au TODO-7 (orphelins), étendu aux photos.

Fichiers : `data/board/{CellEntity,BoardDao,BoardRepository,BoardWithCells}.kt`, `data/PapotonsDatabase.kt`, `app/schemas/…/3.json`, `domain/{Board,Elision}.kt`, `data/photo/{CellPhotos,PhotoModule}.kt` (nouveaux), `ui/board/{BoardViewModel,CellEditorState,BoardScreen,CommunicationGrid,MessageBar,SavedWord,LocalPictogramImages}.kt`, `ui/edit/CellEditorDialog.kt`, `AndroidManifest.xml` (FileProvider), `res/xml/file_paths.xml`, `gradle/libs.versions.toml` et `app/build.gradle.kts` (exifinterface), `res/values/strings_edit.xml`, `PapotonsApplication.kt` (ménage), tests, `docs/TODO.md`.

Invariants : 1 (aucune photo ne sort ; ni réseau ni permission nouvelle), 2 (photo copiée dans l'appli, lue sans réseau), 3 (positions inchangées), 4 (migration additive testée ; sauvegarde incluse ; export au MVP-4).

## Révision après la revue du plan

- **Aucune valeur par défaut sur `CellContent`** (le chemin d'écriture de l'édition et du futur import) : oublier la photo ou le texte dit ne compile pas. `CellEntity` garde `null` par défaut pour le vocabulaire de base généré, qui n'écrit jamais ces colonnes. Test : changer le seul libellé d'une case photo garde sa photo et son texte dit.
- **Photo en attente dans `no_backup/photos-pending/`** (hors sauvegarde, même partition que `files/`, sans le groupe « cache » du système) ; la valider la déplace dans `files/photos/<uuid>.jpg` (même partition : renommage), dossier synchronisé. La validation est idempotente (fichier final déjà là = succès) : un enregistrement qui échoue en base garde le fichier validé dans l'éditeur, un nouvel essai le réutilise ; « Annuler », « Terminer », « Supprimer », « Ouvrir le dossier », un autre choix ou « Retirer » suppriment le fichier qui n'a été écrit dans aucune case.
- **Une photo n'appartient qu'à une case** (nom unique) : dès que la base a enregistré une photo remplacée, retirée, ou une case supprimée (y compris les cases des grilles d'un dossier supprimé, que le DAO rend), l'ancien fichier est effacé. Vie privée (une photo retirée est vraiment retirée) et budget de sauvegarde.
- **Budget de la sauvegarde Android (25 Mo, au-delà rien n'est sauvegardé)** : photo carrée de 480 px en JPEG qualité 80, environ 40 Ko ; base et pictos téléchargés, quelques centaines de Ko ; copies de base au plus 3. Il tient environ 500 photos. Au-delà, l'export du MVP-4 reste la sauvegarde.
- **Appareil photo** : proposé seulement si l'appareil en a un (`FEATURE_CAMERA_ANY`) ; un lancement sans appli photo (`ActivityNotFoundException`) donne un message, jamais un plantage. La photo pleine taille va dans un fichier fixe `cache/camera/capture.jpg`, vidé avant chaque lancement (certaines applis renvoient « OK » sans écrire) et effacé après l'import et au démarrage. `FileProvider` limité à ce dossier.
- **Contrôle parental** : un choix de photo en cours excuse un `ON_STOP` ; mais si le résultat revient plus de 2 minutes après le lancement (l'adulte est parti, l'enfant a repris la tablette dans la tâche de l'appli photo), l'édition se termine et la photo est ignorée.
- **Import** : toujours `BitmapFactory` (dimensions d'abord, sous-échantillonnage) puis `ExifInterface` pour toutes les orientations, miroirs compris (`ImageDecoder` n'est pas employé : un seul chemin, aucune double rotation) ; fond blanc sous une image transparente ; jeton d'import (un résultat arrivé après la fermeture de l'éditeur est ignoré et son fichier effacé) ; « Enregistrer » attend la fin de l'import. Test avec une image asymétrique : le pixel marqué arrive au bon coin, pour 90° et 270°.
- **Affichage** : photos décodées à 240 px en `RGB_565`, au plus 48 gardées en mémoire (les plus anciennes sortent) ; une photo absente n'est signalée qu'une fois.
- **Éditeur** : choisir une photo retire le picto et inversement ; texte dit d'une ligne, 200 caractères au plus, vide ou égal au libellé = `null`, masqué pour un dossier ; la phrase affiche ce que la case dit, et l'élision se calcule dessus (voulu : la phrase est ce qu'on entend) ; « Ouvrir le dossier » compare aussi photo et texte dit.
- **Journaux** : jamais d'adresse ni de nom de fichier d'origine (la classe de l'exception seulement).
- **MVP-4** : l'export et la restauration incluent `files/photos/` (entrée mise à jour).
- Écarté : relire un état sauvé par la classe précédente dans un test (la compatibilité d'un champ ajouté avec le même `serialVersionUID` est garantie par la spécification de la sérialisation Java) ; vérifier le manifeste fusionné (aucune bibliothèque du projet n'ajoute CAMERA, à revoir si une dépendance arrive) ; entrée `ModuleDependencies` du sélecteur (sans elle, repli sur `ACTION_OPEN_DOCUMENT`, qui marche).

## Envisagé et écarté

- Recadrage interactif : un écran de plus ; le centre carré convient aux portraits et aux objets, l'adulte reprend la photo sinon. Reporté.
- Garder l'`Uri` d'origine : la photo disparaîtrait avec la galerie (et l'accès expire).
- Permission CAMERA et prise de vue intégrée (CameraX) : une dépendance lourde et une permission de plus pour ce que l'appli photo du système fait.
- Une table des photos : un nom de fichier dans la case suffit.
- Photo dans la base (BLOB) : la base grossit, la sauvegarde et les migrations aussi.

## Observabilité

- Import qui échoue (flux illisible, image non décodable, disque plein) : `Log.e` sans le nom ni l'adresse de la photo (vie privée), message dans l'éditeur (« Cette image n'a pas pu être utilisée »).
- Renommage à l'« Enregistrer » qui échoue : `EditFailure.Storage`, rien en base.
- Photo absente à l'affichage : `Log.w`, la case garde son libellé.

## Retries et limites

Sans objet (aucun réseau). Une seule photo en cours de préparation à la fois ; un nouveau choix remplace et supprime le précédent temporaire.

## Plan de test

- Migration 2 → 3 (instrumenté) : grilles et cases gardées, colonnes nouvelles à `null`.
- Domaine (JVM) : la photo l'emporte sur le picto ; l'élision garde les deux images ; une case dit son texte propre et c'est lui qui entre dans la phrase.
- `CellPhotos` (instrumenté, vraie image) : une photo portrait 3:4 avec orientation EXIF 90° donne un carré de 480 px orienté ; le temporaire n'est visible qu'après validation ; une image illisible ne laisse aucun fichier ; les temporaires sont effacés au démarrage.
- ViewModel (faux `PhotoStore`) : choisir puis enregistrer valide la photo et l'écrit dans la case ; annuler la supprime ; un choix en cours ne termine pas l'édition sur `ON_STOP` ; un échec de validation donne `Storage`, rien en base.
- `SavedWord` : un état sauvé sans photo se relit.
