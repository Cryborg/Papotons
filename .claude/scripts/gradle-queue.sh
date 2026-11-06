#!/usr/bin/env bash
# Serialise les builds Gradle de TOUTE la machine : toutes les sessions Claude, les
# routines, tous les depots et tous leurs worktrees prennent la meme file. Version de
# reference de l'atelier (socle-android, brique file-gradle) : le projet la copie
# dans .claude/scripts/gradle-queue.sh, sans la modifier.
#
# Pourquoi : deux builds lances en meme temps saturent la machine (32 workers sur
# 16 threads) et se disputent le cache ~/.gradle et les dossiers build/. Et comme
# tous les projets du poste partagent UN daemon Gradle (meme wrapper, meme JDK,
# memes jvmargs), deux projets differents se genent autant que deux worktrees du
# meme : le second build ne trouve pas le daemon libre et en demarre un autre de
# plusieurs Go.
#
# Usage : bash .claude/scripts/gradle-queue.sh assembleDebug
#         bash .claude/scripts/gradle-queue.sh :app:testDebugUnitTest --stacktrace
#         bash .claude/scripts/gradle-queue.sh --stop   (attend la fin du build en cours)
#
# Variables : GRADLE_QUEUE_TIMEOUT (secondes d'attente max, defaut 10800 : un long
#             test d'un autre projet tient plus d'une heure, et d'autres builds
#             peuvent attendre devant)
# Sortie    : le code de gradlew ; 75 si le verrou n'est jamais venu (le build
#             n'a pas commence), 130 ou 143 si l'attente a ete interrompue.
#
# ⚠️ Format du verrou fige : le mod voisinage de l'atelier lit
# /tmp/claude-gradle-locks/*.lock/{project,tree,task,since}. Ne renomme ni le
# dossier ni ces fichiers.

set -euo pipefail

TIMEOUT="${GRADLE_QUEUE_TIMEOUT:-10800}"

# Un JAVA_HOME qui ne mene nulle part (JDK desinstalle ou mis a jour) fait
# refuser gradlew. Sans lui, le `java` du PATH.
if [ -n "${JAVA_HOME:-}" ] && [ ! -d "$JAVA_HOME" ]; then
  unset JAVA_HOME
fi

COMMON_DIR="$(git rev-parse --git-common-dir 2>/dev/null)" || {
  echo "gradle-queue: pas dans un depot git" >&2; exit 1
}
COMMON_DIR="$(cd "$COMMON_DIR" && pwd)"
# Le nom du depot et non du worktree : .../Jeu/.git donne Jeu,
# depuis le worktree de release comme depuis la copie principale.
if [ "$(basename "$COMMON_DIR")" = ".git" ]; then
  PROJECT="$(basename "$(dirname "$COMMON_DIR")")"
else
  PROJECT="$(basename "$COMMON_DIR" .git)"
fi

# Chemin fixe, sans $TMPDIR : une file machine doit etre la meme pour tous les
# processus, et TMPDIR change d'un environnement a l'autre (bac a sable, routine,
# macOS). Sous Git Bash, /tmp est le %TEMP% de l'utilisateur.
LOCKS_ROOT="/tmp/claude-gradle-locks"
LOCK_DIR="$LOCKS_ROOT/machine.lock"
mkdir -p "$LOCKS_ROOT"
# Sans droit d'ecriture, `mkdir` du verrou echouerait comme si la file etait tenue,
# et le build attendrait le delai entier pour rien.
[ -w "$LOCKS_ROOT" ] || { echo "gradle-queue: impossible d'ecrire dans $LOCKS_ROOT" >&2; exit 1; }

# `--show-toplevel` rend la racine du WORKTREE courant : un build lance depuis
# le worktree de release compile bien le worktree, avec son propre gradlew.
TOPLEVEL="$(git rev-parse --show-toplevel)"
# Tableau et non chaine : le repli porte DEUX mots, et un `$GRADLEW` non quote
# casserait sur un chemin a espaces. Le repli sert quand gradlew a perdu son bit
# d'execution (copie depuis Windows, index en 100644).
if [ -x "$TOPLEVEL/gradlew" ]; then
  GRADLEW=("$TOPLEVEL/gradlew")
