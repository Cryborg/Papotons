# Test fermé de Papotons (MVP-6)

Matériel pour faire valider l'appli, et surtout son vocabulaire de base, par des orthophonistes et des familles **avant toute publication publique**. Une fois l'appli installée chez quelqu'un, la disposition de l'accueil devient son plan moteur : elle ne bougera plus (invariant 3). C'est donc maintenant ou jamais pour la corriger.

Ce dossier :

- `grilles/` : chaque grille du vocabulaire de base à sa vraie disposition (couleurs de catégorie, emplacements libres en gris, dossiers marqués d'une icône), à joindre aux invitations ou à imprimer. Régénérer après un changement : `python3 -I tools/arasaac/fetch_pictograms.py boards --out docs/test-ferme/grilles` ;
- `invitation.md` : un message à adapter et à envoyer ;
- `questionnaire.md` : les questions à poser, à recopier dans un formulaire (ou à poser de vive voix).

## Ce qu'on cherche à savoir

1. **La disposition de l'accueil** : les colonnes (pronoms, verbes d'intention, actions, petits mots, qualificatifs, mots sociaux et questions, dossiers) sont-elles dans un ordre qui aide ? Un mot est-il mal placé ?
2. **Les mots manquants** déjà repérés, et leur place : `c'est`, `on`, `moi`, `là`, `mettre`, `ouvrir`, `qui ?`. La ligne du bas de l'accueil est libre pour eux ; un emplacement pris par une case de l'utilisateur ne pourra plus servir à un mot ajouté plus tard.
3. **Les pictos** : un picto est-il mal compris, ambigu, inadapté à un enfant ?
4. **Les dossiers** : thèmes et contenus utiles ? Lesquels manquent ?
5. **L'édition par l'adulte** : ajouter une case, une photo, chercher un picto, sauvegarder : est-ce faisable sans aide ?
6. **La voix** et la phrase : compréhensible, assez rapide, « j'ai » plutôt que « je ai » ?
7. **Les tailles des cases** (Réglages > Écrans, à la création d'un écran) : grandes cases (3 × 4), moyennes (5 × 6), standard (7 × 8). Les mots retenus pour les deux petites tailles sont-ils les bons ? Leurs grilles sont dans `grilles/` (07 à 14).

## Qui inviter

- Orthophonistes qui pratiquent la CAA (ISAAC Francophone, réseaux régionaux, cabinets connus).
- Familles et aidants (associations autour de l'autisme, du polyhandicap, de la paralysie cérébrale, des troubles du langage).
- Enseignants spécialisés (ULIS, IME).

## Déroulé proposé

1. Envoyer `invitation.md` avec les images de `grilles/`.
2. Ajouter les volontaires comme testeurs dans la Play Console (Tests fermés : liste d'adresses e-mail, ou un groupe Google), puis leur envoyer le lien d'inscription.
3. Tablette ou téléphone : sur téléphone, proposer les moyennes (debout) ou les grandes cases.
4. Deux à quatre semaines d'usage, puis le questionnaire.
5. Reporter les retours dans `docs/TODO.md` : un mot à ajouter va dans la ligne libre de l'accueil ou dans un dossier ; un mot à déplacer n'est possible que tant que rien n'est publié (`tools/arasaac/fetch_pictograms.py`, voir sa documentation sur l'instantané des positions).

À vérifier dans la Play Console : un compte développeur personnel récent doit faire tourner un test fermé avec un minimum de testeurs inscrits (12, pendant 14 jours d'affilée, selon les règles de 2023) avant de pouvoir publier en production.

## Avant d'envoyer

- Coller l'adresse de la politique de confidentialité dans la Play Console (`docs/regles/PUBLICATION.md`).
- Remplir le formulaire « Sécurité des données » et la section « Public cible ».
- Vérifier sur une vraie tablette qu'une sauvegarde `.obz` s'enregistre et se restaure : un testeur qui change d'appareil ne doit rien perdre.
