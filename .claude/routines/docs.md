# Papotons : consignes `docs`

Lues par la tournée cloud (23 h) (programme : `~/.claude/scheduled-tasks/programme.md`). Le verrou, le temps, les lois communes, le passage et la forme du compte rendu sont dans `REGLES_COMMUNES.md`, la passe en général dans `passes/docs.md` ; ici, seulement ce qui est propre à Papotons. Écrites le 2026-11-07, à l'entrée du projet dans les routines.

Avant tout, lis le `CLAUDE.md` du dépôt (stack, architecture, conventions, invariants numérotés) : il l'emporte sur toute habitude prise sur un jeu. Papotons n'est PAS un jeu : c'est une appli de Communication Améliorante et Alternative, utilisée par des enfants qui ne parlent pas. Une case qui bouge ou une grille perdue, c'est la parole de l'enfant qui casse. La mémoire du projet est versionnée dans `.claude/memoire-auto/` (index : `MEMORY.md`).

LE PÉRIMÈTRE : `CLAUDE.md`, `docs/ETUDE.md`, `docs/regles/` (`INVARIANTS.md`, `PUBLICATION.md`), `docs/designs/`, `docs/TODO.md`, `docs/test-ferme/` (sauf `grilles/`, générées), les fiches de `.claude/`. Jamais de code. Jamais `docs/confidentialite.html` ni la fiche Play (`app/src/main/play/`) : ce qu'elles promettent engage la fiche « Sécurité des données », c'est Franck qui les publie. Si le code les a rendues fausses, dis-le dans « Pour toi ».

1. La plage : les commits depuis le dernier passage (premier passage : 7 jours).
2. Pour chaque commit qui change un comportement : la doc qui le décrit dit-elle encore vrai ? Un design de `docs/designs/` livré qui ne dit plus ce que fait l'appli se corrige ou se signale.
3. `docs/TODO.md` : une entrée que le code a livrée est SUPPRIMÉE (règle du dépôt : pas d'archive) ; une entrée qui dit le contraire d'une « Décision actée » plus récente se signale.
4. `CLAUDE.md` est chargé à chaque session et par chaque sous-agent : il ne grossit pas. Une précision qui manque va dans `docs/regles/` et le CLAUDE.md y renvoie. Un invariant ne se renumérote jamais ; un invariant retiré garde son numéro, marqué MORT.
5. Les chemins, classes et commandes cités par la doc existent encore (`grep`) : une référence morte se corrige vers le bon nom, ou la phrase part si la chose a disparu.
6. Aucun tiret cadratin dans un texte destiné à l'affichage cité par la doc (règle de Franck) ; la doc interne, elle, n'est pas concernée.
