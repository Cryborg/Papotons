# MVP-9 : écran Réglages derrière le contrôle parental

## Contrat et fichiers touchés

Le bouton « À propos » (icône « i ») de la barre du haut devient une roue dentée « Réglages ». Un appui ouvre le contrôle parental ; réussi, il ouvre l'écran Réglages, en plein écran, qui réunit :

- **Modifier les grilles** : ferme les Réglages, entre en édition (comme l'ancien bouton de « À propos ») ;
- **Sauvegarde** : ferme les Réglages, entre en édition et ouvre la modale de sauvegarde (même chemin que « Restaurer une sauvegarde » de la modale des grilles perdues : la sauvegarde n'existe qu'en édition) ;
- **Taille des cases** : ajoutée par MVP-8 ;
- **À propos** : la modale actuelle (crédits, confidentialité, licence, version), sans son bouton « Modifier les grilles ».

Fichiers :
- `ui/board/BoardTopBar.kt` : `onAboutClick` devient `onSettingsClick`, icône `ic_settings` (Material « settings » contour), description `settings_open`.
- `ui/settings/SettingsDialog.kt` (nouveau) : `Dialog` plein écran (`usePlatformDefaultWidth = false`, `dismissOnClickOutside = false`), titre, bouton Fermer, une ligne par entrée (titre + phrase d'explication), cibles de 48 dp au moins, défilement vertical.
- `ui/board/BoardViewModel.kt` + `BoardUiState.kt` : `isSettingsOpen`, en mémoire seulement comme `isEditing` (jamais dans `savedState` : pas de réouverture sans contrôle après la mort du processus). `onOpenSettings()`, `onCloseSettings()`. `onStartEditing()` ferme les Réglages. `isBusy` compte les Réglages ouverts (la mise à jour ne redémarre pas sous l'adulte).
- `ui/board/BoardScreen.kt` : `GatedAction.OpenSettings` ; `ON_STOP` (vrai départ) ferme aussi les Réglages, comme il termine l'édition ; retour arrière ferme les Réglages (la `Dialog` le fait).
- `ui/about/AboutDialog.kt` : retire `onEditBoards`.
- `res/values/strings_settings.xml` (nouveau), `res/drawable/ic_settings.xml` (nouveau), `strings_about.xml` (`about_open` gardé : titre de l'entrée « À propos »), `strings_edit.xml` (commentaire de `edit_open`).
- Tests : `BoardViewModelTest` (Réglages fermés par l'édition, jamais restaurés), `EditEntryTest` (roue dentée → contrôle → Réglages → Modifier les grilles), `BoardScreenLayoutTest` (`about_open` → `settings_open`).

Invariants :
- **5 (crédit ARASAAC visible)** : le crédit reste dans l'appli (Réglages > À propos) et sur la fiche Play. Il passe derrière le contrôle parental : décision de Franck (2026-11-07, « Tout l'écran »).
- **3** : la barre garde sa hauteur fixe ; la roue prend la place exacte du « i » (même `IconButton`), rien ne bouge.
- **1** : rien de réseau.

## Envisagé et écarté

- Navigation Compose avec un vrai écran : l'appli n'a qu'un écran et toutes ses surfaces adultes sont des modales plein écran (éditeur de case) ; on imite.
- Réglages ouverts sans contrôle, actions protégées une à une : écarté par Franck.
- Garder un bouton « À propos » libre à côté de la roue : une cible de plus dans la barre, que l'enfant toucherait.
- Retirer le bouton Sauvegarde du bandeau d'édition : il porte la pastille « pas sauvegardé » à l'endroit où l'adulte travaille ; on le garde.

## Observabilité

Rien de nouveau qui puisse échouer : l'ouverture est un état en mémoire. Une erreur de l'édition ou de la sauvegarde garde ses messages actuels.

## Plan de test

- VM : `onOpenSettings` ouvre, `onStartEditing` ferme (sinon l'édition s'afficherait sous les Réglages) ; un ViewModel recréé depuis `savedState` n'a pas les Réglages ouverts.
- VM : `isBusy` vrai avec Réglages ouverts.
- Instrumenté : la roue ouvre le contrôle, pas les Réglages ; mauvaise réponse : ni Réglages ni édition ; bonne réponse : Réglages, puis « Modifier les grilles » : édition et Réglages fermés.
- Disposition : la roue ne chevauche pas les boutons de la phrase (tests existants renommés).

## Après la revue du plan

- `ON_STOP` ferme les Réglages sans condition (ils n'ouvrent aucune autre appli).
- En édition, la roue ouvre les Réglages sans redemander le contrôle (l'adulte vient de le passer) ; « Modifier les grilles » y ferme seulement les Réglages.
- « À propos » s'ouvre depuis les Réglages, état local (`remember`) : il ne survit ni à leur fermeture ni à la mort du processus.
- `GatedAction.StartEditing` disparaît (plus d'appelant).
- Invariant 5 du CLAUDE.md précisé : crédit dans Réglages > À propos, derrière le contrôle parental.
- Tests instrumentés : Réglages ouverts puis vrai départ (`ON_STOP`) : fermés ; rotation : gardés.
