#!/usr/bin/env bash
# Scan mécanique commun des projets Kotlin / Jetpack Compose (brique de l'atelier,
# socle-android:briques/scan-mecanique). Phase 0 des audits et des revues.
#
# Détecte au grep les violations des règles communes de la checklist
# socle-android:kotlin-compose, la parité i18n des locales, et produit une carte
# des domaines pour les agents qui suivent. Aucun LLM, aucun flake.
#
# Les règles propres au projet vivent dans tools/audit/rules-projet.sh, sourcé
# s'il existe (voir LISEZMOI.md de la brique et le modèle rules-projet.sh).
#
# Sortie : un document JSON sur stdout. Progression et erreurs sur stderr.
# Code de sortie : toujours 0 (c'est un rapport, pas un gate).
#
# Usage :
#   bash tools/audit/mechanical-scan.sh                  # JSON complet
#   bash tools/audit/mechanical-scan.sh --pretty         # résumé lisible
#   bash tools/audit/mechanical-scan.sh [--pretty] <dossier-du-projet>
#
# Le projet est, dans l'ordre : le dossier passé en paramètre ; sinon le projet
# qui contient ce script quand il est rangé dans <projet>/tools/audit/ ; sinon la
# racine git du répertoire courant (ou le répertoire courant).
#
# Dépendances : bash 4+, grep (GNU), awk POSIX (mawk suffit), sed, find, comm.
# Les scripts awk n'utilisent que des classes POSIX ([[:space:]]) : mawk, l'awk par
# défaut d'Ubuntu, ne connaît ni \s ni \b, et les prenait pour des lettres.

set -u
# `producteur | add_violations_from ...` : le dernier maillon d'un pipeline tourne
# dans le shell courant, sinon les violations ajoutées se perdraient dans un
# sous-shell (le contrôle de tâches est coupé dans un script, lastpipe s'applique).
shopt -s lastpipe
# Tri et comparaison octet par octet : en locale fr_FR, sort ignore les « _ » et
# comm voit alors des clés en trop qui n'en sont pas (world_unlock_x / worlds_...).
export LC_ALL=C

PRETTY=0
ROOT_ARG=""
for arg in "$@"; do
  case "$arg" in
    --pretty) PRETTY=1 ;;
    -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "[mechanical-scan] option inconnue ignorée : $arg" >&2 ;;
    *) ROOT_ARG="$arg" ;;
  esac
done

# ----- Racine du projet -----
is_gradle_root() { [[ -f "$1/settings.gradle.kts" || -f "$1/settings.gradle" ]]; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "$ROOT_ARG" ]]; then
  ROOT="$(cd "$ROOT_ARG" 2>/dev/null && pwd)" || { echo "ERROR: dossier introuvable : $ROOT_ARG" >&2; exit 0; }
elif [[ "$(basename "$SCRIPT_DIR")" == "audit" && "$(basename "$(dirname "$SCRIPT_DIR")")" == "tools" ]] \
     && is_gradle_root "$SCRIPT_DIR/../.."; then
  ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
else
  ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
if ! is_gradle_root "$ROOT"; then
  echo "ERROR: pas un projet Gradle (ni settings.gradle.kts ni settings.gradle) : $ROOT" >&2
  exit 0
fi

# ----- Réglages du projet (surchargeables dans tools/audit/rules-projet.sh) -----
SCAN_MODULE="app"            # module Android scanné
SCAN_SRC=""                  # racine du package ; vide = déduite du namespace
SCAN_UI=""                   # dossier de l'interface ; vide = $SRC/ui s'il existe, sinon $SRC
SCAN_RES=""                  # dossier res/ ; vide = <module>/src/main/res
REGLES_DESACTIVEES=""        # préfixes de règles communes à ne pas lancer, ex. "R10 R21"
EXCLURE_TEXTE_EN_DUR='/theme/|/admin/|/debug/'   # chemins exclus de R11 (regex étendue)
LOCALES_IGNOREES=""          # dossiers values-* hors parité, ex. "values-fr"
LOCALES_ATTENDUES=""         # dossiers values-* qui DOIVENT exister (sinon status missing_file)
CLAUDE_MD_MAX_BYTES=""       # R18 ne tourne que si ce seuil est posé
CLAUDE_MD_TARGET_BYTES=""
SEUIL_DOMAINE=60             # au-delà de N fichiers .kt, un dossier de la carte se découpe
DOMAIN_MAP_JSON=""           # carte des domaines ; vide = déduite des dossiers
declare -A SEVERITE=()       # SEVERITE[R2]=important ou SEVERITE[R1_theme_raw_hex]=suggestion

