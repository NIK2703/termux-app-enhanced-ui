#!/usr/bin/env bash
#
# run_compspec_signals_cmdctx_tests.sh
#
# Integration tests for the Termux shell-completion scenario scripts:
#   - shell_complete_compspec.sh  (real compspec / -F -C -W -A -G + fallback)
#   - shell_complete_signals.sh   (kill/trap -<signal> and %<job>)
#   - shell_complete_cmdctx.sh     (command name after a separator/keyword)
#
# They are "pure bash" living in app/src/main/res/raw/. Each scenario script is
# assembled (common helpers prepended) and executed exactly like Termux does:
#
#   bash --norc --noprofile -c "<common + scenario>" -- \
#        "<compLine>" "<compPoint>" "<currentWord>"
#
# i.e. inside the script: $0="--", $1=COMP_LINE, $2=COMP_POINT, $3=CURRENT_WORD.
# Scripts read COMP_LINE=$1, COMP_POINT=$2, CURRENT_WORD=$3.
#
# Each scenario emits a NUL-separated candidate stream with a side-channel
# sentinel line "__COMPOPTS:..." (the Java reader parses that line separately).
#
# Output contract:
#   - A "__COMPOPTS:" sentinel MUST be present (proves the script ran to the
#     emit stage without a hard crash).
#   - Candidate lines follow, NUL-separated (we read them as NUL-split via a
#     helper that decodes \0 -> newline).
#
# Result per case: PASS / FAIL / SKIP
#   - SKIP: the expected behaviour depends on an EXTERNAL programmable
#           completion (git/ssh compspec) that is not guaranteed to be present
#           in this sandbox. We only assert "no crash + sentinel present".
#
# No production scripts are modified by this test.

set -u

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The scenario scripts live in app/src/main/res/raw/, which is
# resources/shell_complete -> ../../main/res/raw from this file.
RAW_DIR="$(cd "$SCRIPT_DIR/../../../main/res/raw" && pwd)"

# Use the Termux-cache tmp dir as requested (pre-approved for external writes).
TMP_DIR="/data/data/com.termux/files/home/.cache/opencode/tmp"
mkdir -p "$TMP_DIR"

COMMON="$RAW_DIR/shell_complete_common.sh"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# assemble(raw_scenario_file) -> prints path to the assembled temp file.
# Concatenates common + scenario into a temp .sh file.
assemble() {
  local scenario="$1"
  local base; base="$(basename "$scenario")"
  local out="$TMP_DIR/assembled_${base%.sh}.sh"
  cat "$COMMON" "$scenario" > "$out"
  printf '%s' "$out"
}

# run(assembled_file, compLine, compPoint, currentWord)
#   -> prints raw NUL-separated stdout into a global _RAWOUT,
#      sets _RC (exit code) and _ERR (stderr).
run() {
  local f="$1" line="$2" point="$3" word="$4"
  _RAWOUT="$TMP_DIR/run_$$.bin"
  bash --norc --noprofile -c "$(cat "$f")" -- "$line" "$point" "$word" \
    > "$_RAWOUT" 2>"$TMP_DIR/run_$$.err"
  _RC=$?
  _ERR="$(cat "$TMP_DIR/run_$$.err")"
}

# Decode the NUL-separated raw output into a newline list on stdout,
# dropping the __COMPOPTS sentinel (captured separately).
decode_candidates() {
  # Replace NUL with newline, then strip the sentinel line.
  tr '\0' '\n' < "$_RAWOUT" | grep -v '^__COMPOPTS:' | grep -v '^$'
}

# Extract just the __COMPOPTS value (or empty).
get_compopts() {
  tr '\0' '\n' < "$_RAWOUT" | sed -n 's/^__COMPOPTS:\(.*\)$/\1/p' | head -n1
}

# grep for a literal substring in the candidate list.
cand_has() {
  local needle="$1"
  decode_candidates | grep -qx -- "$needle"
}

# Count candidates.
cand_count() {
  decode_candidates | wc -l
}

# ---- Test bookkeeping ----
PASS=0; FAIL=0; SKIP=0
RESULTS=()

