# shell_complete_fast.sh
#
# FAST PATH completion script (command-name / first word, cword==0).
# Invoked as:  bash --norc --noprofile -c SCRIPT -- "<currentWord>"
#
# It lists command names via `compgen -c` (5-10x faster than sourcing
# bash-completion) and classifies each result so the UI can mark
# aliases / builtins / functions. Candidates are NUL-delimited and
# emitted as  name<TAB>category  where category is:
#   0 = external/file, 1 = alias, 2 = builtin, 3 = function.
#
# Max candidates are bounded by ${__MAX_CANDIDATES}.
#
# The shared header (process-group trap, .bashrc alias scan, COMP_WORDBREAKS)
# is prepended by Java from shell_complete_common.sh at load time, so this file
# is self-contained under `bash -c` and must NOT source common itself. It only
# adds the command-name specific alias extraction on top of the common header.

set +H 2>/dev/null                      # disable histexpand ('!' -> history)
shopt -s expand_aliases 2>/dev/null
[[ -f "$HOME/.bash_aliases" ]] && source "$HOME/.bash_aliases" 2>/dev/null
# Inline .bashrc aliases (the common case), without running the whole file.
if [[ -f "$HOME/.bashrc" ]]; then
  while IFS= read -r __line; do
    [[ "$__line" == alias\ * || "$__line" == *';'alias\ * || "$__line" == *'|alias '* ]] \
      && eval "$__line" </dev/null >/dev/null 2>&1
  done < "$HOME/.bashrc"
fi

while IFS= read -r __c; do
  [[ -z "$__c" ]] && continue
  __t=$(type -t "$__c" 2>/dev/null); __cat=0
  case "$__t" in
    alias)    __cat=1;;
    builtin)  __cat=2;;
    function) __cat=3;;
    *)        __cat=0;;   # file / hashed -> external
  esac
  printf '%s\t%s\0' "$__c" "$__cat"
done < <(compgen -c -- "$1" 2>/dev/null | head -n "${__MAX_CANDIDATES}")