else
  GRADLEW=(sh "$TOPLEVEL/gradlew")
fi

held=0
release() {
  if [ "$held" -eq 1 ]; then rm -rf "$LOCK_DIR" 2>/dev/null || true; fi
}
trap release EXIT
# Un signal doit SORTIR : un handler qui rend la main laisserait bash reprendre la
# boucle d'attente, et l'orphelin lancerait plus tard un build que personne n'attend.
# `exit` declenche le trap EXIT, qui rend le verrou s'il est tenu.
trap 'exit 130' INT
trap 'exit 143' TERM

# Qui tient la file, relu a chaque message : le titulaire change pendant l'attente.
holder() {
  local who task since
  who="$(cat "$LOCK_DIR/project" 2>/dev/null || echo '?')"
  task="$(cat "$LOCK_DIR/task" 2>/dev/null || true)"
  since="$(cat "$LOCK_DIR/since" 2>/dev/null || echo '?')"
  echo "$who${task:+ ($task)}, depuis $since"
}

# Un verrou dont le processus proprietaire est mort est recuperable.
stale_check() {
  local pid
  pid="$(cat "$LOCK_DIR/pid" 2>/dev/null || echo '')"
  if [ -z "$pid" ]; then
    # Sans PID depuis plus d'une minute : le proprietaire est mort entre le mkdir et
    # l'ecriture du PID (signal tombe avant `held=1`, kill -9), personne ne le rendra.
    # Ecrire le PID touche le dossier : un verrou vivant n'a pas un mtime aussi vieux.
    [ -n "$(find "$LOCK_DIR" -maxdepth 0 -mmin +1 2>/dev/null)" ] || return 1
    echo "gradle-queue: verrou sans PID depuis plus d'une minute, recuperation" >&2
    rm -rf "$LOCK_DIR" 2>/dev/null || true
    return 0
  fi
  if kill -0 "$pid" 2>/dev/null; then return 1; fi
  echo "gradle-queue: verrou orphelin (PID $pid mort), recuperation" >&2
  rm -rf "$LOCK_DIR" 2>/dev/null || true
  return 0
}

start=$(date +%s)
announced=0
while true; do
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    held=1
    # `project`, `tree` et `task` ne servent qu'a l'affichage : messages d'attente
    # des autres builds, bandeau du plugin voisinage.
    { echo "$$" > "$LOCK_DIR/pid"; date '+%Y-%m-%d %H:%M:%S' > "$LOCK_DIR/since"
      echo "$PROJECT" > "$LOCK_DIR/project"; basename "$TOPLEVEL" > "$LOCK_DIR/tree"
      echo "$*" > "$LOCK_DIR/task"; } 2>/dev/null || true
    break
  fi
  stale_check && continue
  now=$(date +%s); waited=$((now - start))
  if [ "$waited" -ge "$TIMEOUT" ]; then
    echo "gradle-queue: abandon apres ${TIMEOUT}s d'attente, la file est tenue par $(holder)." >&2
    echo "gradle-queue: si aucun build ne tourne et que le verrou est bloque a tort : rm -rf '$LOCK_DIR'" >&2
    exit 75
  fi
  if [ "$announced" -eq 0 ]; then
    echo "gradle-queue: un build Gradle tourne deja sur la machine : $(holder). Mise en file d'attente..." >&2
    announced=1
  elif [ $((waited % 300)) -lt 3 ]; then
    echo "gradle-queue: toujours en attente (${waited}s), file tenue par $(holder)..." >&2
  fi
  sleep 3
done

echo "gradle-queue: verrou acquis ($PROJECT), lancement du build" >&2
"${GRADLEW[@]}" "$@"
