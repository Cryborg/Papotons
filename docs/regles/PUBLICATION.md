# Publication sur le Play Store : ce qui se règle à la main

Ce que le code et le dépôt ne peuvent pas faire. À revoir avant chaque version qui change ce qui sort de l'appareil (invariant 1).

## Politique de confidentialité

- Source : `docs/confidentialite.html`, servie par GitHub Pages.
- Activer une fois : dépôt GitHub > Settings > Pages > Source « Deploy from a branch », branche `main`, dossier `/docs`. Adresse obtenue : `https://cryborg.github.io/Papotons/confidentialite.html` (celle que cite la modale « À propos », `about_privacy`).
- Play Console > Contenu de l'application > Règles de confidentialité : coller cette adresse.
- Le dossier `docs/` entier devient public sur Pages (le dépôt l'est déjà).
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

- Tant que le MVP-8 (taille des cases) n'est pas livré : limiter le catalogue d'appareils aux tablettes dans la Play Console (sur téléphone, les cases sont trop étroites).

## Public cible et Familles

- L'appli vise aussi des enfants : la section « Public cible et contenu » et les règles Familles s'appliquent. Aucune pub, aucun SDK tiers de collecte (invariant 1), contrôle parental devant l'édition, la recherche et les photos.
