#!/data/data/com.termux/files/usr/bin/bash
#
# run_path_redir_vars_tests.sh
#
# Integration tests for the Termux shell-completion bash scenarios.
#
# These scripts are pure bash that Java assembles at runtime as:
#     <shell_complete_common.sh> + <scenario raw>
# and executes as:
#     bash --norc --noprofile ASSEMBLED.sh "<compLine>" <compPoint> "<currentWord>"
# with $1=COMP_LINE, $2=COMP_POINT, $3=CURRENT_WORD.
#
# This harness reproduces that assembly + invocation against the REAL bash
# (Android/Termux port, bash 5.3.9) and greps stdout for expected candidates.
#
# It does NOT modify the production scripts. Cases that cannot pass because of
# a genuine production bug (see the tokenizer infinite-loop note below) are
# reported as FAIL with an explanation; cases blocked purely by the Termux
# filesystem sandbox are reported as SKIP.
#
# Usage:
#     bash app/src/test/resources/shell_complete/run_path_redir_vars_tests.sh
#
# Exit code: 0 if no FAILs, 1 otherwise.

set -u

# --- Locate the production raw scripts relative to this test file -----------
SELF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# repo_root/app/src/test/resources/shell_complete -> repo_root
REPO_ROOT=$(cd "$SELF_DIR/../../../../.." && pwd)
RAW_DIR="$REPO_ROOT/app/src/main/res/raw"

COMMON="$RAW_DIR/shell_complete_common.sh"
PATH_RAW="$RAW_DIR/shell_complete_path.sh"
REDIR_RAW="$RAW_DIR/shell_complete_redir.sh"
VARS_RAW="$RAW_DIR/shell_complete_vars.sh"

# --- Temp dir (must be under the opencode cache, NOT /tmp) ------------------
TMP_DIR="/data/data/com.termux/files/home/.cache/opencode/tmp"
mkdir -p "$TMP_DIR"
WORK="$TMP_DIR/shell_complete_tests.$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

# Per-invocation timeout: the scenario spawns child completers / scans .bashrc,
# so we always redirect its stdin from /dev/null and cap wall time so a bug
# (e.g. the tokenizer loop) is caught instead of hanging the whole run.
RUN_TIMEOUT=15

PASS=0
FAIL=0
SKIP=0

# --- assemble <scenario_raw>  -> path of the assembled temp script ----------
# Concatenates common + scenario into a fresh temp file and echoes its path.
assemble() {
  local scenario=$1
  local out="$WORK/assembled.$RANDOM$RANDOM.sh"
  cat "$COMMON" "$scenario" > "$out" || return 1
  printf '%s\n' "$out"
}

# --- run_case: execute an assembled scenario, decode NUL-separated output ---
# args: <assembled.sh> <compLine> <compPoint> <currentWord> [workdir]
# writes decoded (newline-separated) candidates, one per line, to $CASE_OUT.
# We must NOT use $(...) command substitution to capture, because bash strips
# NUL bytes from command substitution — which would jam every candidate onto a
# single line. Instead we redirect raw output to a file and translate NUL->\n
# from that file with `tr`, preserving one-candidate-per-line.
# returns: 0 ok, 124 timeout/hang, other = bash error
CASE_OUT="$WORK/case_out.txt"
run_case() {
  local script=$1 line=$2 point=$3 word=$4 wd=${5:-}
  local raw="$WORK/case_raw.bin" rc
  if [[ -n $wd ]]; then
    ( cd "$wd" && timeout "$RUN_TIMEOUT" \
        bash --norc --noprofile "$script" "$line" "$point" "$word" </dev/null 2>/dev/null ) >"$raw"
    rc=$?
  else
    timeout "$RUN_TIMEOUT" \
        bash --norc --noprofile "$script" "$line" "$point" "$word" </dev/null 2>/dev/null >"$raw"
    rc=$?
  fi
  # NUL -> newline so each completion candidate is a distinct line, then drop
  # the __COMPOPTS side-channel sentinel (Java parses it separately).
  tr '\0' '\n' <"$raw" | grep -v '^__COMPOPTS:' > "$CASE_OUT"
  return $rc
}

