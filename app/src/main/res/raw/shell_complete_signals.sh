# shell_complete_signals.sh
#
# SIGNAL / JOB completion scenario. Covers kill -<signal>, trap -<signal>, and
# kill/trap %<job>. Used in COMMAND mode only (a message line won't start with
# a signal/job token in a path-relevant way).
# NOTE: shared helpers are prepended by Java from shell_complete_common.sh.

COMP_LINE="$1"
COMP_POINT="$2"
CURRENT_WORD="$3"

__termux_tokenize "$COMP_LINE" "$COMP_POINT"
words=("${COMP_WORDS[@]}")
CURRENT_WORD=${COMP_WORDS[COMP_CWORD]:-}

if [[ ${COMP_CWORD} -gt 0 ]]; then
  case "${words[0]:-}" in
    kill|trap) case "$CURRENT_WORD" in
      -*) # compgen -A signal matches the full name incl. the SIG prefix, so map
          # the user's "-USR"/"-HUP" onto "SIGUSR"/"SIGHUP", then strip SIG and
          # re-prepend the user's "-" so the inserted token is e.g. "-USR1".
          __sigp=${CURRENT_WORD#-}; case "$__sigp" in SIG*) :;; *) __sigp="SIG${__sigp}";; esac
          COMPREPLY=($(compgen -A signal -- "$__sigp" 2>/dev/null \
                        | sed 's/^SIG//' | sed 's/^/-/' | head -n "${__MAX_CANDIDATES}"))
         __emit_opts noquote nospace nosort; printf '%s\0' "${COMPREPLY[@]}"; exit 0;;
      %*) COMPREPLY=($(compgen -j -P '%' -- "${CURRENT_WORD#%}" 2>/dev/null | head -n "${__MAX_CANDIDATES}"))
         __emit_opts noquote nospace nosort; printf '%s\0' "${COMPREPLY[@]}"; exit 0;;
    esac;;
  esac
fi

# Fallback: emit signals (harmless when not applicable).
__sigp=${CURRENT_WORD#-}; case "$__sigp" in SIG*) :;; *) __sigp="SIG${__sigp}";; esac
COMPREPLY=($(compgen -A signal -- "$__sigp" 2>/dev/null | sed 's/^SIG//' | head -n "${__MAX_CANDIDATES}"))
__emit_opts noquote nospace nosort; printf '%s\0' "${COMPREPLY[@]}"
