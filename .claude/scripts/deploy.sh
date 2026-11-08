#!/usr/bin/env bash
# Publie le jeu sur Google Play. Lancé par /deploy (.claude/commands/deploy.md).
# Version de référence de l'atelier (socle-android, brique deploiement) : le projet
# la copie dans .claude/scripts/deploy.sh sans la modifier, et règle ce qui lui est
# propre dans .claude/deploy.conf (facultatif, modèle dans la brique).
#
#     bash .claude/scripts/deploy.sh precheck
#     bash .claude/scripts/deploy.sh release <versionName> [--dry-run] [--fichier <chemin>]...
#     bash .claude/scripts/deploy.sh promote <alpha|beta|production> [<versionName>]
#     bash .claude/scripts/deploy.sh notes [<track>] [--apply]
#     bash .claude/scripts/deploy.sh cle
#
# `release` enchaîne : notes vérifiées, version montée et commitée, worktree de
# release calé sur ce commit, tests et AAB dans le worktree, envoi en tests
# internes, relecture des notes en ligne, tag du commit publié, push.
# `--dry-run` s'arrête à l'AAB : aucun commit, aucun envoi, aucun tag.
# `promote` promeut la version taguée d'`internal` vers un autre track, sans
# renvoyer l'AAB. `notes` relit (et répare avec --apply) les notes en ligne.
# `cle` vérifie que la clé de service peut publier, sans rien publier.
#
# Pourquoi un worktree : le build de release lit la copie de travail. Une session
# voisine qui écrit pendant les minutes de R8 fait entrer son code non commité
# dans l'AAB, qui ne correspond alors à aucun commit et part sur un versionCode
# consommé pour toujours. La fenêtre dure jusqu'à la fin de l'envoi, car
# `publishReleaseBundle` dépend de `bundleRelease`. Tout ce qui construit ou
# publie se joue donc dans le worktree, jamais dans la copie partagée.
#
# Codes de sortie : 0 réussi ; 1 échec (le message dit ce qui est déjà fait) ;
# 75 la file Gradle n'a jamais rendu le verrou (rien n'a été construit).

set -euo pipefail

# --- Racines ----------------------------------------------------------------
#
# Toujours le dépôt PRINCIPAL, même si le script est lancé depuis le worktree :
# c'est là que vivent les secrets, le commit de version et le tag.
# `--path-format=absolute` : sans lui, `git rev-parse` mélange relatif et absolu
# selon le répertoire courant et `dirname` rend `.`.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN_GIT="$(git -C "$SCRIPT_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" ||
  { echo "ERREUR : pas dans un dépôt git" >&2; exit 1; }
MAIN="$(dirname "$MAIN_GIT")"
cd "$MAIN"

# --- Réglages par défaut, surchargés par .claude/deploy.conf ----------------

GRADLE_FILE="app/build.gradle.kts"
NOTES_FILE="docs/RELEASE_NOTES.md"
CHANGELOG_FILE="docs/CHANGELOG.md"
PLAY_NOTES_DIR="app/src/main/play/release-notes"
LOCALES="fr-FR en-US"
NOTES_MAX_CHARS=500
BRANCHE="main"
VERIFS="testDebugUnitTest"
PROMOTION_PAR_DEFAUT=""
KEYSTORE=""
SECRETS_EN_PLUS=()
BUILDCONFIG_REQUIS=()
PLAY_PROJET_GCP=""
NOM_AAB=""
WORKTREE="${DEPLOY_WORKTREE:-$(dirname "$MAIN")/$(basename "$MAIN")-release}"

# Étapes propres au projet. Le conf les redéfinit ; chacune tourne dans le
# worktree avec la version et le versionCode en arguments, et un code non nul
# arrête le déploiement.
deploy_avant_build() { :; }
deploy_apres_build() { :; }
deploy_avant_envoi() { :; }
deploy_apres_envoi() { :; }

CONF="$MAIN/.claude/deploy.conf"
if [ -f "$CONF" ]; then
  # shellcheck source=/dev/null
  source "$CONF"
fi

# --- Outils -----------------------------------------------------------------

fail() {
  echo "ERREUR : $*" >&2
  exit 1
}

