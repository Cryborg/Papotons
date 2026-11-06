# Papotons

Appli Android de Communication Améliorante et Alternative (CAA) : des grilles de pictogrammes qui parlent, pour les personnes qui ne s'expriment pas, ou pas assez, à l'oral.

- Pensée pour le français.
- 100 % gratuite : ni pub, ni achat intégré, ni compte.
- Hors ligne, aucune donnée collectée.

Le projet démarre : voir [docs/ETUDE.md](docs/ETUDE.md) pour le contexte et [docs/TODO.md](docs/TODO.md) pour la feuille de route.

## Licence

Le code de Papotons est sous licence [GPL-3.0](LICENSE) : libre de l'étudier, de le modifier et de le redistribuer, à condition que toute version dérivée reste sous la même licence.

## Pictogrammes

Les pictogrammes utilisés sont ceux d'ARASAAC.
Auteur des pictogrammes : Sergio Palao. Origine : ARASAAC (https://arasaac.org). Licence : CC BY-NC-SA 4.0. Propriété : Gouvernement d'Aragon (Espagne).

Les fichiers de `app/src/main/assets/pictograms/` sont sous cette licence CC BY-NC-SA 4.0, **pas** sous la GPL du code : aucun usage commercial. Ils sont choisis et téléchargés par `tools/arasaac/fetch_pictograms.py` (Python 3 et Pillow), lancé à la main ; l'appli elle-même ne télécharge rien.

## Compiler

```bash
bash .claude/scripts/gradle-queue.sh assembleDebug
```

Le script fait passer le build par la file d'attente Gradle du poste ; sur une autre machine, `./gradlew assembleDebug` suffit.