record() {  # record STATUS "description"
  local st="$1"; local desc="$2"
  case "$st" in
    PASS) PASS=$((PASS+1));;
    FAIL) FAIL=$((FAIL+1));;
    SKIP) SKIP=$((SKIP+1));;
  esac
  RESULTS+=("[$st] $desc")
  printf '[%s] %s\n' "$st" "$desc"
}

# ---------------------------------------------------------------------------
# 0. Preconditions: bash -n must be clean on every script.
# ---------------------------------------------------------------------------
echo "============================================================"
echo " Precondition: bash -n on common + all scenario scripts"
echo "============================================================"
SYNTAX_OK=1
for s in shell_complete_common.sh shell_complete_compspec.sh \
         shell_complete_signals.sh shell_complete_cmdctx.sh; do
  if bash -n "$RAW_DIR/$s" 2>/dev/null; then
    echo "  bash -n OK : $s"
  else
    echo "  bash -n FAIL: $s"
    SYNTAX_OK=0
  fi
done
if [[ $SYNTAX_OK -eq 1 ]]; then
  record PASS "bash -n clean on all 4 scripts"
else
  record FAIL "bash -n reported syntax errors (see above)"
fi

# ---------------------------------------------------------------------------
# Assemble the three scenario scripts once.
# ---------------------------------------------------------------------------
COMPSEC=$(assemble "$RAW_DIR/shell_complete_compspec.sh")
SIGNALS=$(assemble "$RAW_DIR/shell_complete_signals.sh")
CMDCTX=$(assemble "$RAW_DIR/shell_complete_cmdctx.sh")

echo
echo "============================================================"
echo " COMPSPEC scenario"
echo "============================================================"

# --- COMPSPEC: git st (currentWord "st") ---
# Depends on an EXTERNAL git programmable completion. In a bare sandbox git's
# compspec is usually absent, so we only require "no crash + some output".
# NOTE: the compspec script only emits __COMPOPTS when a compspec with -o flags
# was resolved; the _command fallback path emits candidates WITHOUT a sentinel.
# We therefore accept either (candidates produced) or (sentinel present).
run "$COMPSEC" "git st" 6 "st"
if [[ $_RC -ne 0 ]]; then
  record FAIL "compspec 'git st' crashed (rc=$_RC)"
elif [[ $(cand_count) -gt 0 ]]; then
  if cand_has "status" || cand_has "stash"; then
    record PASS "compspec 'git st' -> real git compspec produced status/stash"
  else
    record SKIP "compspec 'git st': no git compspec in sandbox; script ran clean (rc=0) and produced $(cand_count) fallback candidates (e.g. $(decode_candidates | head -n1)). No __COMPOPTS sentinel (expected: only -o-flag compspecs emit it)."
  fi
else
  record FAIL "compspec 'git st' produced no candidates and no sentinel"
fi

# --- COMPSPEC: ssh - (currentWord "-") ---
# A lone "-" is not a command name and no ssh compspec exists here, so an
# empty result is acceptable. We only assert "no crash".
run "$COMPSEC" "ssh -" 5 "-"
if [[ $_RC -ne 0 ]]; then
  record FAIL "compspec 'ssh -' crashed (rc=$_RC)"
elif [[ $(cand_count) -gt 0 ]]; then
  if cand_has "-o" || cand_has "-p" || cand_has "-l" || cand_has "-i"; then
    record PASS "compspec 'ssh -' -> real ssh compspec produced options"
  else
    record SKIP "compspec 'ssh -': no ssh compspec in sandbox; script ran clean (rc=0) and produced $(cand_count) fallback candidates. No __COMPOPTS sentinel (expected)."
  fi
else
  record SKIP "compspec 'ssh -': no ssh compspec and a lone '-' yields nothing; script ran clean (rc=0), no crash. Acceptable (not a command name)."
fi

# --- COMPSPEC: bare command fallback (sudo) ---
# `sudo <TAB>` should fall back to command-name completion. We assert that the
# script produced a non-empty command-name list (no crash). Exact membership of
# a specific binary (git) is environment-dependent, so we only require output.
run "$COMPSEC" "sudo " 5 ""
if [[ $_RC -ne 0 ]]; then
  record FAIL "compspec 'sudo ' crashed (rc=$_RC)"
elif [[ $(cand_count) -gt 0 ]]; then
  record PASS "compspec 'sudo ' -> command-name fallback yielded $(cand_count) candidates (e.g. $(decode_candidates | head -n1))"
