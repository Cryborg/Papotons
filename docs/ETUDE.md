# Étude préalable (6 novembre 2026)

Pourquoi Papotons existe, ce qu'il doit reprendre des applis de référence et les pièges propres au projet. Les décisions qui en découlent sont dans `TODO.md` § Décisions actées.

## Le constat

Proloquo2Go (AssistiveWare), la référence de la CAA, n'existe que sur iOS et Mac, et rien n'annonce de version Android. TD Snap (Tobii Dynavox) non plus. Sur Android :

| Appli | Points forts | Limites |
|---|---|---|
| Cboard | Open source (GPL-3), 40 langues, gratuite | Appli web dans un emballage Android, peu de grammaire |
| Avaz | Sérieuse, Android et iOS | Payante, propriétaire, français secondaire |
| LetMeTalk, Jellow, PicTalk... | Gratuites, ARASAAC | Basiques : grilles et synthèse vocale, sans moteur de langue |
| Lemma, PicPaul (françaises) | Pensées pour le français | iPad d'abord ; PicPaul sur Android en test fermé seulement |

Le manque : une appli native, solide, pensée pour le français (conjugaison, accords), avec un vocabulaire de base organisé comme celui de Proloquo2Go.

## Ce qui fait la force de Proloquo2Go

- **Vocabulaire de base et vocabulaire thématique** : quelques centaines de mots très fréquents (je, veux, encore, fini, pas...) toujours visibles, et des dossiers par thème.
- **Planification motrice** : un mot ne change jamais de place. L'utilisateur finit par taper de mémoire, sans regarder. Agrandir la grille ajoute des cases sans déplacer les anciennes.
- **Taille de grille évolutive**, de 4 à plus de 100 cases, avec des cases masquées qu'on dévoile.
- **Code couleur Fitzgerald** : une couleur par catégorie grammaticale.
- **Grammaire** : appui long sur un mot pour ses variantes (veux, voulais ; petit, petite, petits).
- **Barre de message** : la phrase se construit puis se prononce, ou chaque mot se prononce en direct.
- **Mode édition protégé**, pour qu'un enfant ne casse pas ses grilles.

## Les pièges

- **Licences des pictos** : ARASAAC est en CC BY-NC-SA, donc aucun usage commercial (ni prix, ni pub, ni achat intégré) sans autorisation écrite du gouvernement d'Aragon. Mulberry, OpenMoji et Tawasol (CC BY-SA) permettent l'usage commercial mais sont moins connus et pensés pour l'anglais. Les photos personnelles restent indispensables en plus.
- **Voix** : le moteur TTS d'Android fonctionne hors ligne et rend correctement le français, mais les voix d'enfant sont presque absentes. Laisser choisir n'importe quel moteur installé (Acapela, RHVoice...).
- **Grammaire française** : élisions (le arbre devient l'arbre), conjugaison selon le sujet, accords. Le plus gros chantier, en deuxième phase.
- **Fiabilité** : l'appli sert de voix. Hors ligne, aucun plantage, réaction instantanée, aucune pub, sauvegarde et restauration des grilles.
- **Verrouillage** : épinglage d'écran / lock task mode, pour ne pas sortir de l'appli par erreur.

## Play Store

- **Règles Familles** : l'appli sera utilisée par des enfants. Aucun SDK de pub ou d'analytics non certifié, politique de confidentialité obligatoire, fiche « Sécurité des données ». Hors ligne et sans collecte, ces formulaires restent simples.
- **Pas de promesse thérapeutique** dans la fiche (« soutien à la communication », jamais « traitement » ni « rééducation ») pour rester loin du statut de dispositif médical.
- **Matériel modeste** : tablettes d'entrée de gamme. Interface fluide, tablette d'abord, téléphone possible.
- **Pictos** : plus de 13 000 chez ARASAAC. Embarquer ceux du vocabulaire de base, télécharger les autres à la demande et les garder hors ligne.
- **Crédit ARASAAC** obligatoire dans l'appli et sur la fiche.
- **Validation** : test fermé avec des orthophonistes et des familles avant la sortie publique.

## Zéro coût, concrètement

- Aucun serveur, aucun compte : tout reste sur l'appareil.
- Partage des grilles entre appareils par fichiers `.obz` (Open Board Format, compatible Cboard et CoughDrop), via le partage Android ou le Drive de l'utilisateur.
- Sauvegarde automatique d'Android (gratuite mais plafonnée à 25 Mo) plus export manuel.
- Plantages suivis dans Android vitals, sans SDK.
- Politique de confidentialité en page statique hébergée gratuitement.

## Sources

- [Is Proloquo2Go Available On Android? (spokenaac.com)](https://spokenaac.com/is-proloquo-on-android/)
- [AAC apps comparison guide (specialneeds.com)](https://www.specialneeds.com/articles/assistive-tech/apps/aac-apps-for-nonverbal-children-a-complete-comparison-guide/)
- [Cboard (AlternativeTo)](https://alternativeto.net/software/cboard/about)
- [ARASAAC : conditions d'utilisation](https://aulaabierta.arasaac.org/en/terms-of-use)
- [Global Symbols : conditions](https://globalsymbols.com/about/terms-and-conditions?locale=en)
