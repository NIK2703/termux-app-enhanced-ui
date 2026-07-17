# shell_complete_vars.sh
#
# VARIABLE-NAME completion scenario. Covers $VAR, ${VAR}, and the builtins
# export/unset/readonly/declare/typeset when completing their variable arg.
# Used in COMMAND mode; in MESSAGE mode only when the last word starts with '$'
# (treated as a path-relevant expansion).
# NOTE: shared helpers are prepended by Java from shell_complete_common.sh.

COMP_LINE="$1"
COMP_POINT="$2"
CURRENT_WORD="$3"

__termux_tokenize "$COMP_LINE" "$COMP_POINT"
words=("${COMP_WORDS[@]}")
CURRENT_WORD=${COMP_WORDS[COMP_CWORD]:-}

# $VAR / ${VAR} forms.
case "$CURRENT_WORD" in
  \${*|\$*) __sigil='$'; __bare=${CURRENT_WORD#\$}; __close='';
           [[ $CURRENT_WORD == \${* ]] && __close='}'; __bare=${__bare#\{};
           COMPREPLY=($(compgen -v -- "$__bare" 2>/dev/null | head -n "${__MAX_CANDIDATES}" \
                      | while IFS= read -r __v; do printf '%s%s%s\0' "$__sigil" "$__v" "$__close"; done))
           __emit_opts noquote nospace nosort; printf '%s\0' "${COMPREPLY[@]}"; exit 0;;
esac

# export|unset|readonly|declare|typeset variable name.
case "${words[0]:-}" in
  export|unset|readonly|declare|typeset)
    COMPREPLY=($(compgen -e -v -- "$CURRENT_WORD" 2>/dev/null | head -n "${__MAX_CANDIDATES}"))
    __emit_opts noquote nospace nosort; printf '%s\0' "${COMPREPLY[@]}"; exit 0;;
esac

# Fallback: shouldn't reach here; emit variable names anyway.
COMPREPLY=($(compgen -v -- "$CURRENT_WORD" 2>/dev/null | head -n "${__MAX_CANDIDATES}"))
__emit_opts noquote nospace nosort; printf '%s\0' "${COMPREPLY[@]}"
