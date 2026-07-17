# shell_complete_cmdctx.sh
#
# COMMAND-CONTEXT completion scenario (cword>0 but the word under the cursor is
# a command name, not an argument). Covers:
#   - empty / whitespace-only line (-E)            -> compgen -c
#   - after a separator: ; | & && || |& ( ) {      -> compgen -c
#   - process substitution: <( >( &>(              -> compgen -c (inner command)
#   - keywords: time coproc do then else elif, and NAME= assignment
# Used in COMMAND mode only (MESSAGE mode never offers command names).
# NOTE: shared helpers are prepended by Java from shell_complete_common.sh.

COMP_LINE="$1"
COMP_POINT="$2"
CURRENT_WORD="$3"

__termux_tokenize "$COMP_LINE" "$COMP_POINT"
words=("${COMP_WORDS[@]}")
CURRENT_WORD=${COMP_WORDS[COMP_CWORD]:-}

__emit_command_names() {
  COMPREPLY=($(compgen -c -- "$CURRENT_WORD" 2>/dev/null | head -n "${__MAX_CANDIDATES}"))
  __emit_opts nospace nosort noquote
  printf '%s\0' "${COMPREPLY[@]}"
}

# -E context: empty/whitespace-only line -> command names.
if [[ -z ${COMP_LINE//[[:space:]]/} ]]; then
  __emit_command_names; exit 0
fi

# -I context: previous token is a command separator -> command name.
case "${COMP_WORDS[COMP_CWORD-1]:-}" in
  ";"|"|"|"&"|"&&"|"||"|"|&"|"("|")"|"{")
    __emit_command_names; exit 0;;
esac

# Keyword / assignment words also begin a command context.
if [[ ${COMP_CWORD} -gt 0 ]]; then
  __prev=${COMP_WORDS[COMP_CWORD-1]}
  if [[ $__prev == time || $__prev == coproc || $__prev == do
     || $__prev == then || $__prev == else || $__prev == elif
     || $__prev == [@a-zA-Z_][@a-zA-Z0-9_]*= ]]; then
    __emit_command_names; exit 0
  fi
fi

# Process substitution inner word -> command name.
if [[ ${COMP_CWORD} -gt 0 ]]; then
  case "${COMP_WORDS[COMP_CWORD-1]:-}" in
    "<("|">("|"&>(",) __emit_command_names; exit 0;;
  esac
fi

# Fallback: shouldn't reach here for a true command context; emit command names.
__emit_command_names
