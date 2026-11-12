---
name: tests-instrumentes-emulateur-seul
description: "connectedAndroidTest avec ANDROID_SERIAL=emulator-5554, jamais sur un appareil réel de Franck (Pixel 7 Pro, Galaxy Tab A9+) ; essai visuel sur appareil par adb install -r"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 975230e8-ac5b-4e9a-83b6-cf844367425e
  modified: 2026-11-07T16:37:01.849Z
---

Toujours préfixer `connectedDebugAndroidTest` par `ANDROID_SERIAL=emulator-5554` (AVD `Medium_Phone_API_37.0`, lancé sans fenêtre), même quand un appareil réel est branché : le Pixel 7 Pro, ou la tablette Galaxy Tab A9+ (SM-X210, série R9WWA08BTGZ) qui porte les vraies grilles d'un enfant.

**Why:** à la fin des tests, Gradle désinstalle l'appli et ses données (arrivé le 2026-11-06 sur le Pixel) ; et quatre tests (`EditEntryTest`, `CaregiverGateTest`, `EditModeLifecycleTest`, `VerbFormsBackTest`) ouvrent la vraie appli et écrivent dans ses données. Le 2026-11-07, Franck a demandé pourquoi pas la tablette : on a convenu d'un essai visuel seulement.

**How to apply:**
- Pour voir un changement sur la tablette : `adb -s R9WWA08BTGZ install -r app/build/outputs/apk/debug/app-debug.apk` (mise à jour, données gardées), puis `adb -s R9WWA08BTGZ exec-out screencap -p`. Ne rien y créer ni modifier : demander à Franck de le faire.
- Ne pas lancer d'autre commande adb vers un appareil pendant que la suite instrumentée tourne : le 2026-11-07, une installation sur la tablette en pleine suite a coïncidé avec un test bloqué 25 min (cause non prouvée).
- Lancer la suite complète avec `timeout 580` devant, pour qu'un test bloqué ne fige pas la session.
- Vérifier `adb devices` avant toute installation ; arrêter l'émulateur à la fin (`adb -s emulator-5554 emu kill`).