# Valeur d'une clé dans un .properties. `tr -d '\r'` : un fichier venu de
# Windows finit ses lignes en CRLF, et un chemin qui finit par \r n'existe pas.
prop() {
  [ -f "$1" ] || return 0
  { grep -E "^[[:space:]]*$2[[:space:]]*=" "$1" || true; } | head -1 | cut -d= -f2- |
    tr -d '\r' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

gradle_value() { # fichier clé : `clé = "valeur"` ou `clé = 12`, première occurrence
  { grep -E "^[[:space:]]*$2[[:space:]]*=" "$1" || true; } | head -1 |
    sed -E "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*\"?([^\"[:space:]]*)\"?.*/\1/"
}

current_code() { gradle_value "${1:-$GRADLE_FILE}" versionCode; }
current_name() { gradle_value "${1:-$GRADLE_FILE}" versionName; }
package_name() { gradle_value "$GRADLE_FILE" applicationId; }

# Seuls les tags de version : un tag d'outillage n'en est pas un.
last_tag() { git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true; }

version_gt() { # vrai si $1 > $2
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

# Un chemin relatif du build se résout depuis app/ avec `file(...)` et depuis la
# racine avec `rootProject.file(...)`. On lit lequel le bloc utilise, au lieu de
# supposer : les deux formes existent dans les jeux.
gradle_base_for() { # motif de la ligne qui porte le chemin
  if { grep -A3 -E "$1" "$GRADLE_FILE" || true; } | grep -q 'rootProject\.file'; then
    echo "$MAIN"
  else
    echo "$MAIN/app"
  fi
}

resolve() { # base chemin
  case "$2" in
    /*) echo "$2" ;;
    *) realpath -m "$1/$2" ;;
  esac
}

play_key_path() {
  local value
  value="$(prop local.properties PLAY_PUBLISH_KEY)"
  if [ -n "$value" ]; then
    resolve "$(gradle_base_for 'serviceAccountCredentials')" "$value"
  else
    echo "$MAIN/play-publish-key.json"
  fi
}

keystore_path() {
  if [ -n "$KEYSTORE" ]; then
    resolve "$MAIN" "$KEYSTORE"
    return
  fi
  local value
  value="$(prop local.properties RELEASE_STORE_FILE)"
  if [ -n "$value" ]; then
    resolve "$(gradle_base_for 'storeFile')" "$value"
  else
    echo "$MAIN/release-keystore.jks"
  fi
}

key_project() { # project_id de la clé de service, sans l'afficher
  { grep -o '"project_id"[[:space:]]*:[[:space:]]*"[^"]*"' "$1" 2>/dev/null || true; } |
    head -1 | sed -E 's/.*"([^"]*)"$/\1/'
}

aab_name() {
  local name="$NOM_AAB"
  [ -n "$name" ] || name="$(package_name | awk -F. '{ print $NF }')"
  echo "${name:-app}-$1.aab"
}

# --- Notes de version ---------------------------------------------------------
#
# Source unique : $NOTES_FILE, une section `## v<version>` (ou `## <version>`)
# par version, un bloc `<xx-YY>` ... `</xx-YY>` par langue. Le titre se compare
# EN ENTIER : chercher la version comme sous-chaîne prenait `## v1.2.1` pour la 1.2.

notes_for() { # fichier version locale
  awk -v ver="$2" -v loc="$3" '
    { sub(/\r$/, "") }
    /^## / { if (in_v) exit; in_v = ($2 == "v" ver || $2 == ver); next }
    in_v && $0 ~ "^[[:space:]]*<" loc ">[[:space:]]*$" { in_l = 1; started = 0; next }
    in_v && $0 ~ "^[[:space:]]*</" loc ">[[:space:]]*$" { in_l = 0; next }
    in_v && in_l {
      if (!started && $0 ~ /^[[:space:]]*$/) next
      started = 1
      print
    }
  ' "$1"
}

# Refuse AVANT le build : découvrir des notes manquantes après six minutes de R8
# coûte un build, et Play refuse au-delà de 500 caractères.
check_notes() { # fichier version
  [ -f "$1" ] || fail "$1 manque"
  local locale notes chars
  for locale in $LOCALES; do
    notes="$(notes_for "$1" "$2" "$locale")"
    [ -n "$notes" ] || fail "pas de bloc <$locale> pour la v$2 dans $1"
    # ⚠️ La locale UTF-8 est OBLIGATOIRE : sans elle, `wc -m` compte des octets
    # comme `wc -c`, les accents et les emojis gonflent le total, et un
    # garde-fou qui crie au loup finit par ne plus être lu.
    chars="$(printf '%s' "$notes" | LC_ALL=C.UTF-8 wc -m | tr -d ' ')"
    [ "$chars" -le "$NOTES_MAX_CHARS" ] ||
      fail "notes $locale de la v$2 : $chars caractères, Play en accepte $NOTES_MAX_CHARS"
    if printf '%s' "$notes" | grep -q '—'; then
      fail "notes $locale de la v$2 : tiret cadratin interdit dans un texte affiché"
    fi
    echo "Notes $locale : $chars caractères"
  done
}

write_play_notes() { # racine version
  local locale dir
  rm -rf "${1:?}/$PLAY_NOTES_DIR"
  for locale in $LOCALES; do
    dir="$1/$PLAY_NOTES_DIR/$locale"
    mkdir -p "$dir"
    notes_for "$1/$NOTES_FILE" "$2" "$locale" > "$dir/default.txt"
    # Un fichier vide ferait hériter Play des notes de la release précédente.
    [ -s "$dir/default.txt" ] || fail "notes $locale de la v$2 absentes de $1/$NOTES_FILE"
  done
}

# --- Version ------------------------------------------------------------------

set_version() { # fichier versionName versionCode
  sed -i -E "s/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1$3/" "$1"
  sed -i -E "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*)\"[^\"]*\"/\1\"$2\"/" "$1"
  # Relire : un `sed` qui ne trouve pas sa ligne ne dit rien.
  if [ "$(current_name "$1")" != "$2" ] || [ "$(current_code "$1")" != "$3" ]; then
    fail "$1 ne porte pas versionName=$2 / versionCode=$3 après la mise à jour"
  fi
}

# --- Worktree de release ------------------------------------------------------

copy_into_worktree() { # chemin absolu d'un fichier ou dossier du dépôt principal
  local abs="$1" rel
  [ -e "$abs" ] || fail "absent : $abs. Sans lui le build tomberait, ou partirait sur un défaut."
  case "$abs" in
    "$MAIN"/*) rel="${abs#"$MAIN"/}" ;;
    *) echo "  hors du dépôt, lu sur place : $abs"; return 0 ;;
  esac
  # Suivi par git : le checkout l'a déjà apporté.
  if git ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then return 0; fi
  mkdir -p "$(dirname "$WORKTREE/$rel")"
  if [ -d "$abs" ]; then
    # Un dossier (dépendances installées, par exemple) se lie au lieu de se copier.
    [ -L "$WORKTREE/$rel" ] || [ ! -e "$WORKTREE/$rel" ] ||
      fail "$WORKTREE/$rel existe en vrai dossier : le retirer à la main"
    ln -sfn "$abs" "$WORKTREE/$rel"
  else
    cp -p "$abs" "$WORKTREE/$rel"
  fi
  echo "  copié : $rel"
}

prepare_worktree() { # sha
  local sha="$1" wt_common
  echo "--- WORKTREE DE RELEASE : $WORKTREE ---"
  # Le test porte sur les métadonnées git, pas sur la présence du dossier : les
  # deux divergent (prune passé, dossier survivant) et chaque cas échoue
  # autrement, avec un message qui n'aide pas.
  if [ -e "$WORKTREE/.git" ]; then
    wt_common="$(git -C "$WORKTREE" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    [ "$wt_common" = "$MAIN_GIT" ] ||
      fail "$WORKTREE existe mais n'est pas un worktree de ce dépôt. Le supprimer à la main, git worktree prune, relancer."
    # `--force` vise CE worktree, à nous seuls : il garde les fichiers non suivis
    # (secrets, build/) et efface ce qu'un essai à blanc y a modifié.
    git -C "$WORKTREE" checkout --quiet --detach --force "$sha"
  elif [ -e "$WORKTREE" ]; then
    fail "$WORKTREE existe et n'est pas un worktree. Le supprimer à la main, relancer."
  else
    git worktree prune
    git worktree add --quiet --detach "$WORKTREE" "$sha"
  fi

  # Les secrets ne suivent pas le checkout. La clé Play surtout : aucune tâche
  # Gradle ne la vérifie, son absence ne se verrait qu'à l'envoi.
  copy_into_worktree "$MAIN/local.properties"
  copy_into_worktree "$(keystore_path)"
  local extra key
  for extra in ${SECRETS_EN_PLUS[@]+"${SECRETS_EN_PLUS[@]}"}; do
    copy_into_worktree "$(resolve "$MAIN" "$extra")"
  done
  key="$(play_key_path)"
  if [ -f "$key" ]; then copy_into_worktree "$key"; fi

  # Toute la promesse du worktree tient dans ces deux assertions : exactement le
  # commit demandé, sans aucune modification suivie. Un checkout interrompu
  # laisse un arbre mixte, et l'AAB ne correspondrait à aucun commit.
  [ "$(git -C "$WORKTREE" rev-parse HEAD)" = "$sha" ] ||
    fail "le worktree n'est pas sur $sha (checkout partiel ?)"
  [ -z "$(git -C "$WORKTREE" status --porcelain --untracked-files=no)" ] ||
    fail "le worktree porte des modifications suivies : l'AAB ne correspondrait à aucun commit"
  echo "  HEAD = $sha, arbre propre"
}

# Par la file Gradle quand le projet l'a : le hook qui l'impose ne voit que les
# commandes de l'agent, pas celles lancées DANS un script. Le `cd` compte :
# gradle-queue.sh résout sa racine depuis le répertoire courant, et compilerait
# sinon le dépôt principal. Pas de `--no-daemon` : le worktree a le même wrapper,
# le même JDK et les mêmes jvmargs, donc il réutilise le daemon commun au lieu
# d'en lancer un second de plusieurs Go.
run_gradle() {
  local status=0
  if [ -f "$WORKTREE/.claude/scripts/gradle-queue.sh" ]; then
    (cd "$WORKTREE" && bash .claude/scripts/gradle-queue.sh "$@" --console=plain) || status=$?
  else
    (cd "$WORKTREE" && sh ./gradlew "$@" --console=plain) || status=$?
  fi
  if [ "$status" -eq 75 ]; then
    echo "EN ATTENTE : un autre build a tenu le verrou de la file Gradle jusqu'au délai." >&2
    echo "Rien n'a été construit. Relancer plus tard." >&2
    exit 75
  fi
  return "$status"
}

run_hook() { # nom version code
  local status=0
  (cd "$WORKTREE" && "$1" "$2" "$3") || status=$?
  [ "$status" -eq 0 ] || fail "l'étape du projet $1 a échoué (code $status)"
}

# Un secret vide dans le BuildConfig casse en silence chez tous les joueurs (une
# connexion Google sans client web, par exemple). Le build ne le voit pas.
check_buildconfig() {
  [ "${#BUILDCONFIG_REQUIS[@]}" -gt 0 ] || return 0
  local file field value
  file="$(find "$WORKTREE/app/build/generated" -name BuildConfig.java -path '*release*' 2>/dev/null | head -1)"
  [ -n "$file" ] || fail "BuildConfig.java de release introuvable : impossible de vérifier ${BUILDCONFIG_REQUIS[*]}"
  for field in "${BUILDCONFIG_REQUIS[@]}"; do
    value="$({ grep -E "String[[:space:]]+$field[[:space:]]*=" "$file" || true; } | head -1 |
      sed -E 's/.*=[[:space:]]*"(.*)"[[:space:]]*;.*/\1/')"
    [ -n "$value" ] || fail "$field est VIDE dans le BuildConfig de release : ne pas publier cet AAB"
    echo "  $field : renseigné"
  done
}

# Relit sur Play les notes du versionCode et les répare si elles ne sont pas les
# nôtres. Quand le plugin reçoit une liste de notes vide, Play hérite en silence
# de celles de la release précédente : un binaire juste sous les notes d'une
# autre version, sans une ligne d'alerte. Un versionCode ne se renvoie pas, donc
# relire et réécrire est la seule réparation. Ne fait jamais échouer l'appelant :
# le binaire est déjà en ligne. Mais un échec se DIT.
verify_notes_online() { # racine-des-notes-générées versionCode track
  local output status=0
  echo "--- NOTES EN LIGNE ($3, versionCode $2) ---"
  output="$(python3 "$SCRIPT_DIR/play-api.py" notes --cle "$(play_key_path)" \
    --package "$(package_name)" --version-code "$2" --track "$3" \
    --notes-dir "$1/$PLAY_NOTES_DIR" --apply 2>&1)" || status=$?
  echo "$output"
  if [ "$status" -ne 0 ]; then
    echo "⚠️  Notes en ligne NON vérifiées (code $status). À relancer : bash .claude/scripts/deploy.sh notes $3 --apply"
  elif printf '%s' "$output" | grep -q '^RÉPARÉ'; then
    echo "⚠️  Play portait d'AUTRES notes que celles de cette version : réparées ci-dessus. Garder ce log."
  fi
}

# --- Sous-commandes -----------------------------------------------------------

precheck() {
  local tag name code key keystore commits
  tag="$(last_tag)"
  name="$(current_name)"
  code="$(current_code)"
  echo "=== PRÉ-VOL DU DÉPLOIEMENT ==="
  echo "Application : $(package_name)"
  echo "Version actuelle : ${name:-INTROUVABLE} (versionCode ${code:-INTROUVABLE})"
  echo "Dernier tag : ${tag:-aucun}"
  if [ -n "$tag" ] && [ "$tag" != "v$name" ]; then
    echo "INCOHÉRENCE : le dernier tag est $tag, le build porte v$name. STOP avant toute écriture."
  fi
  echo "Branche : $(git branch --show-current) (publication depuis $BRANCHE)"
  if git fetch --quiet origin "$BRANCHE" 2>/dev/null; then
    git merge-base --is-ancestor "origin/$BRANCHE" HEAD ||
      echo "RETARD : origin/$BRANCHE a des commits absents d'ici"
    echo "Commits non poussés : $(git rev-list --count "origin/$BRANCHE..HEAD")"
  else
    echo "origin/$BRANCHE injoignable : retard non vérifié"
  fi
  echo

  echo "--- SECRETS ---"
  key="$(play_key_path)"
  if [ -f "$key" ]; then
    echo "Clé Play : $key (projet GCP $(key_project "$key"))"
    if [ -n "$PLAY_PROJET_GCP" ] && [ "$(key_project "$key")" != "$PLAY_PROJET_GCP" ]; then
      echo "CLÉ ÉTRANGÈRE : projet $(key_project "$key"), attendu $PLAY_PROJET_GCP. Chaque jeu a son compte de service."
    fi
  else
    echo "Clé Play : ABSENTE ($key). Le script construira l'AAB, à déposer à la main."
  fi
  keystore="$(keystore_path)"
  echo "Keystore : $keystore ($([ -f "$keystore" ] && echo présente || echo ABSENTE))"
  echo "local.properties : $([ -f local.properties ] && echo présent || echo ABSENT)"
  if ! grep -q 'gradleProperty("promoteTrack")' "$GRADLE_FILE"; then
    echo "Promotion : le bloc play ne lit pas -PpromoteTrack, la promotion est refusée tant qu'il manque"
  fi
  echo

  echo "--- RÉGLAGES ---"
  echo "Notes : $NOTES_FILE, langues $LOCALES"
  echo "Promotion par défaut : ${PROMOTION_PAR_DEFAUT:-aucune (tests internes seuls)}"
  echo "Vérifications avant le bundle : ${VERIFS:-aucune}"
  echo "File Gradle : $([ -f .claude/scripts/gradle-queue.sh ] && echo présente || echo 'absente, ./gradlew direct')"
  echo "Worktree : $WORKTREE"
  if [ -f "$CONF" ]; then
    echo "Étapes du projet : $({ grep -oE '^deploy_(avant|apres)_[a-z]+' "$CONF" || true; } | tr '\n' ' ')"
  else
    echo "Pas de .claude/deploy.conf : réglages par défaut"
  fi
  echo

  echo "--- COPIE DE TRAVAIL ---"
  git status --short
  echo "(du diff ici ne part pas dans le binaire, construit dans le worktree ; il peut venir d'une session voisine)"
  echo

  commits="$(git log --format='%h %s' "${tag:+$tag..}HEAD")"
  echo "--- COMMITS DEPUIS ${tag:-le début} ---"
  echo "$commits"
  echo
  local type count visible=0
  for type in feat fix perf refactor docs test chore build; do
    count="$(printf '%s\n' "$commits" | grep -cE "^[0-9a-f]+ $type(\(.*\))?!?:" || true)"
    echo "$type : $count"
    case "$type" in feat|fix|perf) visible=$((visible + count)) ;; esac
  done
  echo "Commits pouvant toucher les joueurs (feat, fix, perf) : $visible"
  [ "$visible" -gt 0 ] || echo "RIEN À PUBLIER : aucun feat, fix ni perf depuis ${tag:-le début}"
  echo

  local major minor patch
  IFS=. read -r major minor patch <<< "${name:-0.0.0}"
  echo "--- VERSIONS CANDIDATES ---"
  echo "contenu jouable nouveau : $major.$((minor + 1)).0"
  echo "corrections seules : $major.$minor.$(( ${patch:-0} + 1 ))"
  echo "versionCode suivant : $(( ${code:-0} + 1 ))"
  echo "(un feat ne suffit pas à faire une mineure : voir les règles de /deploy)"
}

release() {
  local version="" dry=0 files=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) dry=1 ;;
      --fichier) [ $# -ge 2 ] || fail "--fichier attend un chemin"; files+=("$2"); shift ;;
      -*) fail "option inconnue : $1" ;;
      *) [ -z "$version" ] || fail "une seule version"; version="$1" ;;
    esac
    shift
  done
  [ -n "$version" ] || fail "usage : deploy.sh release <versionName> [--dry-run] [--fichier <chemin>]..."
  [[ "$version" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || fail "version illisible : $version (attendu X.Y ou X.Y.Z)"

  [ "$(git branch --show-current)" = "$BRANCHE" ] || fail "la publication part de $BRANCHE"
  ! git rev-parse --quiet --verify "refs/tags/v$version" >/dev/null ||
    fail "le tag v$version existe déjà. S'il n'est pas poussé : git push origin refs/tags/v$version"
  git fetch --quiet origin "$BRANCHE"
  git merge-base --is-ancestor "origin/$BRANCHE" HEAD ||
    fail "origin/$BRANCHE a avancé : mettre $BRANCHE à jour d'abord"

  local name code tag
  name="$(current_name)"
  code="$(current_code)"
  [[ "$code" =~ ^[0-9]+$ ]] || fail "versionCode illisible dans $GRADLE_FILE (il doit être un entier littéral)"
  tag="$(last_tag)"
  if [ -n "$tag" ] && ! version_gt "$version" "${tag#v}"; then
    fail "la v$version ne dépasse pas le dernier tag $tag"
  fi
  check_notes "$NOTES_FILE" "$version"
  # Une relance après un échec garde sa version : le commit est déjà fait, et
  # remonter le versionCode en brûlerait un pour rien.
  if [ "$name" != "$version" ]; then code=$((code + 1)); fi
  # Jamais sous le versionCode déjà publié : un versionName monté à la main sans
  # son versionCode le réutiliserait, et Play le refuserait après le build.
  local tag_code=""
  if [ -n "$tag" ]; then
    tag_code="$(git show "$tag:$GRADLE_FILE" 2>/dev/null | gradle_value /dev/stdin versionCode || true)"
  fi
  if [[ "$tag_code" =~ ^[0-9]+$ ]] && [ "$code" -le "$tag_code" ]; then code=$((tag_code + 1)); fi

  local key manual=0
  key="$(play_key_path)"
  [ -f "$key" ] || manual=1
  if [ "$manual" -eq 0 ] && [ -n "$PLAY_PROJET_GCP" ] && [ "$(key_project "$key")" != "$PLAY_PROJET_GCP" ]; then
    fail "la clé Play $key appartient au projet GCP $(key_project "$key"), pas à $PLAY_PROJET_GCP"
  fi

  local sha
  if [ "$dry" -eq 0 ]; then
    git diff --quiet HEAD -- "$GRADLE_FILE" ||
      fail "$GRADLE_FILE a des changements non commités (session voisine ?)"
    set_version "$GRADLE_FILE" "$version" "$code"
    local commit_files=("$GRADLE_FILE" "$NOTES_FILE") f
    [ -f "$CHANGELOG_FILE" ] && commit_files+=("$CHANGELOG_FILE")
    for f in ${files[@]+"${files[@]}"}; do
      [ -e "$f" ] || fail "fichier de la release introuvable : $f"
      commit_files+=("$f")
    done
    # Fichier par fichier, et le commit restreint à ces chemins : ce qu'une
    # session voisine a mis dans l'index n'y entre pas.
    git add -- "${commit_files[@]}"
    if ! git diff --cached --quiet HEAD -- "${commit_files[@]}"; then
      git commit --quiet -m "chore(release): v$version ($code)" -- "${commit_files[@]}"
    fi
  fi
  sha="$(git rev-parse HEAD)"
  echo "Version $version (versionCode $code), commit ${sha:0:9}"
  echo

  prepare_worktree "$sha"
  if [ "$dry" -eq 1 ]; then
    # À blanc, rien n'est commité : la version et les notes du disque entrent
    # dans le worktree après ses assertions, pour un AAB jetable.
    set_version "$WORKTREE/$GRADLE_FILE" "$version" "$code"
    cp "$NOTES_FILE" "$WORKTREE/$NOTES_FILE"
  fi
  write_play_notes "$WORKTREE" "$version"
  echo

  run_hook deploy_avant_build "$version" "$code"
  echo "--- BUILD : ${VERIFS:+$VERIFS }bundleRelease ---"
  local marker aab_src aab
  marker="$(mktemp)"
  # shellcheck disable=SC2086 # VERIFS est une liste de tâches
  run_gradle $VERIFS bundleRelease || fail "le build a échoué. Rien n'est envoyé ni tagué ; le commit de version reste, relancer avec la même version le reprend."
  # Le worktree garde ses build/ d'une version à l'autre : seul un AAB plus récent
  # que le lancement du build est celui de cette version.
  aab_src="$(find "$WORKTREE/app/build/outputs/bundle/release" -name '*.aab' -newer "$marker" 2>/dev/null | head -1)"
  rm -f "$marker"
  [ -n "$aab_src" ] || fail "build vert mais aucun AAB neuf sous app/build/outputs/bundle/release"
  mkdir -p "$MAIN/build/release"
  aab="$MAIN/build/release/$(aab_name "$version")"
  cp "$aab_src" "$aab"
  echo "AAB : $aab ($(du -h "$aab" | cut -f1))"
  check_buildconfig
  run_hook deploy_apres_build "$version" "$code"
  echo

  if [ "$dry" -eq 1 ]; then
    echo "Essai à blanc terminé : rien n'est commité, envoyé ni tagué."
    return 0
  fi

  if [ "$manual" -eq 0 ]; then
    run_hook deploy_avant_envoi "$version" "$code"
    echo "--- ENVOI EN TESTS INTERNES ---"
    run_gradle publishReleaseBundle || fail "l'envoi a échoué, rien n'est tagué. Un 403 : le compte de service n'a pas les droits sur l'app dans la Play Console. « Version code already used » : un envoi précédent de ce versionCode est passé, vérifier la Play Console avant tout."
    verify_notes_online "$WORKTREE" "$code" internal
  fi

  # Le SHA publié, jamais HEAD : la copie partagée a pu avancer pendant le build.
  git tag "v$version" "$sha"
  if ! git push --quiet origin "$sha:refs/heads/$BRANCHE" "refs/tags/v$version"; then
    echo "⚠️  Push refusé. Le tag v$version est posé en local. Pour finir :" >&2
    echo "    git push origin $sha:refs/heads/$BRANCHE refs/tags/v$version" >&2
    exit 1
  fi
  run_hook deploy_apres_envoi "$version" "$code"
  echo

  if [ "$manual" -eq 1 ]; then
    echo "PAS DE CLÉ PLAY : déposer $aab à la main dans la Play Console (tests internes)."
    echo "Le tag v$version ne prouve donc pas la publication."
  else
    echo "v$version ($code) en tests internes, tag v$version poussé."
  fi
}

promote() {
  local track="${1:-}" version="${2:-}"
  case "$track" in
    alpha|beta|production) ;;
    *) fail "usage : deploy.sh promote <alpha|beta|production> [<versionName>]" ;;
  esac
  [ -f "$(play_key_path)" ] || fail "pas de clé Play : promouvoir à la main dans la Play Console"
  [ -n "$version" ] || version="$(last_tag)"
  version="${version#v}"
  [ -n "$version" ] || fail "aucun tag de version à promouvoir"
  local sha code
  sha="$(git rev-parse --verify --quiet "refs/tags/v$version^{commit}")" ||
    fail "pas de tag v$version : on ne promeut que ce qui a été envoyé et tagué"

  # La version, le versionCode et les notes se lisent DANS le commit publié.
  prepare_worktree "$sha"
  # Sans la propriété, `-PpromoteTrack` est ignoré et la promotion partirait vers
  # le track par défaut sans rien dire. Lu dans le commit publié, qui est ce qui tourne.
  grep -q 'gradleProperty("promoteTrack")' "$WORKTREE/$GRADLE_FILE" ||
    fail "le bloc play de la v$version ne lit pas -PpromoteTrack (voir le LISEZMOI de la brique deploiement)"
  code="$(current_code "$WORKTREE/$GRADLE_FILE")"
  check_notes "$WORKTREE/$NOTES_FILE" "$version"
  write_play_notes "$WORKTREE" "$version"
  echo
  echo "--- PROMOTION v$version ($code) : internal vers $track ---"
  # Le track est une PROPRIÉTÉ, jamais une ligne injectée dans build.gradle.kts :
  # une interruption laissait la ligne dans l'arbre partagé, et un `sed` accroché
  # à une chaîne exacte devenait muet au premier reformatage du bloc.
  run_gradle promoteReleaseArtifact "-PpromoteTrack=$track" ||
    fail "la promotion a échoué. Causes connues : droit « Release manager » absent, rien en internal pour ce versionCode."
  verify_notes_online "$WORKTREE" "$code" "$track"
  echo "v$version promue en $track."
}

