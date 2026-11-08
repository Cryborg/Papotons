#!/usr/bin/env python3
"""Deux vérifications sur Google Play que le plugin Gradle ne sait pas faire.

    python3 play-api.py cle   --cle <json> --package <id>
    python3 play-api.py notes --cle <json> --package <id> --version-code <n>
                              --track <track> --notes-dir <dossier> [--apply]

Lancé par `deploy.sh` (brique deploiement de l'atelier), qui résout la clé, le
package et les notes du projet. Rien de propre à un jeu ici.

`cle` : la clé de service peut-elle publier CETTE app ? Il ouvre un BROUILLON
d'édition, lit les tracks, puis supprime le brouillon. Un brouillon jamais
validé (`edits.commit`) n'a aucun effet sur le store, et c'est la première
étape de toute publication : le test prouve les droits réels sans rien changer.
⚠️ Ne pas tester une clé avec une tâche du plugin : `bootstrapListing` passe par
des endpoints dépréciés et répond `PERMISSION_DENIED: Please migrate to the new
publishing API` quels que soient les droits, ce qui se lit comme un refus
d'identité et n'en est pas un. Les autres tâches, elles, publient pour de bon.

`notes` : relit les notes du versionCode sur le track et, avec `--apply`, les
remplace si ce ne sont pas celles de `--notes-dir` (`<locale>/default.txt`,
écrits par deploy.sh). Pourquoi : quand le plugin envoie une liste de notes
vide, Play hérite en silence de celles de la release précédente. Un versionCode
ne se renvoie pas, donc réécrire les notes est la seule réparation. Sans
`--apply`, le brouillon est jeté : rien n'est publié.

Sortie : une ligne `RÉPARÉ : <track>` quand des notes ont été remplacées.
Codes : 0 bon, 1 échec Play, 2 arguments, 3 dépendance absente.
N'affiche jamais la clé privée ni le jeton. Demande le module `cryptography`.
"""

