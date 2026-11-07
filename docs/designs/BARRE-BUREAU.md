# BARRE-BUREAU : barre des adultes en bas partout, barre système masquée, bouton Bureau, épinglage

Demande de Franck (2026-11-07) :
- la barre des adultes (prénom, Réglages) reste en bas, même en paysage (plus de rail) ;
- la barre de navigation du système se cache automatiquement ;
- un bouton qui ramène au bureau de la tablette, dans la barre des adultes, avec une option par utilisateur qui le grise et exige le code d'un accompagnant pour s'en servir.

Choix de Franck (2026-11-07) :
- option **cochée par défaut** (nouveaux utilisateurs comme le premier) ;
- option cochée sans accompagnant : **le calcul**, comme la roue dentée ;
- **pas de migration** : aucun utilisateur réel, la version 4 du schéma est amendée et la base des appareils de test s'efface ;
- l'option **épingle l'appli** (épinglage d'Android), seul moyen d'empêcher la sortie par le glissement ou le bouton Accueil du système, qu'une barre masquée ne fait que cacher. Il ne protège vraiment que si la tablette a un code : sans code, un appui long (Retour et Aperçu, ou glisser et maintenir) désépingle ;
- en **édition**, le bouton Bureau ne redemande pas le code, comme la roue.

## Contrat et fichiers touchés

**Barre des adultes, toujours en bas.** `BoardAdultBar` perd le mode rail (et `InitialBadge`, qui ne servait qu'au rail) ; `BoardScreen` perd `BoxWithConstraints` et `isRail`. Contenu, de gauche à droite : prénom (inchangé), bouton **Bureau** (icône `exit_to_app`), roue dentée. Épaisseur fixe 48 dp (invariant 3).

Ordre de grandeur pour les grandes cases sur un téléphone couché de 360 dp de haut : 360 − 24 (barre d'état) − 104 (barre du haut) − 48 (barre des adultes) − marges de la grille, soit environ 50 dp par ligne sur 3 lignes, juste au-dessus de la cible de 48 dp. Calcul fait à la main, à revoir sur un vrai téléphone : c'est la raison du rail qui tombe, pas une mesure.

**Barre de navigation masquée, cases immobiles.**
- `MainActivity` cache `navigationBars()` (la barre d'état reste) avec `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, dès `onCreate` et de nouveau à chaque `onWindowFocusChanged(true)` (avant Android 11, le retour d'une autre fenêtre la réaffiche).
- La disposition **n'écoute plus la barre de navigation** : la colonne prend la barre d'état et l'encoche, la barre des adultes l'encoche seulement. Quand la barre système revient (glissement, modale qui prend le focus, clavier), elle passe **par-dessus** : la barre des adultes sur une tablette, la dernière colonne de cases sur un téléphone couché en navigation à trois boutons. Aucune case ne bouge (invariant 3) ; pendant une modale, la grille ne se touche pas de toute façon.
- **Écran partagé ou fenêtre** (`isInMultiWindowMode`) : Android ne masque pas la barre, qui couvrirait la roue pour de bon ; sa place est alors réservée. L'activité est recréée en entrant dans ce mode.
- Les Réglages (fenêtre plein écran à part) la masquent aussi, dans le `SideEffect` qui règle déjà leurs barres ; leur contenu garde `safeDrawingPadding`.
- Écarté : réserver toujours la place de la barre masquée (`navigationBarsIgnoringVisibility`), qui laisserait une bande vide de 48 dp en navigation à trois boutons.

**Épinglage.** `ExitLockEffect(isExitLocked)` dans `ui/board/ExitLock.kt` :
- à la reprise (`LifecycleResumeEffect` : `startLockTask` exige une activité au premier plan), `shouldRequestPin(isExitLocked, hasRequested, isPinned)` (fonction pure, testée) : option cochée, pas encore demandé depuis le dernier vrai départ, pas déjà épinglée. Puis `hasRequested = isExitLocked` (déjà épinglée compte comme demandé ; décochée puis recochée redemande) ;
- `hasRequested` en `rememberSaveable`, remis à faux sur un `ON_STOP` hors rotation : un refus n'est redemandé qu'au retour au premier plan. La confirmation d'Android revient donc à chaque lancement, souvent devant l'enfant, qui peut la refuser : c'est le prix d'un épinglage sans propriétaire de l'appareil ;
- **épinglé aussi en édition** : vérifié sur l'émulateur (API 37), l'appareil photo, le sélecteur de photos et ceux de fichiers s'ouvrent dans la tâche épinglée ;
- **seul le bouton Bureau désépingle**. Vérifié sur l'émulateur : un désépinglage verrouille l'appareil s'il a un code, et ce départ (ON_STOP) terminerait l'édition et fermerait les Réglages. Décocher l'option ou passer à un utilisateur non protégé ne désépingle donc pas : l'appli cesse seulement de redemander ;
- `startLockTask` / `stopLockTask` qui lèvent (`IllegalArgumentException` hors premier plan, `SecurityException`, `IllegalStateException`) : `Log.w`, rien d'autre.

**Bouton Bureau.** Lance `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)` avec `FLAG_ACTIVITY_NEW_TASK`, après `stopLockTask()` si l'appli est épinglée (épinglée, Android bloque l'ouverture du lanceur). Tablette avec un code : après notre contrôle, Android demande en plus le code de la tablette (double contrôle assumé : sans le nôtre, un enfant qui touche Bureau tomberait sur l'écran de verrouillage et perdrait sa grille). Aucune activité : `Log.w`. Fonction pure `needsGateToLeave(isExitLocked: Boolean?, isEditing: Boolean)` : vrai si l'option est cochée **ou inconnue**, hors édition.
- Option cochée : icône grisée (`colorScheme.outlineVariant`, 3,27:1 sur le fond de la barre, contre `onSurfaceVariant` d'ordinaire), Toujours touchable : l'appui ouvre le contrôle (`GatedAction.LeaveApp` : code d'un accompagnant, ou calcul sans accompagnant), puis quitte. Mauvaise réponse ou fermeture : rien.
- Option décochée, ou en édition : couleur normale, quitte directement.
- Icône seule, comme la roue. Descriptions TalkBack : « Quitter Papotons pour le bureau », et « …, réservé aux adultes » quand il est protégé.

**Option par utilisateur.** `profiles.is_exit_locked INTEGER NOT NULL DEFAULT 1` (`ProfileEntity.isExitLocked`, `@ColumnInfo(defaultValue = "1")`, défaut `true`). Schéma 4 amendé : `Migration3To4` crée la colonne, `4.json` régénéré. Domaine `Profile(id, name, isExitLocked)`. Dépôt `setExitLocked(profileId, isLocked)` dans `write {}` (la sauvegarde la voit comme non sauvegardée). Sauvegarde `.obz` : `ext_papotons_exit_locked` par utilisateur ; absente à la lecture : cochée.

Réglages, section « Grilles de Léo » de l'accueil, une ligne à interrupteur « Protéger la sortie de l'appli », résumé « Quitter Papotons passe par le contrôle des adultes, et l'appli s'épingle à l'écran » (contrôle : code d'un accompagnant, ou calcul sans accompagnant) :
- toute la ligne bascule (`toggleable`, rôle `Switch`) ;
- désactivée pendant une écriture (`isWorking`) ;
- absente tant que l'utilisateur ouvert n'est pas connu ;
- échec d'écriture : `ProfileFailure.ExitLockNotSaved`, message sous la ligne, sur l'accueil seulement (la page des utilisateurs ne l'affiche pas).

**Base existante.** La version 4 change d'empreinte sans changer de numéro : la base actuelle d'un appareil fait planter l'ouverture. À faire avant le premier lancement :

```bash
adb -s emulator-5554 shell pm clear com.wobblebot.papotons
```

Même chose (ou « Effacer les données » dans Android) sur la tablette et le téléphone de Franck. Revenir à une ancienne version installée plante de même ; la sauvegarde Android pourrait rapporter l'ancienne base à une réinstallation.

**Fichiers.**
- `MainActivity.kt` ;
- `ui/board/{BoardAdultBar,BoardScreen,ExitLock}.kt` ;
- `ui/settings/{SettingsDialog,SettingsComponents,ProfilesPage,ProfilesViewModel}.kt` ;
- `data/profile/{ProfileEntity,Migration3To4}.kt`, `data/board/{BoardDao,BoardRepository}.kt`, `data/backup/BackupArchive.kt`, `domain/Profile.kt` ;
- `res/drawable/ic_exit_to_app.xml`, chaînes, `app/schemas/…/4.json` ;
- `docs/TODO.md` : décision de la barre des adultes revue, IDEE-3 retirée (livrée) ;
- tests.

**Invariants.**
- 1, 2, 5 : inchangés.
- 3 : la barre garde son épaisseur ; la barre système ne prend plus de place, elle passe par-dessus ; aucune case ne bouge quand elle va et vient.
- 4 : amender la version 4 est le choix de Franck tant qu'aucun utilisateur n'existe ; l'export `.obz` garde l'option.

## Envisagé et écarté

- Garder le rail en paysage : écarté par Franck.
- Masquer aussi la barre d'état : non demandé (heure et batterie utiles à l'adulte).
- Bouton protégé sans épinglage : un glissement depuis le bas rend le bouton Accueil du système. Écarté par Franck.
- Désépingler en édition : verrouille la tablette protégée par un code et termine l'édition (vérifié sur l'émulateur).
- Désépingler quand l'option se décoche : même verrouillage, Réglages fermés sous les yeux de l'adulte.
- Une version 5 avec migration : écartée par Franck (aucun utilisateur).
- Bouton Bureau désactivé (`enabled = false`) quand l'option est cochée : il ne pourrait plus ouvrir le contrôle.
- Masquer la barre dans chacune des 14 modales : inutile dès que la disposition l'ignore ; elle revient par-dessus pendant la modale et repart à sa fermeture.
- Mode kiosque (propriétaire de l'appareil, sans confirmation) : demande de réinitialiser la tablette, hors de portée d'une famille.

## Observabilité

- Lanceur introuvable, épinglage refusé par Android, désépinglage impossible : `Log.w` avec la raison. Un désépinglage raté fait aussi échouer l'ouverture du lanceur (violation de l'épinglage, sans exception) : seul ce `Log.w` le dit.

**Autres applis ouvertes sans contrôle, appli épinglée.** Réglages du son (modale du son coupé) et de la voix (modale de la voix) : lancés sans nouvelle tâche, ils devraient s'ouvrir dans la nôtre (non vérifié) ; refusés par l'épinglage, rien ne s'ouvre et la modale se ferme quand même (`startActivity` ne lève pas). Mise à jour Play : le processus redémarre, l'épinglage tombe (et la tablette se verrouille si elle a un code) ; il est redemandé au retour.
- Refus de l'épinglage par l'adulte : visible (la confirmation d'Android disparaît, la barre du système reste atteignable) ; redemandé au prochain retour.
- Échec d'écriture de l'option : `Log.e` avec l'id, message sur l'accueil des Réglages.

## Plan de test

- `needsGateToLeave` (JVM) : coché, décoché, inconnu, en édition. `shouldRequestPin` (JVM) : demande une fois, refus pas redemandé, déjà épinglée pas redemandée, décoché jamais.
- `BoardScreenLayoutTest` : la barre de navigation qui revient (encarts injectés) ne bouge aucune case, et sa place est réservée en multifenêtre ; le bouton Bureau, protégé ou non, appelle sa propre action (l'aiguillage contrôle ou sortie est `needsGateToLeave`, lu à l'appui dans `BoardRoute`).
- `BoardScreenLayoutTest` : en largeur aussi, la roue et le bouton Bureau sont sous la grille ; le test du rail est retiré ; l'arrivée du prénom et le changement de l'option ne bougent aucune case.
- `BoardProfilesTest` : un nouvel utilisateur est protégé ; `setExitLocked` change l'utilisateur visé seulement et allume « non sauvegardé » ; utilisateur inconnu : `ProfileNotFoundException`.
- `ProfilesViewModelTest` : un échec d'écriture donne `ExitLockNotSaved`.
- `BackupArchiveTest` : aller-retour (un coché, un décoché) ; absente : cochée ; valeur non booléenne : refusée.
- `MigrationTest` 3 → 4 : le premier utilisateur est protégé. `BoardDaoTest` : `setExitLocked` d'un id absent rend 0.
- `SettingsDialogTest` : la ligne bascule l'option de l'utilisateur ouvert ; absente sans utilisateur ouvert.
- À la main sur l'émulateur (API 37, navigation par gestes), fait : barre cachée ; épinglage demandé au lancement, refusé sans boucle ; Bureau protégé : calcul, désépinglage, bureau ; Bureau non protégé : sortie directe ; option recochée dans les Réglages : redemandé ; édition épinglée : appareil photo, sélecteur de photos, sélecteurs de fichiers ; code posé : un désépinglage verrouille.
- Reste pour la tablette de Franck : écran partagé, fenêtres libres, navigation à trois boutons, téléphone couché.