report_pass() { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
report_fail() { FAIL=$((FAIL+1)); printf 'FAIL  %s\n     -> %s\n' "$1" "$2"; }
report_skip() { SKIP=$((SKIP+1)); printf 'SKIP  %s\n     -> %s\n' "$1" "$2"; }

# expect_contains: run a case and assert the decoded output matches a regex.
# args: <desc> <scenario_raw> <compLine> <compPoint> <currentWord> <regex> [workdir]
expect_contains() {
  local desc=$1 scenario=$2 line=$3 point=$4 word=$5 regex=$6 wd=${7:-}
  local script; script=$(assemble "$scenario") || { report_fail "$desc" "assemble failed"; return; }
  local rc
  run_case "$script" "$line" "$point" "$word" "$wd"; rc=$?
  if [[ $rc -eq 124 ]]; then
    report_fail "$desc" "scenario HUNG (timeout ${RUN_TIMEOUT}s) — see tokenizer infinite-loop bug in report"
    return
  fi
  if grep -Eq -- "$regex" "$CASE_OUT"; then
    report_pass "$desc"
  else
    report_fail "$desc" "expected /$regex/, got: [$(tr '\n' '|' <"$CASE_OUT")]"
  fi
}

# skip_if_missing_glob: SKIP a case whose expectation depends on a sandbox path
# that isn't present in this environment (so the failure would be environmental,
# not a code defect). args: <desc> <glob>  -> returns 0 if we should SKIP.
should_skip_glob() {
  local g=$1
  ! compgen -G "$g" >/dev/null 2>&1
}

printf '===================================================================\n'
printf ' Termux shell-completion integration tests\n'
printf ' repo: %s\n' "$REPO_ROOT"
printf ' bash: %s\n' "$(bash --version | head -1)"
printf '===================================================================\n\n'

# ------------------------------------------------------------------ PATH ----
printf '%s\n' '--- PATH scenario -------------------------------------------------'

# 1) tilde: `cd ~/D`  -> a $HOME entry starting with D
if should_skip_glob "$HOME/D*"; then
  report_skip "PATH tilde 'cd ~/D'" "no \$HOME entry matching D* in this env"
else
  expect_contains "PATH tilde 'cd ~/D' -> \$HOME/D*" \
    "$PATH_RAW" "cd ~/D" 5 "~/D" "^${HOME}/D"
fi

# 2) relative: `cp a.txt ./`  (run in repo root) -> ./app/ or ./gradle*
expect_contains "PATH relative 'cp a.txt ./' -> ./app/ or ./gradle" \
  "$PATH_RAW" "cp a.txt ./" 11 "./" '^\./(app/|gradle)' "$REPO_ROOT"

# 3) absolute-accessible: `cd /data/data/com.termux/files/home/DI`
ABS_PREFIX="/data/data/com.termux/files/home/DI"
if should_skip_glob "${ABS_PREFIX}*"; then
  report_skip "PATH absolute '$ABS_PREFIX'" "no matching entry in \$HOME"
else
  expect_contains "PATH absolute 'cd $ABS_PREFIX' -> DIAGNOSIS_*.md" \
    "$PATH_RAW" "cd $ABS_PREFIX" "$(( ${#ABS_PREFIX} + 3 ))" "$ABS_PREFIX" \
    '/DIAGNOSIS_.*\.md'
fi

# 4) /storage: `cat /storage/emulated/0/Dow` -> /storage/emulated/0/Download/
if should_skip_glob "/storage/emulated/0/Dow*"; then
  report_skip "PATH /storage 'Dow'" "/storage/emulated/0/Dow* not accessible in this env"
else
  expect_contains "PATH /storage 'cat /storage/emulated/0/Dow' -> Download/" \
    "$PATH_RAW" "cat /storage/emulated/0/Dow" 28 "/storage/emulated/0/Dow" \
    '^/storage/emulated/0/Download/'
fi

printf '\n'; printf '%s\n' '--- REDIRECTION scenario -----------------------------------------'

# 5) `cat foo > ` with an empty current word -> files of the current directory.
# NOTE: the previous token is the redirection operator '>'. This is the exact
# scenario this raw script exists for. See the tokenizer bug note below — this
# case exercises it directly.
expect_contains "REDIR 'cat foo > ' (empty word) -> ./ files" \
  "$REDIR_RAW" "cat foo > " 10 "" '^\./' "$REPO_ROOT"

# 5b) A redirection where the word already has a partial path (still contains
# the '>' operator earlier in the line, so still routes through the tokenizer).
expect_contains "REDIR 'cat foo > ./RE' -> ./README.md" \
  "$REDIR_RAW" "cat foo > ./RE" 13 "./RE" '^\./README\.md' "$REPO_ROOT"

printf '\n'; printf '%s\n' '--- VARIABLE scenario --------------------------------------------'

# 6) `echo $HO` -> $HOME  (HOME is always exported in this env)
expect_contains "VARS 'echo \$HO' -> \$HOME" \
  "$VARS_RAW" 'echo $HO' 8 '$HO' '^\$HOME$'

# 7) `echo $TER` -> at least one $TER* env var (TERM/TERMUX_* depend on env)
if compgen -v 2>/dev/null | grep -Eq '^TER'; then
  expect_contains "VARS 'echo \$TER' -> \$TER* variable" \
    "$VARS_RAW" 'echo $TER' 9 '$TER' '^\$TER'
else
  report_skip "VARS 'echo \$TER'" "no shell variable starting with TER in this env"
fi

# 8) `${HO`  brace form -> ${HOME}
expect_contains "VARS 'echo \${HO' (brace) -> \${HOME}" \
  "$VARS_RAW" 'echo ${HO' 9 '${HO' '^\$\{HOME\}$'

# 9) builtin: `export HO` -> HOME  (words[0]=export branch, no sigil)
expect_contains "VARS 'export HO' (builtin) -> HOME" \
  "$VARS_RAW" 'export HO' 9 'HO' '^HOME$'

printf '\n===================================================================\n'
printf ' RESULT: PASS=%d  SKIP=%d  FAIL=%d\n' "$PASS" "$SKIP" "$FAIL"
printf '===================================================================\n'

[[ $FAIL -eq 0 ]]
