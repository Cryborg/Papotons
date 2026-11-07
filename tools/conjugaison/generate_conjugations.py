#!/usr/bin/env python3
"""Génère la table de conjugaison de Papotons (app/src/main/assets/conjugations.tsv) à partir de
la liste des verbes (tools/conjugaison/verbs.txt). Le fichier produit est versionné tel quel et
jamais édité à la main.

Formes produites par verbecc 2.0.3 (LGPL-3.0, gabarits issus de Verbiste), à installer dans un
venv à part : il ne sert qu'ici, sur le poste, jamais dans l'appli (invariant 1).

Usage :
  python3 -m venv /tmp/venv-verbecc && /tmp/venv-verbecc/bin/pip install verbecc==2.0.3
  /tmp/venv-verbecc/bin/python -I tools/conjugaison/generate_conjugations.py

Format : une ligne par verbe, colonnes séparées par une tabulation : l'infinitif, puis chaque forme,
temps par temps (TENSES), personne par personne (PERSONS). Formes sans pronom (« allais »),
passé composé au masculin (« suis allé »), futur proche = présent d'« aller » + infinitif.
L'ordre des colonnes est un contrat avec domain/Conjugation.kt : le changer casse l'appli.
"""
import logging
import os
import pathlib
import sys
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
VERBS = pathlib.Path(__file__).resolve().parent / "verbs.txt"
OUTPUT = ROOT / "app/src/main/assets/conjugations.tsv"
FILE_MODE = 0o644

PERSONS = ["je", "tu", "il", "nous", "vous", "ils"]
INDICATIVE = "indicatif"
PRESENT, IMPERFECT, FUTURE, PAST_COMPOUND = "présent", "imparfait", "futur-simple", "passé-composé"
NEAR_FUTURE = "futur-proche"
# Dans l'ordre du temps qui passe, comme les colonnes de la page des formes.
TENSES = [PAST_COMPOUND, IMPERFECT, PRESENT, NEAR_FUTURE, FUTURE]
NEAR_FUTURE_AUXILIARY = "aller"
FEMININE = "f"


def read_verbs() -> list[str]:
    verbs = []
    for line in VERBS.read_text(encoding="utf-8").splitlines():
        verb = line.strip()
        if not verb or verb.startswith("#"):
            continue
        if verb in verbs:
            sys.exit(f"{VERBS.name} : « {verb} » en double")
        if " " in verb or "\t" in verb:
            sys.exit(f"{VERBS.name} : « {verb} » n'est pas un infinitif seul")
        verbs.append(verb)
    return verbs


def person_forms(indicative: dict, tense: str, verb: str) -> list[str]:
    """Les six formes d'un temps, au masculin quand le participe s'accorde."""
    forms = {}
    for entry in indicative[tense]:
        pronoun = str(entry.get("pr"))
        if pronoun in PERSONS and pronoun not in forms and str(entry.get("g")) != FEMININE:
            forms[pronoun] = entry["c"][0]
    missing = [person for person in PERSONS if person not in forms]
    if missing:
        sys.exit(f"« {verb} », {tense} : pas de forme pour {', '.join(missing)}")
    return [forms[person] for person in PERSONS]


def conjugate(conjugator, verb: str, auxiliary: list[str]) -> list[str]:
    data = conjugator.conjugate(verb, conjugate_pronouns=False).get_data()
    if data["verb"]["predicted"]:
        sys.exit(f"« {verb} » est inconnu de verbecc : ses formes seraient devinées")
    indicative = data["moods"][INDICATIVE]
    row = [verb]
    for tense in TENSES:
        if tense == NEAR_FUTURE:
            row += [f"{form} {verb}" for form in auxiliary]
        else:
            row += person_forms(indicative, tense, verb)
    for form in row:
        if not form or "\t" in form or "\n" in form:
            sys.exit(f"« {verb} » : forme vide ou mal formée ({form!r})")
    return row


def main() -> None:
    # verbecc écrit un verbecc.log dans le dossier courant dès son import : pas dans le dépôt.
    os.chdir(tempfile.gettempdir())
    from verbecc import CompleteConjugator, LangCodeISO639_1 as Lang

    # verbecc journalise chaque chargement en INFO : seul ce qui va mal nous intéresse.
    logging.getLogger("verbecc").setLevel(logging.WARNING)
    conjugator = CompleteConjugator(Lang.fr)
    aller = conjugator.conjugate(NEAR_FUTURE_AUXILIARY, conjugate_pronouns=False).get_data()
    auxiliary = person_forms(aller["moods"][INDICATIVE], PRESENT, NEAR_FUTURE_AUXILIARY)
    rows = [conjugate(conjugator, verb, auxiliary) for verb in read_verbs()]
    text = "".join("\t".join(row) + "\n" for row in rows)
    # Écrit à côté puis renommé : un arrêt en route ne laisse jamais une table à moitié écrite.
    descriptor, temporary = tempfile.mkstemp(dir=OUTPUT.parent, suffix=".tmp")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as file:
            file.write(text)
        os.chmod(temporary, FILE_MODE)
        os.replace(temporary, OUTPUT)
    except BaseException:
        # Un temporaire laissé dans assets/ partirait dans l'APK.
        os.unlink(temporary)
        raise
    print(f"{len(rows)} verbes écrits dans {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