import argparse
import base64
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
except ImportError:
    print("Module python `cryptography` absent : sudo apt install python3-cryptography")
    sys.exit(3)

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(info: dict) -> str:
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({
        "iss": info["client_email"],
        "scope": SCOPE,
        "aud": info["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode()
    key = serialization.load_pem_private_key(info["private_key"].encode(), password=None)
    signature = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": f"{header}.{claims}.{b64(signature)}",
    }).encode()
    with urllib.request.urlopen(info["token_uri"], body, timeout=60) as response:
        return json.load(response)["access_token"]


def call(method: str, url: str, token: str, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        request.add_header("Content-Type", "application/json; charset=UTF-8")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        try:
            detail = json.loads(raw)["error"]["message"]
        except (ValueError, KeyError, TypeError):
            detail = raw[:400]
        return error.code, {"error": detail}
    except urllib.error.URLError as error:
        return 0, {"error": f"réseau : {error.reason}"}


def open_session(key_path: str):
    path = pathlib.Path(key_path)
    if not path.is_file():
        print(f"Clé introuvable : {path}")
        sys.exit(1)
    info = json.loads(path.read_text(encoding="utf-8"))
    print(f"compte de service : {info.get('client_email')}")
    print(f"projet GCP        : {info.get('project_id')}")
    try:
        return access_token(info)
    except urllib.error.HTTPError as error:
        print("ÉCHEC à l'authentification, la clé elle-même est refusée :")
        print(error.read().decode(errors="replace")[:500])
    except urllib.error.URLError as error:
        print(f"ÉCHEC à l'authentification, réseau : {error.reason}")
    except (ValueError, KeyError) as error:
        print(f"Clé illisible ({type(error).__name__}) : ce n'est pas une clé de compte de service.")
    sys.exit(1)


def open_edit(package: str, token: str) -> str:
    status, edit = call("POST", f"{API}/{package}/edits", token)
    if status != 200:
        print(f"ouverture d'un brouillon : ÉCHEC ({status}) {edit.get('error')}")
        print("Un 403 : le compte de service n'a pas de droits sur CETTE app.")
        print("Play Console, Utilisateurs et autorisations : inviter son adresse,")
        print("rôle « Release manager », restreint à cette seule application.")
        print("Un 404 sur une app neuve : le premier AAB se dépose à la main.")
        sys.exit(1)
    return edit["id"]


def check_key(args) -> None:
    token = open_session(args.cle)
    print(f"application       : {args.package}")
    edit_id = open_edit(args.package, token)
    print("brouillon         : ouvert")
    try:
        status, tracks = call("GET", f"{API}/{args.package}/edits/{edit_id}/tracks", token)
        if status != 200:
            print(f"lecture des tracks : ÉCHEC ({status}) {tracks.get('error')}")
            sys.exit(1)
        for track in tracks.get("tracks", []):
            for release in track.get("releases", []):
                codes = ", ".join(release.get("versionCodes", []))
                if codes:
                    print(f"  {track['track']:<12} versionCode {codes} ({release.get('status')})")
    finally:
        call("DELETE", f"{API}/{args.package}/edits/{edit_id}", token)
    print("Rien n'a été publié : le brouillon a été supprimé.")


def read_wanted(notes_dir: str) -> dict:
    wanted = {}
    for file in sorted(pathlib.Path(notes_dir).glob("*/default.txt")):
        text = file.read_text(encoding="utf-8").strip()
        if text:
            wanted[file.parent.name] = text
    if not wanted:
        print(f"Aucune note sous {notes_dir}")
        sys.exit(2)
    return wanted


def check_notes(args) -> None:
    wanted = read_wanted(args.notes_dir)
    token = open_session(args.cle)
    print(f"application       : {args.package}, versionCode {args.version_code}, track {args.track}")
    print(f"mode              : {'écriture' if args.apply else 'lecture seule'}")
    edit_id = open_edit(args.package, token)
    url = f"{API}/{args.package}/edits/{edit_id}/tracks/{args.track}"
    committed = False
    try:
        status, track = call("GET", url, token)
        if status != 200:
            print(f"lecture du track : ÉCHEC ({status}) {track.get('error')}")
            sys.exit(1)
        found = False
        stale = []
        for release in track.get("releases", []):
            if args.version_code not in [str(c) for c in release.get("versionCodes", [])]:
                continue
            found = True
            current = {n["language"]: n.get("text", "") for n in release.get("releaseNotes", [])}
            stale = [lang for lang, text in wanted.items() if current.get(lang, "").strip() != text]
            for lang in stale:
                print(f"--- {lang} en ligne ---")
                print(current.get(lang, "(aucune note)"))
                print(f"--- {lang} attendu ---")
                print(wanted[lang])
            notes = [n for n in release.get("releaseNotes", []) if n["language"] not in wanted]
            notes += [{"language": lang, "text": text} for lang, text in wanted.items()]
            release["releaseNotes"] = notes
        if not found:
            print(f"versionCode {args.version_code} absent du track {args.track}")
            sys.exit(1)
        if not stale:
            print(f"Notes à jour ({', '.join(wanted)}).")
            return
        if not args.apply:
            print(f"Notes à remplacer : {', '.join(stale)}. Relancer avec --apply.")
            return
        status, result = call("PUT", url, token, track)
        if status != 200:
            print(f"écriture : ÉCHEC ({status}) {result.get('error')}")
            sys.exit(1)
        status, result = call("POST", f"{API}/{args.package}/edits/{edit_id}:commit", token)
        if status != 200:
            print(f"validation : ÉCHEC ({status}) {result.get('error')}")
            sys.exit(1)
        committed = True
        print(f"RÉPARÉ : {args.track} ({', '.join(stale)})")
    finally:
        if not committed:
            call("DELETE", f"{API}/{args.package}/edits/{edit_id}", token)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    key = sub.add_parser("cle")
    key.add_argument("--cle", required=True)
    key.add_argument("--package", required=True)
    notes = sub.add_parser("notes")
    notes.add_argument("--cle", required=True)
    notes.add_argument("--package", required=True)
    notes.add_argument("--version-code", required=True)
    notes.add_argument("--track", required=True)
    notes.add_argument("--notes-dir", required=True)
    notes.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.command == "cle":
        check_key(args)
    else:
        check_notes(args)


if __name__ == "__main__":
    main()
