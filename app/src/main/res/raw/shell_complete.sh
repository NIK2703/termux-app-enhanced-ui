# shell_complete.sh
#
# PERSISTENT completion dispatcher. ONE long-lived bash process serves every
# completion request for the life of the provider, eliminating the per-request
# spawn + bash-completion load + .bashrc scan that dominated the old design
# (~160-280ms each). The Java side lazy-starts this once via
#   bash --norc --noprofile shell_complete.sh
# and drives it over stdin/stdout.
#
# WIRE PROTOCOL
#   Request  (one line, \n-terminated, fields split by \x01):
#       TYPE \x01 WORD \x01 CWD \x01 COMP_LINE
#     TYPE is one of: CMD PATH FILE REDIR VAR SIGNAL COMPSPEC
#     WORD is the exact completable token (already sigil/prefix-shaped by Java;
#          e.g. VAR arrives WITHOUT the leading '$', SIGNAL arrives as SIGxxx).
#     CWD  is the working directory for file/path resolution (may be empty).
#     COMP_LINE is the full command line (used only by COMPSPEC).
#   Response (to stdout):
#       zero or more NUL-terminated candidates
#       at most one  "__COMPOPTS:opt,opt,...\0"  side-channel record
#       a literal line  "__END__\n"  marking end-of-response
#
# SPEED CONTRACT: the main loop does NOT source bash-completion and does NOT
# scan .bashrc. All string shaping (VAR sigils, SIGNAL SIG-prefix, COMPSPEC
# wrapper-skip, NAME= assignment strip) is done in Java; bash runs exactly ONE
# compgen/glob per request. COMPSPEC is best-effort: it only consults a compspec
# already registered in THIS session (see __do_compspec); it never triggers the
# lazy loader, so a real programmable completion that was never registered
# returns empty (Java then falls back locally). This is the deliberate
# speed-over-faithfulness trade-off.

export LC_ALL=${LC_ALL:-C.UTF-8}
export LANG=${LANG:-C.UTF-8}

set +H 2>/dev/null                      # disable histexpand ('!' -> history)
shopt -s extglob 2>/dev/null
shopt -s nullglob dotglob 2>/dev/null

__MAX_CANDIDATES="${__MAX_CANDIDATES:-300}"

# Default word-break set. A -F programmable completer (e.g. git) reads
# COMP_WORDS/COMP_CWORD and expects them to be tokenized with readline semantics
# (spaces AND the word-break metacharacters produce their own word slots). Match
# the interactive COMP_WORDBREAKS so the compspec computes the same context it
# would in a real shell — this is what makes `git commit --am` yield nothing
# (as bash does) instead of a spurious `--amend`.
if [[ -z ${COMP_WORDBREAKS:-} ]]; then
  COMP_WORDBREAKS=$' \t\n'"'"'()<>;|&=:@~'
fi

