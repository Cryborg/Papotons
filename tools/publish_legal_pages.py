# -*- coding: utf-8 -*-
"""Publie la page legale de Papotons, et rien d'autre.

La politique de confidentialite, que la fiche Play et la modale « A propos »
designent, rangee comme celles des autres projets du studio sous
`legal/papotons/` sur wobblebotstudio.com. La page vit ici, dans le depot qui
en repond ; le site l'heberge sans la posseder (brique `pages-legales` du
socle Android, qui explique aussi le compte OVH cherche par son nom dans
`config.env` et le transfert en SFTP).

    python3 tools/publish_legal_pages.py            # publie ce qui differe
    python3 tools/publish_legal_pages.py --dry-run  # se connecte, montre, n'envoie rien
    python3 tools/publish_legal_pages.py --all      # renvoie meme ce qui est identique

SFTP demande paramiko (paquet Ubuntu python3-paramiko).
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CONFIG = os.environ.get(
    "SITES_CONFIG", os.path.expanduser("~/projets/Mes sites/config.env"))

ACCOUNT = "Wobblebot"
REMOTE_ROOT = "/www"

# Les seuls fichiers que ce script sait publier : source locale -> nom distant.
FILES = [
    ("docs/confidentialite.html", "legal/papotons/privacy.html"),
]


def credentials():
    if not os.path.isfile(CONFIG):
        raise SystemExit(
            "config.env introuvable : %s\n"
            "Le passer par la variable SITES_CONFIG si les projets sont ailleurs."
            % CONFIG)
    with open(CONFIG, encoding="utf-8") as f:
        cfg = json.load(f)
    for entry in cfg["ftp"]:
        if ACCOUNT.lower() in entry["name"].lower():
            host = re.sub(r"^\w+://", "", entry["host"]).rstrip("/")
            return host, entry["username"], entry["password"]
    raise SystemExit("Compte '%s' absent de %s" % (ACCOUNT, CONFIG))


class Sftp:
    """Transport SFTP : tout passe par le port 22."""

    def __init__(self, host, user, password):
        import paramiko  # importe ici pour que --help marche sans lui

        self.transport = paramiko.Transport((host, 22))
        self.transport.connect(username=user, password=password)
        self.client = paramiko.SFTPClient.from_transport(self.transport)

    def resolve(self, base):
        """Le SFTP n'est pas chroote : /www n'existe pas, ~/www si."""
        base = base.rstrip("/")
        try:
            self.client.stat(base)
            return base
        except IOError:
            home = self.client.normalize(".").rstrip("/")
            return home + "/" + base.lstrip("/")

    def size(self, path):
        try:
            return self.client.stat(path).st_size
        except IOError:
            return None

    def put(self, local, remote):
        self.makedirs(remote.rsplit("/", 1)[0])
        self.client.put(local, remote)

    def makedirs(self, folder):
        """Les pages vivent dans legal/<jeu>/ : le dossier peut ne pas exister."""
        if self.size(folder) is not None:
            return
        self.makedirs(folder.rsplit("/", 1)[0])
        self.client.mkdir(folder)

    def close(self):
        self.client.close()
        self.transport.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dry-run", action="store_true",
                    help="se connecte, montre ce qui partirait, n'envoie rien")
    ap.add_argument("--all", action="store_true",
                    help="renvoyer meme les pages identiques a celles en ligne")
    args = ap.parse_args()

    for local_rel, _ in FILES:
        if not os.path.isfile(os.path.join(ROOT, local_rel)):
            raise SystemExit("MANQUANT : %s" % local_rel)

    host, user, password = credentials()
    print("cible %s:%s (sftp), compte %s" % (host, REMOTE_ROOT, ACCOUNT))

    target = Sftp(host, user, password)
    print("connecte")
    base = target.resolve(REMOTE_ROOT)
    print("racine distante :", base)

    sent = skipped = 0
    failed = []
    for local_rel, remote_name in FILES:
        local = os.path.join(ROOT, local_rel.replace("/", os.sep))
        remote = base + "/" + remote_name
        size = os.path.getsize(local)

        if not args.all and target.size(remote) == size:
            print("  %s : deja a jour (%d octets)" % (remote_name, size))
            skipped += 1
            continue

        if args.dry_run:
            print("  [dry-run] enverrait %s (%d octets)" % (remote_name, size))
            sent += 1
            continue

        target.put(local, remote)

        # On RELIT ce qui vient d'etre ecrit plutot que de croire le transfert.
        # Une page legale coupee en deux est pire qu'une page perimee : elle a
        # l'air a jour.
        remote_size = target.size(remote)
        if remote_size == size:
            print("  %s : publie (%d octets)" % (remote_name, size))
            sent += 1
        else:
            print("  %s : ECART, %d octets envoyes, %s relus"
                  % (remote_name, size, remote_size), file=sys.stderr)
            failed.append(remote_name)

    target.close()
    print("termine : %d %s, %d deja a jour"
          % (sent, "a envoyer" if args.dry_run else "publiee(s)", skipped))

    if failed:
        return 1

    # Le cache trompe le verificateur : au premier passage le navigateur ressert
    # la vieille page, et on croit que rien n'est parti.
    if sent and not args.dry_run:
        print("\nVerifier les pages SERVIES (le cache ment) :")
        for _, remote_name in FILES:
            print("   https://wobblebotstudio.com/%s?v=<n'importe quoi>" % remote_name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
