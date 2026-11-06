---
name: tests-instrumentes-emulateur-seul
description: "Lancer connectedAndroidTest / installDebug avec ANDROID_SERIAL=emulator-5554, jamais sur le téléphone de Franck branché en USB"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 975230e8-ac5b-4e9a-83b6-cf844367425e
  modified: 2026-11-06T19:20:45.420Z
---

Toujours préfixer les tâches Gradle qui touchent un appareil (`connectedDebugAndroidTest`, `installDebug`) par `ANDROID_SERIAL=emulator-5554` (AVD `Medium_Phone_API_37.0`, lancé sans fenêtre).

**Why:** le 2026-11-06, le Pixel 7 Pro de Franck était branché en USB pendant la session ; `connectedDebugAndroidTest` a tourné aussi dessus, puis Gradle a désinstallé l'appli et l'APK de test (une version déjà installée de Papotons aurait été retirée avec ses données). `adb devices` ne le montrait pas au début de la session : le téléphone se branche en cours de route.

**How to apply:** exporter `ANDROID_SERIAL` dans la même commande que `gradle-queue.sh`, et vérifier `adb devices` avant toute installation. Arrêter l'émulateur à la fin (`adb -s emulator-5554 emu kill`) : la RAM du poste est comptée.
