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

## Compiler

```bash
bash .claude/scripts/gradle-queue.sh assembleDebug
```

Le script fait passer le build par la file d'attente Gradle du poste ; sur une autre machine, `./gradlew assembleDebug` suffit.