notes_cmd() {
  local track="internal" apply=() version code
  while [ $# -gt 0 ]; do
    case "$1" in
      --apply) apply=(--apply) ;;
      -*) fail "option inconnue : $1" ;;
      *) track="$1" ;;
    esac
    shift
  done
  version="$(last_tag)"
  version="${version#v}"
  [ -n "$version" ] || fail "aucun tag de version"
  code="$(git show "v$version:$GRADLE_FILE" | gradle_value /dev/stdin versionCode)"
  NOTES_TMP="$(mktemp -d)"
  trap 'rm -rf "$NOTES_TMP"' EXIT
  mkdir -p "$NOTES_TMP/$(dirname "$NOTES_FILE")"
  # Les notes du commit publié, pas celles du disque.
  git show "v$version:$NOTES_FILE" > "$NOTES_TMP/$NOTES_FILE"
  check_notes "$NOTES_TMP/$NOTES_FILE" "$version"
  write_play_notes "$NOTES_TMP" "$version"
  python3 "$SCRIPT_DIR/play-api.py" notes --cle "$(play_key_path)" --package "$(package_name)" \
    --version-code "$code" --track "$track" --notes-dir "$NOTES_TMP/$PLAY_NOTES_DIR" ${apply[@]+"${apply[@]}"}
}

case "${1:-}" in
  precheck) precheck ;;
  release) shift; release "$@" ;;
  promote) shift; promote "$@" ;;
  notes) shift; notes_cmd "$@" ;;
  cle) python3 "$SCRIPT_DIR/play-api.py" cle --cle "$(play_key_path)" --package "$(package_name)" ;;
  *) fail "usage : deploy.sh precheck | release <version> [--dry-run] [--fichier <chemin>]... | promote <track> [<version>] | notes [<track>] [--apply] | cle" ;;
esac
