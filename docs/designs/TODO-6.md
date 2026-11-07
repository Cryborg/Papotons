# TODO-6 : la phrase et la navigation survivent à la mort du processus

## Contrat et fichiers touchés

Android tue une appli en arrière-plan quand la mémoire manque (tablettes modestes, cas courant). Au retour, l'activité est recréée et `BoardViewModel` repart de zéro : la phrase en cours est vidée et la grille affichée redevient l'accueil. Pour une personne qui composait une phrase mot par mot, c'est du travail perdu.

Après : la pile de navigation (ids des grilles) et les mots de la phrase sont recopiés dans le `SavedStateHandle` du ViewModel à chaque changement, et relus à sa création.

- Navigation : `LongArray` sous la clé `navigation`. À la création, `ensureHomeBoard()` tourne toujours (insertion, alerte de perte) ; la pile sauvée n'est reprise que si son premier élément est l'accueil rendu, sinon on repart de l'accueil. Une grille de la pile qui n'existe plus se dépile déjà d'elle-même (`display`).
- Phrase : `ArrayList<SavedWord>` sous la clé `message`, `SavedWord` étant une data class `java.io.Serializable` (libellé, nom de catégorie, id de picto) définie dans `ui/board/` : le domaine reste pur. La catégorie est sauvée par son nom (`WordCategory.fromStoredName` à la relecture, comme la base) : un nom inconnu se relit sans couleur.
- **Mécanisme** : l'état sauvé est lu UNE fois, de façon synchrone, dans le constructeur. `message` démarre avec la phrase relue ; la pile relue est passée à la seule ouverture de l'init (pas à `reopenHome` rappelé par `display`), qui la reprend si sa racine est l'accueil rendu par `ensureHomeBoard`. Ensuite le handle n'est qu'un miroir jamais relu : un collecteur par flux le recopie, celui de la pile ignore la pile vide (sinon la pile vide de l'init écraserait la pile sauvée pendant `ensureHomeBoard`).
- R8 garde le nom et les champs de `SavedWord` (`proguard-rules.pro`), et son `serialVersionUID` est fixé : un état sauvé reste lisible d'un build à l'autre tant que la classe ne change pas.
- Ordre de grandeur : quelques centaines d'octets par mot, loin de la limite de 1 Mo des transactions : pas de plafond.

Fichiers : `ui/board/BoardViewModel.kt`, `ui/board/SavedWord.kt` (nouveau), `app/proguard-rules.pro`, `BoardViewModelTest.kt`, `androidTest/.../SavedBoardStateTest.kt` (nouveau), `docs/TODO.md`. Couplage : `MainActivity` retient la boîte de mise à jour tant qu'une phrase est en cours, donc aussi après une phrase restaurée (voulu).

Invariants : 2 (parler hors ligne, inchangé), 4 (rien de persistant n'est touché ; l'état d'écran n'est pas une donnée utilisateur au sens de l'invariant, mais sa perte en est une pour la personne).

## Envisagé et écarté

- Sauver les ids des cases et relire les mots en base : un mot modifié ou supprimé entre-temps changerait la phrase déjà composée.
- `@Parcelize` : un plugin et une dépendance d'exécution, premier usage du poste ; `Serializable` suffit pour quelques mots.
- Tableaux parallèles (libellés, catégories, pictos) : trois listes à garder alignées à la main.
- `setSavedStateProvider` (état calculé au moment de sauver, sans miroir) : aucun test JVM possible (`Bundle` n'est qu'un bouchon).
- `SavedStateHandle.getMutableStateFlow` : il exige un type sauvegardable dans le flux lui-même, or l'état garde des `PersistentList`.
- Garder la phrase en DataStore (survivre aussi à un vrai redémarrage) : une phrase d'hier qui réapparaît serait plus déroutante qu'utile ; reporté.

## Observabilité

Un état sauvé illisible ferait échouer `SavedStateHandle` avant le ViewModel (plantage visible dans Android vitals) : d'où la classe figée face à R8. Une pile sauvée rejetée (racine différente de l'accueil) est journalisée en `Log.w` ; une pile absente est le cas normal d'un lancement à froid, sans log.

## Retries et limites

Sans objet.

## Plan de test

- Un ViewModel recréé sur le même `SavedStateHandle` retrouve la phrase (mots, ordre, picto, catégorie : l'élision « j'ai » doit se refaire, preuve que la catégorie est revenue) et la grille affichée (pile de trois grilles, asymétrique).
- Une pile sauvée dont la racine n'est plus l'accueil est abandonnée pour l'accueil.
- « Tout effacer » vide aussi l'état sauvé (un ViewModel recréé ne ressuscite pas la phrase effacée).
- Pile restaurée `[accueil, 2, 99]` : la grille 99 disparue se dépile, on reste sur 2, la phrase est intacte.
- Un retour arrière et « Effacer le dernier mot » se retrouvent dans l'état sauvé (un collecteur par flux, pas une écriture par site).
- Instrumenté : aller-retour par un vrai `Parcel` (`savedStateProvider().saveState()`, `writeBundle`/`readBundle`, `SavedStateHandle.createHandle`) des types écrits par le ViewModel ; le ViewModel recréé est couvert en JVM, et à la main par `am kill` sur un build minifié.
- Les tests de restauration rougissent si la copie vers le `SavedStateHandle` est retirée.
