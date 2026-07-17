# shell_complete_path.sh
#
# PATH / FILE / DIRECTORY completion scenario (cword>0, last word looks like a
# filesystem path). Covers: /data/da, ~/Doc, cp a.txt /tmp/, cat ~/My\ Docs/,
# ./foo, relative/paths. Tilde/hidden/dir-vs-file aware. Used both in COMMAND
# mode and in MESSAGE mode (where only path/var scenarios may consult bash).
# NOTE: shared helpers (tokenizer, file fallback, __COMPOPTS emitter, the
# process-group trap, ~/.bashrc alias scan) are prepended by Java from
# shell_complete_common.sh at load time, so this file must NOT source it (the
# script runs via `bash -c` where BASH_SOURCE is undefined).

COMP_LINE="$1"
COMP_POINT="$2"
CURRENT_WORD="$3"

__termux_tokenize "$COMP_LINE" "$COMP_POINT"
words=("${COMP_WORDS[@]}")
CURRENT_WORD=${COMP_WORDS[COMP_CWORD]:-}

# Path completion never needs a real compspec; emit the file fallback directly.
# For an unquoted NAME=value assignment (e.g. "PATH=/us", "export FOO=/ba"),
# the word being completed is the VALUE — strip the leading VAR= so we complete
# the value against the filesystem (S11). Quoted values keep their quote.
__path_word="$CURRENT_WORD"
if [[ "$__path_word" == [A-Za-z_]*=* ]]; then
  __assign_prefix="${__path_word%%=*}="
  __path_word="${__path_word#*=}"
fi
__termux_file_fallback "$__path_word"
if [[ -n ${__assign_prefix:-} ]]; then
  # Re-prepend the VAR= prefix to every candidate so the UI inserts the full token.
  __tmp=()
  for __c in "${COMPREPLY[@]}"; do __tmp+=("${__assign_prefix}${__c}"); done
  COMPREPLY=("${__tmp[@]}")
fi
printf '%s\0' "${COMPREPLY[@]}"
