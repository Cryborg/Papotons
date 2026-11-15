# Dépendances et échéances Play : Papotons

> Relevé du 2026-11-15 (tournée cloud). Fichier réécrit à chaque passage. C'est un relevé : rien n'est monté d'office.
> Limite : Google Maven (dl.google.com) refusé par le proxy de la session cloud ; les versions AndroidX et Play marquées « non vérifié » sont à confirmer sur le PC.

## Invariant 1 (zéro collecte)

- Aucune dépendance de pub, d'analytics, de crash reporting, de paiement ni de compte. Seule permission : `INTERNET`. Seul réseau dans le code : `data/pictogram/ArasaacClient.kt` (api.arasaac.org, static.arasaac.org). Seule dépendance Google : Play In-App Updates (`app-update` 2.1.0).
- À trancher avant la fiche « Sécurité des données » : le mot tapé par l'adulte dans la recherche part vers api.arasaac.org (un prénom peut y être tapé). À confronter aux définitions de Play (« collecte » = transmission hors de l'appareil), avec ce que Play In-App Updates traite.

## À faire avant le 1er février 2027

- Vérifier dans la Play Console que le statut de commerçant du compte est déclaré (voir les échéances).

## Dépendances en retard

⚠️ Kotlin, AGP, KSP et Gradle sont partagés par les quatre projets Android : une montée se fait partout le même jour. État le 2026-11-15 : Gradle 9.6.1, Kotlin 2.4.20, KSP 2.3.12 partout ; AGP 9.4.1 sauf Bubble Blend (9.4.0).

| Dépendance | Actuelle | Dernière stable | Écart | Risque | Effort |
|---|---|---|---|---|---|
| Gradle (partagé) | 9.6.1 | 9.9.1 | 3 mineures | faible à moyen | sur les 4 projets |
| AGP (partagé) | 9.4.1 | 9.5.0 | 1 mineure | moyen (R8, Lint) | sur les 4 projets |
| Kotlin (partagé) | 2.4.20 | 2.4.21 | patch | faible | sur les 4 projets |
| KSP (partagé) | 2.3.12 | 2.3.14 | 2 patchs | faible | faible |
| Room | 2.8.5 | 2.8.5 (dernière 2.x, en maintenance) ; Room 3.0.3 existe | 0 en 2.x | Room 3 = changement de paquet et fin de SupportSQLite : `CorruptionCopyingOpenHelperFactory` (copie de secours, invariant 4) et les migrations seraient à réécrire | ne pas monter sans sujet dédié |
| play-publisher | 4.1.1 | 4.1.2 | patch | faible | très faible |
| mockk (tests) | 1.14.11 | 1.14.12 | patch | nul | très faible |
| org.json (tests) | 20240303 | 20260814 | 2 ans | nul | très faible |
| kotlinx-serialization-bom | 1.8.1 | 1.13.0 | sans objet : aucune bibliothèque kotlinx-serialization ne la consomme, ligne morte du catalogue | nul | la retirer |
| À jour : Hilt 2.60.1, Compose BOM 2026.08.00, lifecycle 2.11.0, activity-compose 1.13.0, datastore 1.2.1, app-update 2.1.0, kotlinx-collections-immutable 0.5.2, coroutines 1.11.0, turbine, JUnit | | | | | |
| Non vérifié : core-ktx 1.19.0, core-splashscreen 1.2.0, exifinterface 1.4.2, hilt-lifecycle-viewmodel-compose 1.4.0, androidx test | | | | | |

## APIs dépréciées

- `ui/settings/SettingsDialog.kt:361-362` : `window.statusBarColor` et `navigationBarColor`, dépréciés depuis l'API 35, gardés derrière `SDK_INT < VANILLA_ICE_CREAM` (l.145) : sans risque.
- `data/pictogram/ArasaacClient.kt:74` : `URL(String)`, déprécié par le JDK 20 au profit de `URI(...).toURL()`.

## Échéances Google Play (recherche web du 2026-11-15)

Page officielle des échéances (support.google.com) injoignable depuis la session cloud : dates tirées de guides tiers concordants, à confirmer dans la Play Console.

| Échéance | Date | Ce que ça bloque | État du projet |
|---|---|---|---|
| Déclaration du statut de commerçant (trader status, compte développeur) | 1er février 2027 | Mises à jour bloquées, appli retirée du store si non déclaré | Geste de compte dans la Play Console, à vérifier (vaut pour toutes les applis du compte) |
| Politique des autorisations Contacts, Localisation, `READ_CALL_LOG` | 27 janvier 2027 | Refus de mise à jour pour une appli qui les demande sans y avoir droit | Aucune de ces autorisations demandée |
| targetSdk 36 pour les nouvelles versions | passée (31 août 2026, extension au 1er novembre 2026) | Mises à jour refusées | Conforme (targetSdk 37) |
| targetSdk 37 (prochaine marche) | attendue fin août 2027, date non publiée | Mises à jour refusées | Déjà en 37 |
| Play Billing Library 8 minimum | passée (31 août 2026) ; la v8 sera refusée après le 31 août 2027 | Mises à jour refusées | Sans objet (aucun paiement) |

- Politique Familles : Play exige que la cible, la fiche « Sécurité des données » et le questionnaire IARC soient justes et tenus à jour ; voir le point ARASAAC en tête.
