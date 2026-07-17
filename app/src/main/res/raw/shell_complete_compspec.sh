# shell_complete_compspec.sh
#
# REAL COMPSPEC completion scenario. The current word is an argument/option of a
# command that has a registered programmable completion (-F function, -C command,
# -W/-A/-G wordlist). We lazy-load the compspec via _completion_loader, run it
# faithfully, capture -o options (incl. runtime compopt), and fall back to path
# completion when nothing is produced.
# Used in COMMAND mode only.
# NOTE: shared helpers are prepended by Java from shell_complete_common.sh.

COMP_LINE="$1"
COMP_POINT="$2"
COMP_KEY=9
COMP_TYPE=63                            # list-all: a dropdown wants the full candidate set

cmd="${1%% *}"   # first token of compLine = the command being completed

__termux_tokenize "$COMP_LINE" "$COMP_POINT"
CURRENT_WORD=${COMP_WORDS[COMP_CWORD]:-}
words=("${COMP_WORDS[@]}")
COMPREPLY=()

# Override compopt so a -F function's RUNTIME -o changes are captured.
__termux_compopt_acc=""
compopt() { local __a; while (( $# )); do case "$1" in
  -o) __termux_compopt_acc+="$2,"; shift 2;;
  +o) __termux_compopt_acc=${__termux_compopt_acc//$2,/}; shift 2;;
  *)  builtin compopt "$1" 2>/dev/null; shift;; esac; done; }

# Resolve the real completion command: skip known command-wrappers (sudo,
# xargs, env, ...) and any inline NAME=value assignments that precede it, so
# e.g. `sudo systemctl sta` completes `systemctl`'s compspec, not `sudo`'s
# (S3/S4/S5). Also sets __realcmd for the fallback below.
__wrappers=" env sudo xargs time nohup nice exec stdbuf timeout ionice setsid "
__cmd="${words[0]:-}"
__i=0
while [[ -n $__cmd && " $__wrappers " == *" $__cmd "* && $((__i+1)) -lt ${#words[@]} ]]; do
  __i=$((__i+1))
  __cmd="${words[__i]:-}"
  # skip an inline NAME=value assignment that follows the wrapper (e.g. env DEBUG=1)
  while [[ $__cmd == [A-Za-z_]*=* && $((__i+1)) -lt ${#words[@]} ]]; do
    __i=$((__i+1)); __cmd="${words[__i]:-}"
  done
done

if [[ -n $__cmd ]]; then
  if ! complete -p -- "$__cmd" >/dev/null 2>&1; then
    type -t _completion_loader >/dev/null 2>&1 && _completion_loader "$__cmd" 2>/dev/null
  fi
fi

# Resolve and run the compspec (handles -F, -C, -W/-A/-G).
if [[ -n $__cmd ]]; then
  spec=$(complete -p -- "$__cmd" 2>/dev/null)
  if [[ -n $spec ]]; then
    # Capture the resolved -o options (filenames/nospace/nosort/noquote).
    __opts=$(awk '{for(i=1;i<=NF;i++) if($i=="-o") printf "%s,", $(i+1)}' <<<"$spec")
    # -C external command: prints completions one per line.
    if [[ $spec =~ (^| )-C([ $'\t']|$) ]]; then
      __prog=$(awk '{for(i=1;i<=NF;i++) if($i=="-C"){print $(i+1);exit}}' <<<"$spec")
      [[ -n $__prog ]] && COMPREPLY=($(__prog 2>/dev/null))
    # -F function: modern bash invokes it WITHOUT positional args and the
    # function reads COMP_WORDS/COMP_CWORD itself.
    elif [[ $spec =~ (^| )-F([ $'\t']|$) ]]; then
      func=$(awk '{for(i=1;i<=NF;i++) if($i=="-F"){print $(i+1);exit}}' <<<"$spec")
      if [[ -n $func ]] && type -t "$func" >/dev/null 2>&1; then
        "$func" 2>/dev/null
      fi
    # -W / -A / -G style spec: re-run compgen with the same flags. The spec line
    # is e.g. "complete -o filenames -W '...' foo" — strip the leading "complete "
    # AND the trailing command name so compgen gets only its flags plus the
    # current word (compgen's optional trailing arg is the word). Keeping the
    # trailing "foo" would make compgen treat it as the word and error on
    # "-- '$3'", silently falling back to filename completion.
    else
      rest=${spec#complete }
      rest=${rest% [^ ]*}   # drop trailing command token
      COMPREPLY=($(eval "compgen $rest -- '$3'" 2>/dev/null))
    fi
  fi
fi

# Merge the static -o flags with any runtime compopt changes, then emit exactly
# one __COMPOPTS: sentinel (avoids double-emission).
__merged="${__opts}${__termux_compopt_acc}"
if [[ -n $__merged ]]; then
  __merged=$(echo "$__merged" | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd, -)
  [[ -n $__merged ]] && printf '__COMPOPTS:%s\0' "$__merged"
fi

# Fallback when nothing else produced:
if [[ ${#COMPREPLY[@]} -eq 0 ]]; then
  case "$CURRENT_WORD" in
    # Path-like token -> filesystem completion.
    /*|~*|.*|*/*) __termux_file_fallback "$CURRENT_WORD";;
    # Bare word with no metacharacters and no '=' -> treat as a command name
    # (e.g. the argument of sudo/xargs/env/time). Never offer command names for
    # tokens that look like assignments or contain shell metacharacters.
    *[!A-Za-z0-9_.,+-]*) ;;  # contains a metachar -> no fallback
    *=*) ;;                  # assignment-like -> no fallback
    *)
      wrappers=" sudo xargs env time nohup nice exec stdbuf timeout ionice setsid "
      if [[ " $wrappers " == *" $cmd "* ]]; then
        # These wrappers take a command as their argument, so falling back to
        # command-name completion is correct (S9).
        COMPREPLY=($(compgen -c -- "$CURRENT_WORD" 2>/dev/null | head -n "${__MAX_CANDIDATES}"))
      else
        # No compspec for a ./script-style command: try _command (default
        # completion) which mirrors bash's `-o default -D` (S9). For autoconf
        # ./configure this still yields nothing, matching real bash.
        if type -t _command >/dev/null 2>&1; then
          _command 2>/dev/null
        fi
      fi
      ;;
  esac
fi
printf '%s\0' "${COMPREPLY[@]}"