# ---- Readline-faithful tokenizer: split COMP_LINE into COMP_WORDS / COMP_CWORD
# honoring quotes, escaped whitespace AND COMP_WORDBREAKS. Ported behaviourally
# from the old shell_complete_common.sh so a -F completer sees the exact
# COMP_WORDS/COMP_CWORD it did before the persistent migration.
__termux_tokenize() {
  local line=$1 point=$2
  COMP_WORDS=(); COMP_CWORD=0
  local WB=${COMP_WORDBREAKS:-$' \t\n"'"'"'()<>;|&=:@~'}
  __is_wb() { [[ "$WB" == *"$1"* ]]; }
  local i=0 n=${#line} inq=0 indq=0 tok=""
  while (( i < n )); do
    local c=${line:i:1}
    if (( inq )); then
      tok+=$c; (( i++ ));
      [[ $c == "'" ]] && inq=0
    elif (( indq )); then
      tok+=$c; (( i++ ));
      [[ $c == '"' ]] && indq=0
    else
      case $c in
        "'") inq=1; tok+=$c; (( i++ ));;
        '"') indq=1; tok+=$c; (( i++ ));;
        "\\") tok+=$c; (( i++ )); if (( i < n )); then tok+=${line:i:1}; (( i++ )); fi;;
        *) if __is_wb "$c"; then
             if [[ "$c" == "~" && $((i+1)) -lt $n && ${line:i+1:1} == / ]]; then
               tok+="$c/"; (( i+=2 ));
             elif [[ "$c" == [=:@~\(\[\<\>] ]]; then
               [[ -n $tok ]] && COMP_WORDS+=("$tok"); tok=""
               COMP_WORDS+=("$c")
             else
               if [[ -n $tok ]]; then COMP_WORDS+=("$tok"); tok=""; fi
               local j=$i; while (( j < n )) && __is_wb "${line:j:1}" \
                                  && [[ ${line:j:1} != [=:@~\(\[\<\>] ]]; do (( j++ )); done
               COMP_WORDS+=(""); i=$j
             fi
           else tok+=$c; (( i++ )); fi;;
      esac
    fi
    if (( i <= point )) && (( ${#tok} > 0 || ${#COMP_WORDS[@]} > 0 )); then
      COMP_CWORD=${#COMP_WORDS[@]}
    fi
  done
  if [[ -n $tok ]]; then COMP_WORDS+=("$tok"); fi
  if (( point >= n )) && [[ -z $tok ]]; then COMP_CWORD=${#COMP_WORDS[@]}; fi
}

# Emit resolved -o comp-options as a side-channel sentinel the Java reader parses.
__emit_opts() {
  local o acc=""
  for o in "$@"; do acc+="$o,"; done
  [[ -n $acc ]] && printf '__COMPOPTS:%s\0' "${acc%,}"
}

# ---- File/dir fallback (tilde/hidden aware). $1 = word token, $2 = cwd.
# Ported verbatim (behaviourally) from the old shell_complete_common.sh so the
# candidate set + trailing-slash directory marking stays identical.
__file_fallback() {
  __emit_opts filenames
  local __w=$1 __cwd=$2
  case "$__w" in
    \~)   __w=$HOME ;;
    \~/*) __w=${HOME}${__w:1} ;;
    \~*)  local __u=${__w#\~}; __u=${__u%%/*}
          local __h; __h=$(getent passwd "$__u" 2>/dev/null | cut -d: -f6)
          [[ -z $__h ]] && __h=$HOME; __w=$__h${__w:1+${#__u}+1} ;;
  esac
  local __dir=${__w%/*}
  if [[ -z $__dir ]]; then
    if [[ "$__w" == /* ]]; then __dir=/; else __dir=.; fi
  fi
  local __prefix=${__w##*/}
  # A relative parent must resolve against the request CWD, not this process's
  # own cwd (which never changes). Absolute/tilde-expanded parents are used as-is.
  local __base="$__dir"
  if [[ "$__dir" != /* && -n "$__cwd" ]]; then
    __base="${__cwd%/}/$__dir"
  fi
  local __glob="${__base}/${__prefix}*"
  local __n=0 __p __rel
  while IFS= read -r __p; do
    [[ -z "$__p" ]] && continue
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    # Re-derive the token the user actually typed (relative to __dir, not __base)
    # so the Java-side prefix match against the typed word holds.
    if [[ "$__dir" == /* || -z "$__cwd" ]]; then
      __rel="$__p"
    else
      __rel="${__p#${__cwd%/}/}"
    fi
    if [[ -d "$__p" ]]; then
      printf '%s/\0' "$__rel"
    else
      printf '%s\0' "$__rel"
    fi
    __n=$((__n+1))
  done < <(compgen -G "$__glob" 2>/dev/null)
}

# ---- CMD / CMDCTX: command names with type classification.
__do_cmd() {
  local __word=$1 __c __t __cat
  while IFS= read -r __c; do
    [[ -z "$__c" ]] && continue
    __t=$(type -t "$__c" 2>/dev/null); __cat=0
    case "$__t" in
      alias)    __cat=1;;
      builtin)  __cat=2;;
      function) __cat=3;;
      *)        __cat=0;;
    esac
    printf '%s\t%s\0' "$__c" "$__cat"
  done < <(compgen -c -- "$__word" 2>/dev/null | head -n "${__MAX_CANDIDATES}")
  __emit_opts nospace nosort noquote
}

# ---- VAR: variable names. WORD arrives already stripped of its $ / ${ sigil by
# Java, which also re-wraps the emitted candidates, so here we emit bare names.
__do_var() {
  local __word=$1
  compgen -v -- "$__word" 2>/dev/null | head -n "${__MAX_CANDIDATES}" \
    | while IFS= read -r __v; do printf '%s\0' "$__v"; done
  __emit_opts noquote nospace nosort
}

# ---- SIGNAL: WORD arrives as a SIGxxx prefix (Java maps -USR1 -> SIGUSR1).
# We emit the matching signal names WITHOUT the SIG prefix; Java re-prepends '-'.
__do_signal() {
  local __word=$1
  compgen -A signal -- "$__word" 2>/dev/null | sed 's/^SIG//' \
    | head -n "${__MAX_CANDIDATES}" | while IFS= read -r __s; do printf '%s\0' "$__s"; done
  __emit_opts noquote nospace nosort
}

# ---- Lazy, one-time source of the bash-completion helper stack. Only invoked
# from the COMPSPEC path when a command has NO compspec registered yet, so the
# common CMD/PATH/VAR/SIGNAL requests never pay this cost. After the first call
# the loader (_completion_loader) and any autoloaded compspecs stay resident for
# the life of this persistent process, so subsequent COMPSPEC requests are fast.
__BC_LOADED=0
__ensure_bc() {
  [[ $__BC_LOADED -eq 1 ]] && return 0
  __BC_LOADED=1
  local __bc
  for __bc in "${TERMUX_PREFIX:-/data/data/com.termux/files/usr}/share/bash-completion/bash_completion" \
             "/usr/share/bash-completion/bash_completion"; do
    [[ -f "$__bc" ]] && { source "$__bc" 2>/dev/null; break; }
  done
}

# ---- COMPSPEC: best-effort. WORD = real command's current word, CMD passed in
# COMP_LINE first token = the (already wrapper-resolved) command name. Only runs
# a compspec already registered in this session; never triggers the lazy loader
# (that would re-introduce the bash-completion load cost). Falls back to empty
# so Java can path/command-fallback locally.
__do_compspec() {
  local __word=$1 __cwd=$2 __line=$3
  # Resolve through command wrappers (sudo/xargs/env/time/…) and any inline
  # NAME=value assignments, so `sudo systemctl sta` runs systemctl's compspec
  # (S3/S4/S5). Mirrors the original compspec template's __wrappers loop.
  local __wrappers=" env sudo xargs time nohup nice exec stdbuf timeout ionice setsid "
  local __words=($__line)
  local __cmd=${__words[0]:-}
  local __i=0
  while [[ -n $__cmd && " $__wrappers " == *" $__cmd "* ]]; do
    __i=$((__i + 1))
    __cmd=${__words[__i]:-}
    [[ -z $__cmd ]] && break
    # skip an inline NAME=value assignment that follows the wrapper
    while [[ $__cmd == [A-Za-z_]*=* ]]; do
      __i=$((__i + 1))
      __cmd=${__words[__i]:-}
      [[ -z $__cmd ]] && break 2
    done
  done

  COMPREPLY=()
  local __spec
  __spec=$(complete -p -- "$__cmd" 2>/dev/null)
  # When no compspec is registered for this command yet, lazy-load it via the
  # bash-completion helper (matches the original compspec template, which called
  # _completion_loader). This is what makes a real programmable completion such
  # as `git --` yield its option set. The helper stack is sourced at most once
  # per process (see __ensure_bc); if bash-completion is not installed this is a
  # no-op and the command-name fallback below still applies.
  if [[ -z $__spec && -n $__cmd ]]; then
    __ensure_bc
    if type -t _completion_loader >/dev/null 2>&1; then
      _completion_loader "$__cmd" 2>/dev/null
      __spec=$(complete -p -- "$__cmd" 2>/dev/null)
    fi
  fi
  if [[ -n $__spec ]]; then
    local __opts
    __opts=$(awk '{for(i=1;i<=NF;i++) if($i=="-o") printf "%s,", $(i+1)}' <<<"$__spec")
    if [[ $__spec =~ (^| )-F([ $'\t']|$) ]]; then
      local __func
      __func=$(awk '{for(i=1;i<=NF;i++) if($i=="-F"){print $(i+1);exit}}' <<<"$__spec")
      if [[ -n $__func ]] && type -t "$__func" >/dev/null 2>&1; then
        # Populate the vars a -F completer reads with readline-faithful
        # tokenization so the compspec computes the same context bash would.
        COMP_LINE="$__line"; COMP_POINT=${#__line}
        __termux_tokenize "$__line" "$COMP_POINT"
        "$__func" 2>/dev/null
      fi
    else
      local __rest=${__spec#complete }
      __rest=${__rest% [^ ]*}
      COMPREPLY=($(eval "compgen $__rest -- '$__word'" 2>/dev/null))
    fi
    [[ -n $__opts ]] && { __opts=$(echo "$__opts" | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd, -); [[ -n $__opts ]] && printf '__COMPOPTS:%s\0' "$__opts"; }
  fi

  # Command-name fallback (S9): when the compspec produced nothing AND the FIRST
  # token of the line is a wrapper (sudo/env/xargs/time/…), the wrapper's argument
  # is itself a command name → compgen -c. This mirrors the original compspec
  # template, which tested the ORIGINAL first token ("${1%% *}") in a final
  # empty-COMPREPLY fallback. It must NOT be an `elif` on "$__spec": the lazy
  # loader may register a minimal placeholder compspec (-F _comp_complete_minimal)
  # for an unknown argument like `gi`, which yields nothing yet made $__spec
  # non-empty; the wrapper fallback still has to run in that case. Never emit for
  # path-like or assignment-like words.
  if [[ ${#COMPREPLY[@]} -eq 0 ]]; then
    local __first=${__line%% *}
    if [[ " $__wrappers " == *" $__cmd "* || " $__wrappers " == *" $__first "* ]]; then
      case "$__word" in
        /*|~*|.*|*/*) ;;
        *[!A-Za-z0-9_.,+-]*) ;;
        *=*) ;;
        *) COMPREPLY=($(compgen -c -- "$__word" 2>/dev/null | head -n "${__MAX_CANDIDATES}")) ;;
      esac
    fi
  fi
  local __n=0 __c
  for __c in "${COMPREPLY[@]}"; do
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    printf '%s\0' "$__c"; __n=$((__n+1))
  done
}

# ---- Main dispatch loop. Read one \x01-delimited request per line.
while IFS= read -r __req; do
  # Split on \x01 into TYPE / WORD / CWD / COMP_LINE.
  IFS=$'\x01' read -r __type __word __cwd __line <<<"$__req"
  case "$__type" in
    CMD)      __do_cmd "$__word" ;;
    PATH|FILE|REDIR) __file_fallback "$__word" "$__cwd" ;;
    VAR)      __do_var "$__word" ;;
    SIGNAL)   __do_signal "$__word" ;;
    COMPSPEC) __do_compspec "$__word" "$__cwd" "$__line" ;;
    *)        : ;;  # unknown TYPE -> empty response
  esac
  # A leading newline guarantees __END__ lands on its OWN line even though the
  # candidate records above are NUL-terminated (no trailing newline), so the
  # Java reader's readLine()-based framing sees an exact "__END__" line.
  printf '\n__END__\n'
done
