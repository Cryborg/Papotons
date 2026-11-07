# TODO-10 : un export raté n'efface plus la sauvegarde qu'il devait remplacer

## Contrat et fichiers touchés

**Aujourd'hui.** Sur tout échec, `BoardBackup.export` supprime le document (`BoardBackup.kt:181-186`), y compris quand l'échec tombe avant toute ouverture du document (base illisible au `snapshot()`, refus `TooLarge` des images, `validate` : toutes avant `openForWriting`, `:115-157` contre `:158`). `BackupViewModel.onSaveDocument` supprime aussi sans condition un document revenu après plus de 2 minutes (`BackupViewModel.kt:116-118`). Et si le fournisseur refuse `"wt"`, le repli `"w"` (`:220-231`) écrit sans tronquer : sur un document existant plus long, la queue de l'ancien zip reste, la nouvelle archive est illisible, et l'export se dit pourtant réussi. Quand le créateur de document rend un fichier EXISTANT (l'adulte touche son ancien `papotons-<date>.obz` et confirme « Remplacer »), l'unique sauvegarde externe peut disparaître.

**Après.**
1. **Échec avant l'ouverture du document** (lecture de la base, images, validation, ouverture refusée) : le document n'a pas été touché. Il est supprimé **seulement s'il est vide** (le document que le créateur vient de faire, qu'on ne veut pas voir passer pour une sauvegarde). Non vide, ou vide inconnu : il reste tel quel, avec un journal.
2. **Échec après l'ouverture** (`"wt"` a tronqué, l'écriture a commencé) : supprimé, comme aujourd'hui. L'ancien contenu est déjà perdu par la troncature ; une archive partielle ne doit pas passer pour une sauvegarde.
3. **Ouverture** : `"wt"` d'abord. Si le fournisseur le refuse, le repli `"w"` n'est permis que sur un document **vide** ; sinon l'export échoue avant l'ouverture (point 1 : document gardé, « La sauvegarde a échoué »).
4. **Document revenu trop tard** (ViewModel) : même règle que le point 1, supprimé seulement s'il est vide.

**« Vide »** : `contentResolver.openInputStream(document)` puis `read() < 0`. Trois réponses : vide, non vide, inconnu (`null` : pas de flux, `IOException`, `SecurityException`, `RuntimeException`). La taille `OpenableColumns.SIZE` ne sert que de veto : connue et positive, le document n'est pas vide, quoi que dise la lecture (le flux d'un fournisseur en ligne coupé finit sans erreur, comme un document vide). Elle ne conclut jamais « vide » : sa colonne peut être nulle (Drive), et `Cursor.getLong` sur une colonne nulle rend 0. La lecture d'un octet marche sur tout fournisseur qui sait lire, et sur un `file://`.

**Suppression** : `DocumentsContract.deleteDocument` comme aujourd'hui ; pour un `file://` (les tests instrumentés : l'ouverture et la lecture les acceptent déjà, `deleteDocument` non), `File.delete()`. Sans cette branche, aucun test ne voit la règle « vide seulement » ni sa violation (le `deleteDocument` d'aujourd'hui sur un `file://` lève une exception avalée).

**Interface `BackupStore`** : `deleteDocument(document)` devient `deleteEmptyDocument(document)`. Seul appelant extérieur : le ViewModel (`:117`) ; le faux de `BackupViewModelTest` suit. Dans `BoardBackup`, une suppression sans condition (privée) reste pour le point 2.

**Le nettoyage ne masque jamais l'erreur** : dans le `catch` de `export`, la suppression est entourée d'un `try { } catch (Throwable) { log }`, puis l'erreur d'origine est relancée (et un `OutOfMemoryError` toujours converti en `OutOfMemoryWhileWriting`, qui est une `IOException` affichée). Le drapeau « ouvert » se pose après le retour réussi de `openForWriting`.

**`markSaved`** reste hors du `try`, après l'écriture complète (inchangé) ; `aFailedBackupLeavesTheBoardsUnsaved` le garde.

**Commentaires à corriger** : KDoc de `BackupStore.export` (`:71`, « en cas d'échec, le document est supprimé »), commentaire de `openForWriting` (`:220`, « il vient d'être créé »), KDoc de `deleteDocument` (`:86`).

**Fichiers** : `data/backup/BoardBackup.kt`, `ui/backup/BackupViewModel.kt`, `test/.../ui/backup/BackupViewModelTest.kt`, `androidTest/.../data/backup/BoardBackupTest.kt`, `docs/TODO.md` (entrée supprimée).

**Invariants.** 4 : c'est l'objet. 1 : rien ne change (le document reste choisi par le système). 2 et 3 : sans objet.

## Envisagé et écarté

