# shell_complete_redir.sh
#
# REDIRECTION completion scenario. The previous token is a redirection operator
# (> >> < << <<< &> &>> or numeric-fd form 2> 1>>) which forces a FILENAME
# context for the current word. Java's cache context for these is "F" so this
# scenario never collides with the command-context ("C") script.
# Used in COMMAND mode only.
# NOTE: shared helpers are prepended by Java from shell_complete_common.sh.

COMP_LINE="$1"
COMP_POINT="$2"
CURRENT_WORD="$3"

__termux_tokenize "$COMP_LINE" "$COMP_POINT"
words=("${COMP_WORDS[@]}")
CURRENT_WORD=${COMP_WORDS[COMP_CWORD]:-}

__termux_file_fallback "$CURRENT_WORD"
printf '%s\0' "${COMPREPLY[@]}"