# ----- Tampons et helpers (utilisables par rules-projet.sh) -----
VIOLATIONS=""
I18N_ISSUES=""
VIOLATION_COUNT=0

# Échappe une chaîne pour un littéral JSON.
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//	/\\t}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  printf '%s' "$s"
}

# Liste JSON de chaînes, une par ligne en entrée.
to_json_array() {
  local out="" k
  while IFS= read -r k; do
    [[ -z "$k" ]] && continue
    if [[ -z "$out" ]]; then out="\"$(json_escape "$k")\""; else out="$out,\"$(json_escape "$k")\""; fi
  done <<< "$1"
  printf '%s' "$out"
}

trim_match() { printf '%s' "$1" | sed 's/^[[:space:]]*//' | cut -c1-200; }

# add_violation <règle> <fichier> <ligne> <sévérité> <extrait>
# La sévérité se surcharge par SEVERITE[<règle complète>] puis SEVERITE[<préfixe>].
add_violation() {
  local rule="$1" file="$2" line="$3" severity="$4" match="$5"
  local prefix="${rule%%_*}"
  severity="${SEVERITE[$rule]:-${SEVERITE[$prefix]:-$severity}}"
  local rel="${file#"$ROOT"/}"
  local entry
  entry=$(printf '{"rule":"%s","file":"%s","line":%s,"severity":"%s","match":"%s"}' \
    "$rule" "$(json_escape "$rel")" "$line" "$severity" "$(json_escape "$match")")
  if [[ -z "$VIOLATIONS" ]]; then VIOLATIONS="$entry"; else VIOLATIONS="$VIOLATIONS,$entry"; fi
  VIOLATION_COUNT=$((VIOLATION_COUNT + 1))
}

# Ajoute chaque ligne "fichier:ligne:extrait" lue sur stdin.
add_violations_from() {
  local rule="$1" severity="$2" line file rest lineno match
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    file="${line%%:*}"; rest="${line#*:}"; lineno="${rest%%:*}"; match="${rest#*:}"
    add_violation "$rule" "$file" "$lineno" "$severity" "$(trim_match "$match")"
  done
}

# scan_pattern <règle> <sévérité> <regex étendue> <racine> [chemins exclus (regex étendue)] [glob inclus]
scan_pattern() {
  local rule="$1" severity="$2" pattern="$3" root="$4" exclude="${5:-}" include="${6:-*.kt}"
  [[ -e "$root" ]] || return 0
  local results
  results=$(grep -rEn --include="$include" -e "$pattern" "$root" 2>/dev/null || true)
  if [[ -n "$exclude" ]]; then
    results=$(printf '%s\n' "$results" | awk -F: -v ex="$exclude" '$1 !~ ex' || true)
  fi
  # Dans un .kt, une ligne de commentaire (//, /*, * de KDoc) n'est pas du code.
  if [[ "$include" == *.kt ]]; then
    results=$(printf '%s\n' "$results" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*)' || true)
  fi
  add_violations_from "$rule" "$severity" <<< "$results"
}

# Fichiers .kt d'un dossier (défaut : $SRC), triés.
kt_files() { find "${1:-$SRC}" -name '*.kt' -type f 2>/dev/null | sort; }

# Vrai si la règle commune n'est pas désactivée par REGLES_DESACTIVEES.
regle_active() {
  local r
  for r in $REGLES_DESACTIVEES; do [[ "$1" == "$r" ]] && return 1; done
  return 0
}

# ----- Extension du projet -----
# Sourcé au niveau global (pas dans une fonction) : ses affectations et son
# `declare -A` éventuel restent visibles par la suite du scan.
RULES_PROJET="$ROOT/tools/audit/rules-projet.sh"
if [[ -f "$RULES_PROJET" ]]; then
  # shellcheck source=/dev/null
  source "$RULES_PROJET"
  echo "[mechanical-scan] règles du projet : tools/audit/rules-projet.sh" >&2
fi

