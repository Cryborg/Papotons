# MVP-5 : politique de confidentialité et fiche Play

## Contrat et fichiers touchés

- `docs/confidentialite.html` : la politique, en français, servie gratuitement par GitHub Pages depuis `docs/` (dépôt public). Elle dit ce qui reste sur l'appareil (grilles, photos, pictos téléchargés, réglages), ce qui en sort et vers qui (recherche ARASAAC : le mot cherché, l'adresse IP, le nom du logiciel ; Google Play pour les mises à jour ; la sauvegarde Android faite par le système ; les fichiers `.obz` rangés par l'adulte), les enfants, les droits, le contact (tickets GitHub, pas d'adresse personnelle publiée sans accord).
- Modale « À propos » : un paragraphe de plus, `about_privacy`, avec l'adresse en texte (comme les crédits : rien dans cette modale ne fait sortir de l'appli, un enfant peut l'ouvrir).
- Fiche Play : `app/src/main/play/listings/fr-FR/{title,short-description,full-description}.txt` (format de Gradle Play Publisher, comme les jeux du studio), crédit ARASAAC complet en fin de description (invariant 5).
- `docs/regles/PUBLICATION.md` : ce qui se règle à la main (activer Pages, coller l'adresse dans la Play Console, proposition de réponses au formulaire « Sécurité des données », public cible).

Invariants : 1 (la page décrit exactement les sorties ; aucun lien cliquable ajouté dans l'appli), 5 (crédit sur la fiche et dans l'appli).

## Envisagé et écarté

- Lien cliquable dans « À propos » : ouvrirait le navigateur depuis une modale qu'un enfant peut ouvrir ; l'adulte tape l'adresse.
- Hébergement ailleurs (site perso, Google Sites) : Pages est gratuit et suit le dépôt.
- Remplir le formulaire « Sécurité des données » : il se remplit dans la Play Console, à la main ; la note en propose les réponses.

## Observabilité

Sans objet (aucun code qui échoue). La page affiche sa date de mise à jour.

## Retries et limites

Sans objet.

## Plan de test

- Lint et tests (une chaîne de plus, aucune parité de langue : français seulement).
- Fiche : titre ≤ 30, description courte ≤ 80, longue ≤ 4 000 caractères (vérifié par `wc -m`), aucun tiret cadratin.
- Page : affichée dans un navigateur, thème clair et sombre.