- **Écrire d'abord l'archive dans un fichier privé, puis la recopier** (premier jet de ce plan) : protège aussi des échecs PENDANT l'écriture (image disparue entre la mesure et l'écriture, `:165` et `:168` ; manque de mémoire). Mais double la place (jusqu'à 512 Mo de stockage interne : un export qui passe aujourd'hui sur une tablette presque pleine échouerait), demande un verrou entre deux exports (l'export `NonCancellable` survit à son ViewModel ; une appli relancée en lance un second sur le même `@Singleton`), et un ménage des restes après une mort du processus. Toutes les causes que l'entrée cite tombent avant l'ouverture : le classement avant ou après ouverture les règle sans rien de tout cela. Reporté : si un échec en cours d'écriture est un jour observé.
- **Taille par `OpenableColumns.SIZE` seule** : colonne nulle selon les fournisseurs, piège `getLong` à 0. Gardée comme veto « non vide » seulement.
- **Taille par `openFileDescriptor(...).statSize`** : `-1` sur un flux virtuel. Même verdict.
- **Un `ContentProvider` de test** dans l'APK d'androidTest (taille, modes d'ouverture, suppression) : seul moyen de provoquer un refus de `"wt"` ou un échec pendant l'écriture, mais un `DocumentsProvider` exige `MANAGE_DOCUMENTS` de l'appelant, et un fournisseur simple ne répond pas à `deleteDocument`. Coût et incertitude hors de proportion ; branche `file://` à la place.
- **Ne jamais supprimer sur un échec** : un document vide nommé comme une sauvegarde passerait pour elle. Gardé, borné au document vide.

## Observabilité

| Étape | Échec | Signal |
|---|---|---|
| lecture de la base, images, validation | exception, refus | inchangé : message dans la modale, journal `Backup refused (...)` ou `Backup work failed: <classe>` |
| document gardé après un échec avant l'ouverture | | `Backup document not empty, kept` ou `Backup document unreadable, kept` ; un vide supprimé : `Empty backup document deleted` |
| ouverture `"wt"` qui échoue | refus du mode, document absent ou protégé | `Truncating open failed (<classe>) on <fournisseur>, document empty: <vrai, faux, null>` ; `IOException` (cause chaînée) si le document n'est pas vide ou ne se lit pas : « La sauvegarde a échoué », document gardé |
| échec après l'ouverture | `IOException` du fournisseur, image disparue | `Backup failed while writing (<classe>), document deleted` ou `not deleted` : seul cas où une ancienne sauvegarde est perdue (troncature déjà faite) |
| ménage qui échoue | exception, manque de mémoire | `Backup clean-up failed: <classe>`, l'erreur d'origine remonte |

Sans réseau : pas de section Retries.

## Plan de test

Instrumentés (`BoardBackupTest`, émulateur `emulator-5554`), sur des `file://` :

1. `aFailedBackupKeepsTheBackupItWasToReplace` : le document est une vraie sauvegarde (exportée d'abord) ; la base est fermée, l'export lève `IllegalStateException` au `snapshot()` (Room 2.8 : `throwIfClosed`) ; le document a les mêmes octets. **Rougit** si un échec avant l'ouverture supprime sans condition (le comportement d'aujourd'hui, maintenant visible grâce à la suppression `file://`).
2. `aFailedBackupDeletesTheEmptyDocumentItWasGiven` : document de 0 octet, même échec ; il n'existe plus. **Rougit** si la suppression du document vide disparaît.
3. `onlyAnEmptyReadableDocumentComingBackTooLateIsDeleted` : `deleteEmptyDocument` sur trois documents, vide, non vide, vide illisible ; seul le premier disparaît.
4. `aDocumentThatCannotBeOpenedKeepsItsBackup` : sauvegarde protégée en écriture, l'ouverture échoue, octets identiques (le drapeau « ouvert » ne se pose qu'après l'ouverture).
5. `withoutTruncationOnlyAnEmptyDocumentIsWritten` (mode qui tronque refusé, par `truncatingMode`) : une sauvegarde prolongée d'une queue reste identique, un document vide reçoit une archive qui se relit.
6. `withoutTruncationADocumentThatCannotBeReadIsNotWritten` : document illisible, mode refusé, rien n'est écrit.
7. Tests existants : l'aller-retour complet et le manifeste en dernier prouvent que l'ouverture rend la même archive ; `aFailedBackupLeavesTheBoardsUnsaved` (ouverture qui échoue) garde `markSaved` à sa place.

JVM (`BackupViewModelTest`) : le test « un document revenu trop tard est supprimé » passe par `deleteEmptyDocument` (renommage du faux).

**Zones aveugles, nommées** : un fournisseur sans taille annoncée dont le flux coupé finit sans erreur fait prendre une ancienne sauvegarde pour vide (cumul jugé improbable, et toujours mieux qu'avant, où tout échec supprimait) ; le veto par la taille (un `file://` n'a pas de colonne `SIZE`) ; un fournisseur qui accepterait `"wt"` sans tronquer ; un échec pendant l'écriture (pas provocable sans couture) ; la mort du processus pendant l'écriture laisse un document partiel (comme aujourd'hui, risque accepté) ; le vrai « Remplacer » de DocumentsUI et la lecture d'un octet sur Drive, à vérifier une fois sur appareil.
