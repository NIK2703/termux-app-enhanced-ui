# shell_complete_common.sh
#
# Shared helpers for the per-scenario completion scripts. Sourced by each
# shell_complete_*.sh (except the fast path) so the duplication of the
# tokenizer, the file fallback and the __COMPOPTS emitter lives in ONE place.
#
# Every scenario script is invoked as:
#   bash --norc --noprofile -c SCRIPT -- "<compLine>" "<compPoint>" "<currentWord>"
# so $1=COMP_LINE, $2=COMP_POINT, $3=CURRENT_WORD.

__MAX_CANDIDATES="${__MAX_CANDIDATES:-300}"

# Reap any child this script spawns (a -F/-C completer, a sourced .bashrc line)
# when it exits, so an orphaned subprocess can't hold the stdout pipe and starve
# the single caller thread. We run in our own process group (set -m) and kill it.
set -m 2>/dev/null
trap 'kill -9 -$$ 2>/dev/null' EXIT

set +H 2>/dev/null                      # disable histexpand ('!' -> history)
shopt -s extglob 2>/dev/null            # enable extended globbing for compspecs
shopt -s prog_completion 2>/dev/null

# Default word-break set (readline normally exports COMP_WORDBREAKS to
# interactive shells; our non-interactive bash -c may lack it). Using a literal
# single-quoted string avoids the nested-quote parsing pitfalls of the original
# $'...' form when this file is sourced rather than fed to `bash -c`.
if [[ -z ${COMP_WORDBREAKS:-} ]]; then
  COMP_WORDBREAKS=$' \t\n'"'"'()<>;|&=:@~'
fi

# Load bash-completion helpers from the Termux prefix (and the conventional
# /usr path, which is normally a symlink to it).
for __bc in "${TERMUX_PREFIX}/share/bash-completion/bash_completion" \
           "/usr/share/bash-completion/bash_completion"; do
  [[ -f "$__bc" ]] && source "$__bc" 2>/dev/null
done

# Safely pick up the user's custom compspecs from ~/.bashrc WITHOUT executing
# the whole file (banners, neofetch, tmux, etc.). Extract only the lines that
# register completions or source completion files, eval just those, silenced.
# We reject lines containing shell metacharacters that could spawn a process or
# do I/O (backticks, $(), |, ;, &), and read from /dev/null so an interactive
# completer can never block on stdin. `timeout` is best-effort (falls back to a
# per-line `read -t 1` bound when the binary is missing).
if [[ -f "$HOME/.bashrc" ]]; then
  __scan='
    while IFS= read -r -t 1 __line; do
      [[ "$__line" =~ ^[[:space:]]*(complete|source|\.|compdef|_.*\(\)|complete\ -|bash-complete|register_completion) ]] \
        && [[ "$__line" != *"`"* ]] && [[ "$__line" != *"$("* ]] && [[ "$__line" != *"|"* ]] \
        && [[ "$__line" != *";"* ]] && [[ "$__line" != *"&"* ]] \
        && eval "$__line" </dev/null >/dev/null 2>&1
    done < "$HOME/.bashrc"
  '
  if command -v timeout >/dev/null 2>&1; then
    timeout 1 bash -c "$__scan" 2>/dev/null
  else
    bash -c "$__scan" 2>/dev/null
  fi
fi

# Register the standard lazy loader as the default completion so commands
# without an explicit compspec still get smart defaults.
complete -D -F _completion_loader -o bashdefault -o default 2>/dev/null

# Helper that emits the resolved -o comp-options as a side-channel sentinel
# line. The Java reader parses "__COMPOPTS:..." separately.
__emit_opts() { local o acc=""; for o in "$@"; do acc+="$o,"; done;
  [[ -n $acc ]] && printf '__COMPOPTS:%s\0' "${acc%,}"; }

# ---- Tokenizer: split COMP_LINE into COMP_WORDS / COMP_CWORD honoring
#      quotes, escaped whitespace AND COMP_WORDBREAKS (readline semantics).
__termux_tokenize() {
  local line=$1 point=$2
  COMP_WORDS=(); COMP_CWORD=0
  local WB=${COMP_WORDBREAKS:-$' \t\n"'"'"'()<>;|&=:@~'}
  __is_wb() { [[ "$WB" == *"$1"* ]]; }
  local i=0 n=${#line} cur= inq=0 indq=0 tok=""
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
             # Keep '~/' glued (matches readline tilde-expansion context).
             if [[ "$c" == "~" && $((i+1)) -lt $n && ${line:i+1:1} == / ]]; then
               tok+="$c/"; (( i+=2 ));
             # Suffix break chars ( = : @ ~ ( [ < > ) attach to the PRECEDING
             # word as their own word, reproducing readline's COMP_WORDS.
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

# ---- File/dir fallback (tilde/hidden aware) used by path + redirection + the
#      generic compspec fallback. $1 = the current word token.
__termux_file_fallback() {
  __emit_opts filenames
  local __w=$1
  case "$__w" in
    \~)  __w=$HOME ;;
    \~/*) __w=${HOME}${__w:1} ;;
    \~*) __u=${__w#\~}; __u=${__u%%/*};
         __h=$(getent passwd "$__u" 2>/dev/null | cut -d: -f6);
         [[ -z $__h ]] && __h=$HOME; __w=$__h${__w:1+${#__u}+1} ;;
  esac
  # Resolve the parent directory and the partial name being completed.
  # For an absolute path like "/da" the only slash is the leading one, so
  # "${__w%/*}" is empty → parent must be "/" (not "."). For a relative path the
  # parent is "." when empty.
  local __dir=${__w%/*}
  if [[ -z $__dir ]]; then
    if [[ "$__w" == /* ]]; then __dir=/; else __dir=.; fi
  fi
  local __prefix=${__w##*/}
  shopt -s nullglob dotglob
  # NOTE: this Android/bash port's `compgen -f/-d` does NOT expand absolute paths
  # (it silently returns nothing for any path whose first readable segment is "/",
  # e.g. /da, /data/da — only `compgen -G` glob expansion works for them). So we
  # drive completion through `compgen -G` (explicit glob) and classify each hit as
  # a directory (append "/") or a file, which works for absolute, relative, tilde,
  # and hidden paths alike.
  local __glob="${__dir}/${__prefix}*"
  local __n=0
  # Read candidates line-by-line (compgen -G separates with newlines) with IFS=
  # cleared and -r so paths containing spaces are kept intact (S8/S10). Newline
  # inside a filename is not handled, but is vanishingly rare on Android.
  while IFS= read -r __p; do
    [[ -z "$__p" ]] && continue
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    if [[ -d "$__p" ]]; then
      # Return the FULL path (so the Java-side prefix match against the typed
      # token holds) with a trailing slash marking it as a directory.
      printf '%s/\0' "$__p"
    else
      printf '%s\0' "$__p"
    fi
    __n=$((__n+1))
  done < <(compgen -G "$__glob" 2>/dev/null)
}
