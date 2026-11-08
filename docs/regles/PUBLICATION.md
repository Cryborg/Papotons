# Publication sur le Play Store : ce qui se règle à la main

Ce que le code et le dépôt ne peuvent pas faire. À revoir avant chaque version qui change ce qui sort de l'appareil (invariant 1).

## Première mise en tests internes

Une fois pour toutes, dans cet ordre. Ensuite, chaque version part par `/deploy` (brique deploiement : `.claude/commands/deploy.md`, `.claude/scripts/deploy.sh`, `.claude/deploy.conf`).

1. **Clé d'importation** (toi, mot de passe tapé à la main) :

   ```bash
   keytool -genkeypair -v -keystore ~/projets/Papotons/release-keystore.jks -alias papotons -keyalg RSA -keysize 4096 -validity 10000
   ```

   Puis dans `local.properties` : `RELEASE_STORE_FILE=../release-keystore.jks`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS=papotons`, `RELEASE_KEY_PASSWORD`. Copie de secours dans `atelier/secrets/01-cles-android/papotons/`, avec sa ligne dans `secrets/LISEZMOI.md`. Sans cette clé, `bundleRelease` signe avec la clé debug et Play refuse l'AAB.
2. **Créer l'appli** dans la Play Console : nom « Papotons », langue par défaut français (France), application, **gratuite** (définitif). Accepter Play App Signing : Google garde la clé de signature, la nôtre n'est que la clé d'importation.
3. **Testeurs** : Tester > Tests internes > Testeurs, une liste d'adresses e-mail (100 au plus), puis copier le lien d'inscription.
4. **Premier AAB** : `/deploy 0.1.0 --internes` (notes déjà prêtes dans `docs/RELEASE_NOTES.md`). Sans clé Play, le script construit l'AAB dans le worktree `../Papotons-release`, le copie sous `build/release/`, tague `v0.1.0`, pousse, et dit quel fichier déposer. Le déposer dans Tests internes > Créer une release, coller les notes, enregistrer, publier.
5. **Envoyer le lien d'inscription** aux testeurs : ils acceptent, puis installent depuis le Play Store (quelques minutes, parfois plus pour la toute première release).
6. **Compte de service** pour que les versions suivantes partent seules : procédure de la brique deploiement (`LISEZMOI.md`, « Prérequis »), clé en `play-publish-key.json` à la racine, rôle « Release manager » restreint à Papotons. Renseigner alors `PLAY_PROJET_GCP` dans `.claude/deploy.conf`, puis vérifier sans rien publier : `bash .claude/scripts/deploy.sh cle`.

Les tests internes ne passent pas par l'examen de Google et n'attendent en principe pas que la fiche, la classification et les déclarations soient finies ; le test fermé (MVP-6) et la production, si. Si la console réclame une section dès les tests internes, la remplir d'après celles qui suivent.

## Politique de confidentialité

- Source : `docs/confidentialite.html`, ici. Le site du studio l'héberge sans la posséder : on la corrige ici, jamais sur le site.
- Adresse : `https://wobblebotstudio.com/legal/papotons/privacy.html`, comme les pages légales des autres projets (celle que cite la modale « À propos », `about_privacy`).
- Mise en ligne : `python3 tools/publish_legal_pages.py` (`--dry-run` pour voir sans envoyer), à relancer à chaque changement de la page. Vérifier la page servie avec un `?v=` quelconque : le cache ressert l'ancienne.
- Play Console > Contenu de l'application > Règles de confidentialité : coller cette adresse.
- Contact : la page renvoie aux tickets GitHub (publics, avec un compte). Google demande un point de contact pour la confidentialité : mettre de préférence une adresse e-mail dédiée, la même que celle de la fiche Play, dans la section « Contact » de la page.

## Fiche Play

- Textes : `app/src/main/play/listings/fr-FR/` (titre 30 caractères au plus, description courte 80, longue 4 000), publiés par Gradle Play Publisher (`publishListing`), jamais par une session de code.
- Le crédit ARASAAC (invariant 5) est à la fin de la description longue : ne pas le retirer.
- Captures et image de présentation : à ajouter dans `graphics/` (non fournies).

## Formulaire « Sécurité des données »

Proposition, à valider (la décision t'appartient : Google compte comme « collectée » toute donnée que l'appli fait sortir de l'appareil, même vers un tiers).

- Données collectées : **oui**, une seule catégorie, à cause de la recherche ARASAAC :
  - « Activité dans les applications » > « Historique des recherches dans l'application » : le mot cherché part vers ARASAAC ;
  - collectée, **non partagée** : exception « transfert à la suite d'une action de l'utilisateur, qui s'attend à ce qu'elle soit transmise » (l'adulte tape un mot et lance la recherche dans la banque ARASAAC) ;
  - traitement éphémère : non revendiqué (l'appli ne garde rien, mais ce qu'ARASAAC fait de ses journaux ne dépend pas d'elle) ;
  - collecte facultative (la recherche n'est pas nécessaire pour utiliser l'appli) ; finalité : « Fonctionnalité de l'application ».
- Chiffrement en transit : **oui** (HTTPS).
- Suppression des données à la demande : l'éditeur ne reçoit et ne garde rien, il n'y a donc pas de mécanisme de demande ; répondre selon ce que propose le formulaire (les données locales s'effacent en désinstallant l'appli).
- Bibliothèque de mise à jour Google Play (Play Core) : lire la page de Google sur ce qu'elle traite avant de remplir le formulaire, et l'ajouter si elle demande une déclaration.
- Photos, contacts, position, identifiants : **non** (les photos ne quittent l'appareil que dans un fichier de sauvegarde que l'adulte range lui-même).
- Sauvegarde automatique d'Android : faite par le système vers le compte de l'utilisateur, hors du formulaire.

## Appareils

- Tablettes et téléphones : sur téléphone, les Réglages proposent les moyennes (debout) et les grandes cases (même couché). La standard reste pensée pour la tablette.

## Public cible et Familles

- L'appli vise aussi des enfants : la section « Public cible et contenu » et les règles Familles s'appliquent. Aucune pub, aucun SDK tiers de collecte (invariant 1), contrôle parental devant l'édition, la recherche et les photos.
