# TODO-4 : prévenir quand le volume de la voix est à zéro

## Contrat et fichiers touchés

La voix sort sur le flux média (`USAGE_MEDIA`). Sur une tablette, ce volume est souvent baissé à zéro (vidéo coupée, enfant qui a joué avec les boutons). L'appli parle alors dans le vide, et la personne non oralisante ne peut pas dire qu'on ne l'entend pas. Pire : dans Papotons, les touches de volume règlent la sonnerie et non la voix tant qu'aucun son ne joue.

Après :

1. **Les touches de volume règlent la voix** : `volumeControlStream = AudioManager.STREAM_MUSIC` dans `MainActivity`. Limite assumée : une modale a sa propre fenêtre, les touches y règlent encore la sonnerie (pas de composant de modale commun pour l'instant).
2. **Alerte « Son coupé »** dans la barre du haut quand la voix ne peut pas s'entendre : `getStreamVolume(STREAM_MUSIC) == 0` ou `isStreamMute(STREAM_MUSIC)` (coupure, « Ne pas déranger » en silence total). Jamais si `isVolumeFixed` (rien à régler). Icône haut-parleur barré (`ic_volume_off`, Material), rouge. Description : « Son coupé : monter le volume ».
3. **Un appui monte le son à un niveau audible**, pas d'un seul cran (0 → 1 sur 15 resterait inaudible) : `ADJUST_UNMUTE` si coupé, puis, si le volume reste sous le tiers du maximum, `setStreamVolume(max / 3, FLAG_SHOW_UI)` (le panneau du système confirme ; un flux rétabli plus haut garde son volume). Pas de modale : c'est souvent la personne qui touche l'alerte, et monter le son est ce qu'il faut. **Si le son reste coupé** après l'appui (« Ne pas déranger », réglage du constructeur, `SecurityException`), une modale s'ouvre pour l'adulte avec un bouton vers les réglages du son.
4. **Priorité dans l'emplacement d'alerte** (barre étroite, une seule) : voix indisponible (`Unavailable`), son coupé, voix française manquante (`LanguageMissing` : l'appli parle quand même), grilles perdues. En barre large : toutes, dans un ordre fixe (voix, son coupé, grilles perdues). Les alertes sont entre la navigation et la phrase : qu'une alerte apparaisse ou disparaisse, seule la phrase change de largeur ; ses boutons et « À propos » ne bougent jamais sous le doigt. Limite assumée : une famille qui coupe exprès le son des médias garde l'icône « Son coupé » (discrète) et, en barre étroite, ne voit pas « Grilles perdues » tant que le son est coupé.
5. **Détection** : `MediaVolume` (dossier `speech/`, interface comme couture de test, implémentation Android liée dans `SpeechModule`) expose `isMuted: StateFlow<Boolean>` et `refresh()`. Le volume est relu : à la création ; à chaque mot dit et à « Dire la phrase » (le moment qui compte) ; au retour au premier plan (`recoverSpeech`, déjà appelé à chaque `ON_START`) ; juste après l'appui ; à chaque changement de sortie (`AudioDeviceCallback` : casque, Bluetooth) ; et sur un `ContentObserver` de `Settings.System` (touches de volume), avec un `Handler` du thread principal. Toute exception d'`AudioManager` est attrapée (`Log.w`, son supposé audible) : jamais de plantage ni de grille bloquée. Libéré dans `onCleared`, comme la voix.

Fichiers : `speech/MediaVolume.kt` (nouveau), `speech/SpeechModule.kt`, `ui/board/BoardViewModel.kt`, `BoardUiState.kt`, `BoardScreen.kt` (action, modale), `BoardTopBar.kt` (emplacement d'alerte), `ui/board/VolumeLockedDialog.kt` (nouveau), `MainActivity.kt`, `res/drawable/ic_volume_off.xml`, `res/values/strings_speech.xml`, tests (dont `BoardActions`/`noActions` et les constructeurs du ViewModel).

Invariants : 2 (parler hors ligne : inchangé, on aide seulement à entendre), 3 (barre à hauteur fixe, aucune case ne bouge), 1 (aucune permission ni dépendance nouvelle : `AudioManager`, `AudioDeviceCallback` et `Settings.System` se lisent sans permission).

## Envisagé et écarté

- Monter le volume tout seul avant de parler : changer un réglage de l'appareil sans le dire surprendrait (vidéo suivante à fond) ; l'alerte laisse la main.
- Le diffuser sur `STREAM_ACCESSIBILITY` ou `USAGE_ASSISTANCE_ACCESSIBILITY` : réglé à part, souvent inconnu des familles, et réservé par Android aux services d'accessibilité sur certaines versions.
- Diffusion `android.media.VOLUME_CHANGED_ACTION` (Media3 s'en sert) : action non publique ; la relecture à chaque mot dit et l'`AudioDeviceCallback` couvrent ce que l'observateur rate.
- Composant de modale commun qui réglerait aussi le flux des touches de volume : quatre modales à migrer, sujet à part.
- Modale à chaque appui : un appui qui monte le son se comprend tout seul ; la modale ne sert que si le son ne remonte pas.
- Alerte sur volume faible mais non nul : seuil arbitraire ; reporté.

## Observabilité

- Changement de volume : rien à journaliser (état normal), l'alerte EST le signal, pour la personne comme pour l'adulte.
- `AudioManager` qui lève (`SecurityException` d'un constructeur exotique) : `Log.w`, son supposé audible (pas de fausse alerte permanente, pas de plantage).
- Appui qui ne remonte pas le son : modale pour l'adulte (signal), `Log.w` avec l'état lu.

## Retries et limites

Sans objet.

## Plan de test

- ViewModel (faux `MediaVolume`) : volume à zéro → `isMuted` vrai ; revenu → faux ; un mot dit relit le volume (`refresh` appelé) ; `onMutedAlertClick` ouvre la modale quand le faux refuse de monter, le son revenu la referme pour de bon.
- `MediaVolume` réel (instrumenté, émulateur seul) : volume média mis à 0 → `isMuted` vrai après `refresh` ; `raise()` → faux et volume ≥ max / 3 ; volume d'origine restauré à la fin.
- Barre du haut (Compose, `BoardScreenLayoutTest`) : son coupé seul → icône présente, appui → action ; `Unavailable` + son coupé en barre étroite → seule la voix ; `LanguageMissing` + son coupé → son coupé ; grilles perdues + son coupé → son coupé ; pire cas en barre large (dossier ouvert, voix écrite, trois alertes) : boutons de la phrase atteignables ; aucune case ne bouge (invariant 3).
- Chaque test rougit si la condition qu'il couvre est retirée.