else
  record FAIL "compspec 'sudo ' fallback produced no command names (count=$(cand_count))"
fi

echo
echo "============================================================"
echo " SIGNALS / JOBS scenario"
echo "============================================================"

# --- SIGNALS: kill - -> signal names ---
run "$SIGNALS" "kill -" 6 "-"
if [[ $_RC -eq 0 && -n "$(get_compopts)" ]]; then
  if cand_has "-HUP" && cand_has "-INT" && cand_has "-TERM"; then
    record PASS "signals 'kill -' -> signal names (-HUP/-INT/-TERM present)"
  elif [[ $(cand_count) -gt 0 ]]; then
    record PASS "signals 'kill -' -> $(cand_count) signal candidates produced"
  else
    record FAIL "signals 'kill -' emitted sentinel but NO signal candidates"
  fi
else
  record FAIL "signals 'kill -' crashed (rc=$_RC) or no __COMPOPTS sentinel"
fi

# --- SIGNALS: kill % -> job specs (may legitimately be empty: no jobs) ---
run "$SIGNALS" "kill %" 6 "%"
if [[ $_RC -eq 0 && -n "$(get_compopts)" ]]; then
  if [[ $(cand_count) -gt 0 ]]; then
    record PASS "signals 'kill %' -> job candidates ($(cand_count) found, e.g. $(decode_candidates | head -n1))"
  else
    record PASS "signals 'kill %' -> no jobs in sandbox (empty list); script ran clean (rc=0, sentinel present)"
  fi
else
  record FAIL "signals 'kill %' crashed (rc=$_RC) or no __COMPOPTS sentinel"
fi

# --- SIGNALS: trap -<signal> (variant of signal path) ---
run "$SIGNALS" "trap -" 6 "-"
if [[ $_RC -eq 0 && -n "$(get_compopts)" ]]; then
  if [[ $(cand_count) -gt 0 ]]; then
    record PASS "signals 'trap -' -> signal names produced ($(cand_count))"
  else
    record FAIL "signals 'trap -' emitted sentinel but NO signal candidates"
  fi
else
  record FAIL "signals 'trap -' crashed (rc=$_RC) or no __COMPOPTS sentinel"
fi

echo
echo "============================================================"
echo " COMMAND-CONTEXT scenario"
echo "============================================================"

# --- CMDCTX: echo a ; (empty current word) -> command names ---
run "$CMDCTX" "echo a ; " 9 ""
if [[ $_RC -eq 0 && -n "$(get_compopts)" ]]; then
  if [[ $(cand_count) -gt 0 ]] && cand_has "git" && cand_has "ls"; then
    record PASS "cmdctx 'echo a ; ' -> command names via compgen -c (git,ls present)"
  elif [[ $(cand_count) -gt 0 ]]; then
    record PASS "cmdctx 'echo a ; ' -> $(cand_count) command names produced"
  else
    record FAIL "cmdctx 'echo a ; ' emitted sentinel but NO command names"
  fi
else
  record FAIL "cmdctx 'echo a ; ' crashed (rc=$_RC) or no __COMPOPTS sentinel"
fi

# --- CMDCTX: ls && (empty current word) -> command names ---
run "$CMDCTX" "ls && " 6 ""
if [[ $_RC -eq 0 && -n "$(get_compopts)" ]]; then
  if [[ $(cand_count) -gt 0 ]] && cand_has "git"; then
    record PASS "cmdctx 'ls && ' -> command names via compgen -c (git present)"
  elif [[ $(cand_count) -gt 0 ]]; then
    record PASS "cmdctx 'ls && ' -> $(cand_count) command names produced"
  else
    record FAIL "cmdctx 'ls && ' emitted sentinel but NO command names"
  fi
else
  record FAIL "cmdctx 'ls && ' crashed (rc=$_RC) or no __COMPOPTS sentinel"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
echo "============================================================"
echo " SUMMARY"
echo "============================================================"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "------------------------------------------------------------"
echo "  PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP"
echo "============================================================"

# Non-zero exit only on real FAIL (SKIP is acceptable).
if [[ $FAIL -gt 0 ]]; then
  exit 1
fi
exit 0
