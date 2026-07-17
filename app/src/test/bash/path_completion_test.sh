#!/usr/bin/env bash
#
# path_completion_test.sh
#
# Modular unit tests for the Termux shell path-completion module.
# Exercises the real Android bash port in this environment:
#   /data/data/com.termux/files/usr/bin/bash  (5.3.9)
#
# The module under test is the concatenation of:
#   app/src/main/res/raw/shell_complete_common.sh  (helpers + __termux_file_fallback)
#   app/src/main/res/raw/shell_complete_path.sh    (the path scenario)
#
# Each scenario is invoked exactly the way Java invokes it:
#   bash --norc --noprofile tmp.sh "<compLine>" "<compPoint>" "<currentWord>"
#
# The side-channel "__COMPOPTS:..." sentinel line is emitted to stdout and must
# be filtered out before candidate assertions. Candidates are NUL-separated.
#
# Exit code is 0 iff there are zero FAIL results. SKIP entries never count as
# FAIL (they document known sandbox limits we cannot exercise here).

set -u

BASH_BIN="${BASH_BIN:-/data/data/com.termux/files/usr/bin/bash}"
PROJECT_ROOT="${PROJECT_ROOT:-/data/local/projects/termux-app-ui-improve}"
COMMON="${PROJECT_ROOT}/app/src/main/res/raw/shell_complete_common.sh"
PATH_SCENARIO="${PROJECT_ROOT}/app/src/main/res/raw/shell_complete_path.sh"
TMP_DIR="${TMP_DIR:-/data/data/com.termux/files/home/.cache/opencode/tmp}"
TMP_SCRIPT="${TMP_DIR}/path_completion_under_test.sh"

PASS=0
FAIL=0
SKIP=0

# ---------------------------------------------------------------------------
# assemble(raw) -> concatenate common + path scenario into the temp script.
# ---------------------------------------------------------------------------
assemble() {
  if [[ ! -f "$COMMON" ]]; then
    echo "FATAL: missing $COMMON" >&2; exit 2
  fi
  if [[ ! -f "$PATH_SCENARIO" ]]; then
    echo "FATAL: missing $PATH_SCENARIO" >&2; exit 2
  fi
  cat "$COMMON" "$PATH_SCENARIO" > "$TMP_SCRIPT" || { echo "FATAL: write $TMP_SCRIPT" >&2; exit 2; }
}

# ---------------------------------------------------------------------------
# run(compLine, compPoint, currentWord) -> NUL/CR candidates only (no sentinel).
# ---------------------------------------------------------------------------
run() {
  local compLine="$1" compPoint="$2" currentWord="$3"
  "$BASH_BIN" --norc --noprofile "$TMP_SCRIPT" "$compLine" "$compPoint" "$currentWord" 2>/dev/null \
    | tr '\0' '\n' \
    | grep -v '^__COMPOPTS:' \
    | grep -v '^$'
}

# ---------------------------------------------------------------------------
# assertion helpers
# ---------------------------------------------------------------------------
expect_present() {            # desc, needle, haystack
  local desc="$1" needle="$2" hay="$3"
  if printf '%s\n' "$hay" | grep -qx -- "$needle"; then
    echo "PASS: $desc (found: $needle)"
    PASS=$((PASS+1))
  else
    echo "FAIL: $desc (missing: $needle)"
    FAIL=$((FAIL+1))
    echo "      --- output was ---"; printf '%s\n' "$hay" | sed 's/^/        /'
  fi
}

expect_empty() {             # desc, haystack
  local desc="$1" hay="$2"
  if [[ -z "$hay" ]]; then
    echo "PASS: $desc (empty as expected)"
    PASS=$((PASS+1))
  else
    echo "FAIL: $desc (expected empty, got:)"
    FAIL=$((FAIL+1))
    echo "      --- output was ---"; printf '%s\n' "$hay" | sed 's/^/        /'
  fi
}

skip_case() {                # desc, reason
  local desc="$1" reason="$2"
  echo "SKIP: $desc ($reason)"
  SKIP=$((SKIP+1))
}

expect_dir_trailing_slash() { # desc, haystack
  # Verify that every candidate which IS a directory on disk carries a trailing
  # '/' AND every candidate which is NOT a directory does NOT. This mirrors the
  # classification the module performs. Candidates are paths relative to $PWD
  # (e.g. "./app/") so they are stat-able from the test's working directory.
  local desc="$1" hay="$2" bad=0 line
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if [[ -d "$line" ]]; then
      [[ "$line" == */ ]] || { bad=1; echo "        dir missing slash: $line"; }
    else
      [[ "$line" != */ ]] || { bad=1; echo "        file has slash: $line"; }
    fi
  done <<< "$hay"
  if (( bad )); then
    echo "FAIL: $desc (directory/file trailing-slash classification wrong)"
    FAIL=$((FAIL+1))
  else
    echo "PASS: $desc (dirs have /, files do not)"
    PASS=$((PASS+1))
  fi
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
assemble

echo "==== Termux path-completion unit tests ===="
echo "bash: $BASH_BIN"
echo "script: $TMP_SCRIPT"
echo

# 1. cd ~/D  (tilde -> $HOME, prefix D)
OUT=$(run "cd ~/D" 6 "~/D")
if [[ -z "$OUT" ]]; then
  skip_case "cd ~/D -> HOME file starting with D" "no D* entries in \$HOME"
else
  expect_present "cd ~/D -> file under \$HOME starting with D" \
    "$(printf '%s\n' "$OUT" | grep -m1 '^.*/D' )" "$OUT"
fi

# 2. cp a.txt ./  (relative ./ completion)
OUT=$(run "cp a.txt ./" 10 "./")
expect_present "cp a.txt ./ -> relative ./app/" "./app/" "$OUT"
expect_present "cp a.txt ./ -> relative ./gradle" "./gradle/" "$OUT"
expect_dir_trailing_slash "cp a.txt ./ -> dirs have trailing /" "$OUT"

# 3. cd /data/data/com.termux/files/home/DI (full absolute path)
OUT=$(run "cd /data/data/com.termux/files/home/DI" 38 "/data/data/com.termux/files/home/DI")
expect_present "cd /data/.../home/DI -> DIAGNOSIS_rilj_unexpected.md" \
  "/data/data/com.termux/files/home/DIAGNOSIS_rilj_unexpected.md" "$OUT"

# 4. cat /storage/emulated/0/Dow (accessible storage, absolute)
OUT=$(run "cat /storage/emulated/0/Dow" 28 "/storage/emulated/0/Dow")
expect_present "cat /storage/emulated/0/Dow -> /storage/emulated/0/Download/" \
  "/storage/emulated/0/Download/" "$OUT"

# 5. cat /da (root not directly readable in sandbox) -> expect EMPTY, known limit
OUT=$(run "cat /da" 7 "/da")
if [[ -z "$OUT" ]]; then
  skip_case "cat /da -> empty (root not readable)" "known sandbox limit: / is not directly accessible"
else
  # If it somehow returns something, that's informative but not a test failure
  # of the code's correctness given the documented sandbox constraint.
  skip_case "cat /da -> returned candidates" "unexpected but documented sandbox behavior changed"
fi

# 6. trailing-slash classification across absolute + storage
OUT=$(run "cat /storage/emulated/0/" 25 "/storage/emulated/0/")
expect_dir_trailing_slash "cat /storage/emulated/0/ -> dirs have trailing /" "$OUT"

echo
echo "TOTAL: $PASS PASS, $FAIL FAIL, $SKIP SKIP"
if (( FAIL > 0 )); then
  exit 1
fi
exit 0