# ----- Dossiers -----
MODULE_DIR="$ROOT/$SCAN_MODULE"
abs_path() { [[ "$1" == /* ]] && printf '%s' "$1" || printf '%s' "$ROOT/$1"; }

if [[ -n "$SCAN_SRC" ]]; then
  SRC="$(abs_path "$SCAN_SRC")"
else
  SRC=""
  namespace=$(grep -hE '^[[:space:]]*namespace[[:space:]]*=?[[:space:]]*"[^"]+"' \
      "$MODULE_DIR/build.gradle.kts" "$MODULE_DIR/build.gradle" 2>/dev/null \
    | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
  if [[ -n "$namespace" ]]; then
    for lang in java kotlin; do
      candidate="$MODULE_DIR/src/main/$lang/${namespace//.//}"
      if [[ -d "$candidate" ]]; then SRC="$candidate"; break; fi
    done
  fi
  if [[ -z "$SRC" ]]; then
    # Pas de namespace exploitable : plus long préfixe commun des dossiers de .kt.
    SRC=$(find "$MODULE_DIR/src/main/java" "$MODULE_DIR/src/main/kotlin" -name '*.kt' -type f 2>/dev/null \
      | sed 's|/[^/]*$||' | sort -u | awk '
          NR == 1 { p = $0; next }
          { while (index($0 "/", p "/") != 1) { sub(/\/[^\/]*$/, "", p) } }
          END { print p }')
  fi
fi
if [[ -z "$SRC" || ! -d "$SRC" ]]; then
  echo "ERROR: dossier des sources introuvable (module $SCAN_MODULE). Pose SCAN_SRC dans tools/audit/rules-projet.sh." >&2
  exit 0
fi
if [[ -n "$SCAN_UI" ]]; then UI="$(abs_path "$SCAN_UI")"
elif [[ -d "$SRC/ui" ]]; then UI="$SRC/ui"
else UI="$SRC"; fi
if [[ -n "$SCAN_RES" ]]; then RES="$(abs_path "$SCAN_RES")"
else RES="$MODULE_DIR/src/main/res"; fi

echo "[mechanical-scan] sources : ${SRC#"$ROOT"/}" >&2
echo "[mechanical-scan] running text rules..." >&2

# ===================================================================
# Règles communes. Les numéros suivent ceux du plus ancien scan là où le sens est
# le même ; R7, R16 et R19 restent libres (règles propres à un projet sous ces numéros).
# ===================================================================

# R1 : couleur hors thème. Aucun littéral Color(0x...) ni Color.White/Black.copy(...)
# hors d'un dossier theme/ : tout passe par les jetons du thème.
if regle_active R1; then
  scan_pattern "R1_theme_raw_hex" "important" 'Color\(0x[0-9A-Fa-f]{6,8}\)' "$SRC" '/theme/'
  scan_pattern "R1_theme_color_white_copy" "important" 'Color\.White\.copy\(' "$SRC" '/theme/'
  scan_pattern "R1_theme_color_black_copy" "important" 'Color\.Black\.copy\(' "$SRC" '/theme/'
fi

# R2 : removeFirst()/removeLast() sur une MutableList plante sous Android 14 et
# avant (conflit stdlib Kotlin / JDK 21). Sur un ArrayDeque, un Deque ou une
# LinkedList ce sont des méthodes membres, donc sûres : écartées quand le receveur
# est déclaré de ce type dans le même fichier.
if regle_active R2; then
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    file="${line%%:*}"; rest="${line#*:}"; lineno="${rest%%:*}"; match="${rest#*:}"
    # Un seul receveur hors deque sur la ligne suffit à la signaler.
    unsafe=0; found=0
    while IFS= read -r receiver; do
      found=1
      if [[ -z "$receiver" ]] || ! grep -qE "\\b$receiver\\s*(:[^=]*)?=\\s*(kotlin\\.collections\\.|java\\.util\\.)?(ArrayDeque|LinkedList)\\b|\\b$receiver\\s*:\\s*(kotlin\\.collections\\.|java\\.util\\.)?(ArrayDeque|Deque|LinkedList)\\b" "$file"; then
        unsafe=1
      fi
    done < <(printf '%s' "$match" | grep -oE '([A-Za-z_][A-Za-z0-9_]*|[)\]])[?!]*\.remove(First|Last)\(\)' | sed -E 's/[?!]*\.remove(First|Last)\(\)$//; s/^[)\]]$//')
    [[ "$found" == 0 ]] && unsafe=1
    [[ "$unsafe" == 1 ]] && add_violation "R2_remove_first_last" "$file" "$lineno" "critical" "$(trim_match "$match")"
  done < <(grep -rEn --include='*.kt' '\.removeFirst\(\)|\.removeLast\(\)' "$SRC" 2>/dev/null)
fi

# R3 : sealed class au lieu de sealed interface.
regle_active R3 && scan_pattern "R3_sealed_class" "suggestion" \
  '^\s*((internal|public|private|protected)\s+)?sealed\s+class\s+' "$SRC"

# R4 : .values() sur un enum au lieu de .entries (heuristique : identifiant capitalisé).
regle_active R4 && scan_pattern "R4_enum_values_call" "suggestion" \
  '[A-Z][A-Za-z0-9_]+\.values\(\)' "$SRC"

# R5 : imports étoile.
regle_active R5 && scan_pattern "R5_wildcard_import" "suggestion" \
  '^import [a-zA-Z0-9_.]+\.\*$' "$SRC"

# R6 : collectAsState() sans cycle de vie (collecte en arrière-plan, batterie).
# Toujours collectAsStateWithLifecycle().
regle_active R6 && scan_pattern "R6_collect_as_state_no_lifecycle" "important" \
  '\.collectAsState\(' "$SRC"

# R8 : java.util.ArrayDeque au lieu de kotlin.collections.ArrayDeque.
regle_active R8 && scan_pattern "R8_java_util_arraydeque" "important" \
  'java\.util\.ArrayDeque\b' "$SRC"

# R9 : champ typé MutableList/Set/Map (heuristique : un état doit être immuable ;
# un accumulateur local ou privé d'une classe ordinaire est un faux positif).
regle_active R9 && scan_pattern "R9_mutable_collection_in_class" "important" \
  '\b(val|var)\s+\w+\s*:\s*Mutable(List|Set|Map)<' "$SRC"

# R10 : Toast natif Android là où le projet a son propre système de messages.
regle_active R10 && scan_pattern "R10_native_toast" "suggestion" \
  '\bToast\.makeText\b' "$SRC"

# R11 : texte en dur dans l'interface. Heuristique : Text("...") avec au moins une
# espace. Chemins exclus : EXCLURE_TEXTE_EN_DUR (thème, admin, debug par défaut).
regle_active R11 && scan_pattern "R11_hardcoded_text_string" "suggestion" \
  'Text\(\s*"[^"]*[a-zA-Z][^"]* [^"]*"' "$UI" "$EXCLURE_TEXTE_EN_DUR"

# R12 : launch {} dans une suspend fun (sortie de la concurrence structurée).
# Le corps est suivi par le compte des accolades depuis la ligne de déclaration ;
# un launch sous coroutineScope {} ou supervisorScope {} est écarté (parallélisme voulu).
if regle_active R12; then
  echo "[mechanical-scan] checking launch{} inside suspend fun..." >&2
  while IFS= read -r kfile; do
    awk '
      function braces(s,   o, c) { o = gsub(/\{/, "{", s); c = gsub(/\}/, "}", s); return o - c }
      /^[[:space:]]*((internal|private|public|protected|override|open|inline|operator|tailrec|actual)[[:space:]]+)*suspend[[:space:]]+fun[[:space:]]/ {
        in_s = 1; opened = 0; depth = 0; start = NR; scoped = 0
      }
      in_s && /^[[:space:]]*(\/\/|\/\*|\*)/ { next }
      in_s {
        if ($0 ~ /(coroutineScope|supervisorScope)[[:space:]]*\{/) scoped = 1
        # launch suivi de son bloc : écarte ActivityResultLauncher.launch(intent).
        if (NR > start && !scoped && $0 ~ /(^|[^A-Za-z0-9_])launch[[:space:]]*(\([^)]*\))?[[:space:]]*\{/) print FILENAME ":" NR ":" $0
        d = braces($0); depth += d
        if (depth > 0) opened = 1
        if (opened && depth <= 0) in_s = 0
        else if (!opened && NR - start >= 3) in_s = 0
      }
    ' "$kfile" 2>/dev/null
  done < <(kt_files) | add_violations_from "R12_launch_in_suspend_fun" "important"
fi

# R13 : runCatching { } .getOrNull() sans observabilité (échec silencieux), sur une
# ligne ou fermé dans les 8 lignes suivantes.
if regle_active R13; then
  while IFS= read -r kfile; do
    awk '
      /runCatching[[:space:]]*\{/ {
        if ($0 ~ /runCatching[[:space:]]*\{.*\}[[:space:]]*\.getOrNull\(\)/) { print FILENAME ":" NR ":" $0; rc = 0; next }
        rc = NR; rcline = $0; next
      }
      rc && NR - rc <= 8 && /^[[:space:]]*(\}[[:space:]]*)?\.getOrNull\(\)/ { print FILENAME ":" rc ":" rcline; rc = 0 }
      rc && NR - rc > 8 { rc = 0 }
    ' "$kfile" 2>/dev/null
  done < <(kt_files) | add_violations_from "R13_runcatching_swallowed" "suggestion"
fi

# R14 : deux écritures d'état à 6 lignes ou moins dans la même fonction
# (_xxxState.update { } ou _xxxState.value = ; aussi _state, _stateFlow, _session) :
# un handler fait UN seul write atomique. Écartés : deux écritures séparées par une
# déclaration de fun, ou dans deux branches exclusives (la profondeur d'accolades
# redescend sous celle de la première, comme dans `} else {`, ou une branche `->`).
if regle_active R14; then
  echo "[mechanical-scan] checking consecutive state writes..." >&2
  while IFS= read -r kfile; do
    awk '
      /^[[:space:]]*(\/\/|\/\*|\*)/ { next }
      {
        ld = depth; lmin = depth; d = depth
        for (i = 1; i <= length($0); i++) {
          ch = substr($0, i, 1)
          if (ch == "{") d++
          else if (ch == "}") { d--; if (d < lmin) lmin = d }
        }
        if (last && lmin < mind) mind = lmin
      }
      /(^|[^A-Za-z0-9_])fun[[:space:]]/ { last = 0 }
      /(^|[^A-Za-z0-9_])_[A-Za-z0-9]*([sS]tate|[sS]ession)(Flow)?\.(update[[:space:]]*\{|value[[:space:]]*=([^=]|$))/ {
        if (last > 0 && NR - last <= 6 && ld == wd && mind >= wd && $0 !~ /->/ && lw !~ /->/) print FILENAME ":" NR ":" $0
        last = NR; wd = ld; mind = ld; lw = $0
      }
      { depth = d }
    ' "$kfile" 2>/dev/null
  done < <(kt_files) | add_violations_from "R14_consecutive_state_write" "important"
fi

# R15 : fichier d'interface avec .clickable / onClick = qui ne dit jamais
# contentDescription ni Modifier.semantics (heuristique par fichier : on ouvre et
# on juge ; un import de semantics.Role ne vaut pas libellé).
if regle_active R15; then
  echo "[mechanical-scan] checking accessibility hints..." >&2
  while IFS= read -r kfile; do
    if grep -qE '\.(combinedC|c)lickable\s*[({]|\bonClick\s*=' "$kfile" 2>/dev/null && \
       ! grep -qE 'contentDescription|Modifier\.semantics' "$kfile" 2>/dev/null; then
      lineno=$(grep -nE '\.(combinedC|c)lickable\s*[({]|\bonClick\s*=' "$kfile" 2>/dev/null | head -1 | cut -d: -f1)
      add_violation "R15_clickable_without_contentDescription_in_file" "$kfile" "${lineno:-1}" "suggestion" \
        "file uses .clickable/onClick but never mentions contentDescription or Modifier.semantics"
    fi
  done < <(kt_files "$UI")
fi

# R17 : allocation (Paint(), Path(), Rect géométrique) dans un fichier qui dessine,
# hors d'un remember { } sur la même ligne. Un Paint() champ de classe est un faux positif.
if regle_active R17; then
  echo "[mechanical-scan] checking Canvas allocations..." >&2
  while IFS= read -r kfile; do
    grep -qE '\bCanvas\s*\(|drawIntoCanvas|drawBehind|\bDrawScope\b' "$kfile" 2>/dev/null || continue
    grep -nE '\b(android\.graphics\.)?Paint\s*\(\s*\)|\bPath\s*\(\s*\)|androidx\.compose\.ui\.geometry\.Rect\s*\(' "$kfile" 2>/dev/null \
      | grep -vE 'remember\s*\{' \
      | sed "s|^|$kfile:|"
  done < <(kt_files "$UI") | add_violations_from "R17_canvas_allocation_no_remember" "suggestion"
fi

# R20 : Modifier.alpha / scale / offset animés : chaque frame invalide la mise en
# page. Passer par graphicsLayer { }.
regle_active R20 && scan_pattern "R20_animated_modifier_not_graphicslayer" "important" \
  '\.(alpha|scale|offset)\([^)]*([Aa]nim|by animate|\.value\b)' "$UI"

# R21 : lecture Firestore .get() sans .limit() (ni .document()), sur une ligne ou
# dans une chaîne d'appels sur plusieurs lignes.
if regle_active R21; then
  while IFS= read -r kfile; do
    grep -qE '\.(collection|collectionGroup)\(' "$kfile" 2>/dev/null || continue
    awk '
      function verdict(n, txt) { if (!lim && !doc) print FILENAME ":" n ":" txt }
      {
        single = ($0 ~ /\.(collection|collectionGroup|orderBy|where[A-Za-z]*)\([^)]*\)\.get\(\)/ && $0 !~ /\.limit\(/)
        if ($0 ~ /\.(collection|collectionGroup)\(/) {
          chain = 1; lim = ($0 ~ /\.limit\(/); doc = ($0 ~ /\.document\(/); start = NR; head = $0
          if ($0 ~ /\.get\(\)/) { if (single || (!lim && !doc)) print FILENAME ":" NR ":" $0; chain = 0 }
          next
        }
        if (chain && $0 ~ /^[[:space:]]*\./) {
          if ($0 ~ /\.limit\(/) lim = 1
          if ($0 ~ /\.document\(/) doc = 1
          if ($0 ~ /\.get\(\)/) { if (single) print FILENAME ":" NR ":" $0; else verdict(NR, $0); chain = 0 }
          next
        }
        chain = 0
        if (single) print FILENAME ":" NR ":" $0
      }
    ' "$kfile" 2>/dev/null
  done < <(kt_files) | add_violations_from "R21_firestore_get_no_limit" "important"
fi

# R22 : chaînes affichées (res/values*/strings*.xml).
# - "%%" dans une chaîne SANS argument positionnel s'affiche tel quel : il faut
#   formatted="false" et un % simple. À côté d'un %1$d, "%%" est juste.
# - Aucun tiret cadratin dans un texte affiché, dans aucune langue (règle de Franck),
#   y compris écrit &#8212; ou —. Les commentaires XML ne comptent pas.
if regle_active R22 && [[ -d "$RES" ]]; then
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    match="${line#*:}"; match="${match#*:}"
    printf '%s' "$match" | grep -qE '%[0-9]+\$' && continue
    printf '%s\n' "$line"
  done < <(grep -rEn --include='strings*.xml' '%%' "$RES" 2>/dev/null) \
    | add_violations_from "R22_double_percent_without_argument" "important"
  find "$RES" -path '*/values*/strings*.xml' -type f 2>/dev/null | sort | while IFS= read -r xfile; do
    awk '
      {
        line = $0; out = ""
        while (length(line) > 0) {
          if (incom) {
            p = index(line, "-->"); if (p == 0) { line = "" } else { line = substr(line, p + 3); incom = 0 }
          } else {
            p = index(line, "<!--"); if (p == 0) { out = out line; line = "" } else { out = out substr(line, 1, p - 1); line = substr(line, p + 4); incom = 1 }
          }
        }
        if (out ~ /—|&#8212;|&#x2014;|\\u2014/) print FILENAME ":" NR ":" $0
      }
    ' "$xfile" 2>/dev/null
  done | add_violations_from "R22_em_dash_in_string" "important"
fi

# R18 : budget de contexte de CLAUDE.md (chargé à chaque session et par chaque
# sous-agent). Ne tourne que si CLAUDE_MD_MAX_BYTES est posé par le projet. Le
# seuil est un garde-fou de reprise de poids, posé juste au-dessus de la taille réelle.
if regle_active R18 && [[ -n "$CLAUDE_MD_MAX_BYTES" && -f "$ROOT/CLAUDE.md" ]]; then
  claude_md_bytes=$(tr -d '\r' < "$ROOT/CLAUDE.md" | wc -c | tr -d ' ')
  if [[ "$claude_md_bytes" -gt "$CLAUDE_MD_MAX_BYTES" ]]; then
    add_violation "R18_claude_md_context_budget" "$ROOT/CLAUDE.md" 1 "important" \
      "CLAUDE.md pese ${claude_md_bytes} octets (~$((claude_md_bytes / 4)) tokens), au-dela du garde-fou de ${CLAUDE_MD_MAX_BYTES}. Cible ${CLAUDE_MD_TARGET_BYTES:-$CLAUDE_MD_MAX_BYTES}. Sortir du RECIT vers docs/, pas des regles."
  fi
fi

# ----- Règles du projet -----
if declare -F regles_projet >/dev/null; then
  echo "[mechanical-scan] running project rules..." >&2
  regles_projet
fi

# ----- Parité i18n -----
# Locales : les dossiers values-<langue> qui portent au moins un strings*.xml
# (values-land, values-night, values-v31... sont écartés). Référence : tous les
# values/strings*.xml, moins les clés translatable="false", que les locales ne
# doivent pas redéfinir. Une locale qui ne définit aucune chaîne (garde-fou
# explicite de la langue par défaut) est écartée, comme LOCALES_IGNOREES.
echo "[mechanical-scan] checking i18n parity..." >&2
LOCALES=()
if [[ -d "$RES" ]]; then
  [[ -n "$(find "$RES/values" -maxdepth 1 -name 'strings*.xml' 2>/dev/null)" ]] && LOCALES+=("values")
  while IFS= read -r dir; do
    name="$(basename "$dir")"
    [[ "$name" =~ ^values-([a-z]{2,3}(-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)$ ]] || continue
    [[ -n "$(find "$dir" -maxdepth 1 -name 'strings*.xml' 2>/dev/null)" ]] || continue
    LOCALES+=("$name")
  done < <(find "$RES" -mindepth 1 -maxdepth 1 -type d -name 'values-*' 2>/dev/null | sort)
fi

extract_names() { # <tag> <fichiers...>
  local tag="$1"; shift
  cat "$@" 2>/dev/null | grep -oE "<$tag name=\"[^\"]+\"" | sed -E "s/<$tag name=\"([^\"]+)\"/\\1/" | sort -u
}

if [[ " ${LOCALES[*]:-} " == *" values "* ]]; then
  ref_files=("$RES"/values/strings*.xml)
  ref_keys=$(cat "${ref_files[@]}" 2>/dev/null | grep -E '<string name="' | grep -v 'translatable="false"' \
    | sed -E 's/.*<string name="([^"]+)".*/\1/' | sort -u)
  untranslatable=$(cat "${ref_files[@]}" 2>/dev/null | grep -E '<string name="[^"]+"[^>]*translatable="false"' \
    | sed -E 's/.*<string name="([^"]+)".*/\1/' | sort -u)
  ref_plurals=$(extract_names plurals "${ref_files[@]}")

  check_locales=()
  for locale in "${LOCALES[@]}" $LOCALES_ATTENDUES; do
    [[ "$locale" == "values" ]] && continue
    [[ " $LOCALES_IGNOREES " == *" $locale "* ]] && continue
    [[ " ${check_locales[*]:-} " == *" $locale "* ]] && continue
    check_locales+=("$locale")
  done

  for locale in "${check_locales[@]}"; do
    loc_files=("$RES/$locale"/strings*.xml)
    if [[ ! -f "${loc_files[0]}" ]]; then
      entry=$(printf '{"locale":"%s","status":"missing_file","missing_keys":[],"missing_plurals":[],"extra_keys":[]}' "$locale")
      if [[ -z "$I18N_ISSUES" ]]; then I18N_ISSUES="$entry"; else I18N_ISSUES="$I18N_ISSUES,$entry"; fi
      continue
    fi
    locale_keys=$(extract_names string "${loc_files[@]}")
    locale_plurals=$(extract_names plurals "${loc_files[@]}")
    if [[ -z "$locale_keys" && -z "$locale_plurals" ]]; then
      echo "[mechanical-scan] $locale ne définit aucune chaîne : écartée de la parité" >&2
      continue
    fi
    missing_keys=$(comm -23 <(printf '%s\n' "$ref_keys") <(printf '%s\n' "$locale_keys") 2>/dev/null)
    missing_plurals=$(comm -23 <(printf '%s\n' "$ref_plurals") <(printf '%s\n' "$locale_plurals") 2>/dev/null)
    extra_keys=$(comm -13 <(printf '%s\n%s\n' "$ref_keys" "$untranslatable" | sort -u) <(printf '%s\n' "$locale_keys") 2>/dev/null)
    if [[ -n "$missing_keys" || -n "$missing_plurals" || -n "$extra_keys" ]]; then
      entry=$(printf '{"locale":"%s","status":"incomplete","missing_keys":[%s],"missing_plurals":[%s],"extra_keys":[%s]}' \
        "$locale" "$(to_json_array "$missing_keys")" "$(to_json_array "$missing_plurals")" "$(to_json_array "$extra_keys")")
      if [[ -z "$I18N_ISSUES" ]]; then I18N_ISSUES="$entry"; else I18N_ISSUES="$I18N_ISSUES,$entry"; fi
    fi
  done
fi

# ----- Carte des domaines -----
# Le projet la fournit par carte_domaines() (qui pose DOMAIN_MAP_JSON) quand ses
# agents d'audit attendent des clés précises. Sinon : un domaine par dossier de
# premier niveau du package ; un dossier de plus de SEUIL_DOMAINE fichiers se
# découpe en ses sous-dossiers ; les fichiers posés à la racine vont dans « racine ».
# Chemins relatifs à $SRC, un dossier finit par « / ».
echo "[mechanical-scan] writing domain map..." >&2
if declare -F carte_domaines >/dev/null; then carte_domaines; fi
if [[ -z "$DOMAIN_MAP_JSON" ]]; then
  map_entries=()
  add_domain() { map_entries+=("\"$(json_escape "$1")\":[$2]"); }
  root_files=$(find "$SRC" -mindepth 1 -maxdepth 1 -name '*.kt' -type f -printf '%f\n' 2>/dev/null | sort)
  [[ -n "$root_files" ]] && add_domain "racine" "$(to_json_array "$root_files")"
  while IFS= read -r dir; do
    name="$(basename "$dir")"
    n=$(find "$dir" -name '*.kt' -type f 2>/dev/null | wc -l | tr -d ' ')
    [[ "$n" -eq 0 ]] && continue
    if [[ "$n" -le "$SEUIL_DOMAINE" ]]; then
      add_domain "$name" "\"$name/\""
      continue
    fi
    own=$(find "$dir" -mindepth 1 -maxdepth 1 -name '*.kt' -type f -printf "$name/%f\n" 2>/dev/null | sort)
    [[ -n "$own" ]] && add_domain "$name" "$(to_json_array "$own")"
    while IFS= read -r sub; do
      [[ -n "$(find "$sub" -name '*.kt' -type f -print -quit 2>/dev/null)" ]] || continue
      add_domain "${name}_$(basename "$sub")" "\"$name/$(basename "$sub")/\""
    done < <(find "$dir" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
  done < <(find "$SRC" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
  DOMAIN_MAP_JSON="{$(IFS=,; printf '%s' "${map_entries[*]:-}")}"
fi

# ----- Stats -----
total_kt=$(find "$SRC" -name '*.kt' -type f 2>/dev/null | wc -l | tr -d ' ')
n_locales=${#LOCALES[@]}

# ----- Sortie -----
SCAN_DATE="$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo 'unknown')"

if [[ "$PRETTY" == "1" ]]; then
  echo "=== Mechanical Scan: $SCAN_DATE ==="
  echo "Project:              $ROOT"
  echo "Sources:              ${SRC#"$ROOT"/}"
  echo "Project rules:        $([[ -f "$RULES_PROJET" ]] && echo 'tools/audit/rules-projet.sh' || echo 'none')"
  echo "Kotlin files scanned: $total_kt"
  echo "Violations:           $VIOLATION_COUNT"
  echo "Locales present:      $n_locales (${LOCALES[*]:-})"
  echo ""
  echo "Top violations by rule:"
  printf '%s' "$VIOLATIONS" | grep -oE '"rule":"[^"]+"' | sort | uniq -c | sort -rn | head -40
  echo ""
  echo "Violations (file:line rule):"
  printf '%s' "$VIOLATIONS" | grep -oE '"rule":"[^"]+","file":"[^"]+","line":[0-9]+' \
    | sed -E 's/"rule":"([^"]+)","file":"([^"]+)","line":([0-9]+)/  \2:\3 \1/'
  echo ""
  if [[ -n "$I18N_ISSUES" ]]; then
    summary=$(printf '%s' "$I18N_ISSUES" | sed 's/},{/}\n{/g' | awk '
      {
        loc = $0; sub(/.*"locale":"/, "", loc); sub(/".*/, "", loc)
        st = $0; sub(/.*"status":"/, "", st); sub(/".*/, "", st)
        if (st == "missing_file") { printf "%s%s: fichier absent", sep, loc; sep = "; "; next }
        n = split("missing_keys missing_plurals extra_keys", f, " ")
        printf "%s%s:", sep, loc
        for (i = 1; i <= n; i++) {
          s = $0; sub(".*\"" f[i] "\":\\[", "", s); sub(/\].*/, "", s)
          c = (s == "") ? 0 : gsub(/","/, "", s) + 1
          printf " %d %s", c, f[i]
        }
        sep = "; "
      }')
    echo "i18n parity issues: $(printf '%s' "$I18N_ISSUES" | grep -o '"locale":' | wc -l | tr -d ' ') locale(s) incomplete ($summary)"
  else
    echo "i18n parity issues: none"
  fi
  echo ""
  echo "Full JSON: re-run without --pretty"
else
  # echo -n et non printf : un extrait peut contenir un %.
  {
    echo -n '{"scan_date":"'
    echo -n "$SCAN_DATE"
    echo -n '","stats":{"kotlin_files":'
    echo -n "$total_kt"
    echo -n ',"violations":'
    echo -n "$VIOLATION_COUNT"
    echo -n ',"locales":'
    echo -n "$n_locales"
    echo -n '},"violations":['
    echo -n "$VIOLATIONS"
    echo -n '],"i18n_parity_issues":['
    echo -n "$I18N_ISSUES"
    echo -n '],"domain_map":'
    echo -n "$DOMAIN_MAP_JSON"
    echo '}'
  }
fi

echo "[mechanical-scan] done." >&2
exit 0
