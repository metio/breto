# SPDX-FileCopyrightText: The breto Authors
# SPDX-License-Identifier: 0BSD

# Runs breto env when the shell enters a directory with a .breto.edn or
# .breto.local.edn above it, or one of them or a group file they name
# changes. On entering, breto env --enter prints the project's commands and
# runs its :on-enter command. When the shell leaves the
# project, it takes the project's bin directory off PATH and gives the
# variables breto env set their earlier values back. Source it from an
# interactive bash.
#
# A prompt hook cannot always be interrupted, so it never installs or waits
# itself: when the project needs a version that is missing, a breto env in the
# background installs it, and the first prompt after it finishes switches to
# the project.

# Whether the project file or a file it takes packages from, such as a group
# in a shared registry, changed since this shell last ran breto env.
__breto_changed() {
    local file files
    [ "$1/.breto.edn" -nt "$__breto_stamp" ] && return 0
    [ "$1/.breto.local.edn" -nt "$__breto_stamp" ] && return 0
    IFS=: read -r -a files <<< "${BRETO_ENV_FILES-}"
    for file in "${files[@]}"; do
        [ -n "$file" ] && [ "$file" -nt "$__breto_stamp" ] && return 0
    done
    return 1
}

# Takes the project's bin directory off PATH and gives the variables breto env
# set their earlier values back.
__breto_leave() {
    if [ -n "${BRETO_ENV_BIN-}" ]; then
        PATH=":$PATH:"
        PATH=${PATH//":$BRETO_ENV_BIN:"/:}
        PATH=${PATH#:}
        PATH=${PATH%:}
    fi
    local var saved
    for var in ${BRETO_ENV_VARS-}; do
        saved=__breto_saved_$var
        if [ -n "${!saved+x}" ]; then
            export "$var=${!saved}"
            unset "$saved"
        else
            unset "$var"
        fi
    done
    unset BRETO_ENV_BIN BRETO_ENV_FILES BRETO_ENV_VARS BRETO_PROJECT __breto_project
}

# Installs what the project `$1` is missing with a breto env in the
# background, unless one is already running for it. The file in
# __breto_pending receives its exit code once it is done; its output goes to
# the file next to it.
__breto_install() {
    [ "${__breto_pending_dir-}" = "$1" ] && return
    __breto_pending=$(mktemp "${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/breto-install.XXXXXX") || return
    __breto_pending_dir=$1
    echo "[breto] installing what $1 needs in the background" >&2
    (breto env --dir "$1" > /dev/null 2> "$__breto_pending.log"; echo "$?" > "$__breto_pending") &
    disown
}

# Whether the background install for the project `$1` finished, so the
# project links what it installed. A failed install is reported once.
__breto_installed() {
    local status
    [ -n "${__breto_pending-}" ] && [ -s "$__breto_pending" ] || return 1
    status=$(< "$__breto_pending")
    if [ "$status" != 0 ]; then
        echo "[breto] installing what $__breto_pending_dir needs failed:" >&2
        cat "$__breto_pending.log" >&2
    fi
    rm -f "$__breto_pending" "$__breto_pending.log"
    [ "$__breto_pending_dir" = "$1" ] && [ "$status" = 0 ]
    status=$?
    unset __breto_pending __breto_pending_dir
    return "$status"
}

__breto_env() {
    local dir=$PWD code enter status
    while [ -n "$dir" ] && [ ! -f "$dir/.breto.edn" ] && [ ! -f "$dir/.breto.local.edn" ]; do
        dir=${dir%/*}
    done
    if [ -n "$dir" ]; then
        # The stamp belongs to this shell, so runs of other shells in the same
        # project do not count, and a failed run is retried once a file changes
        # or the shell enters the project again, not on every prompt.
        if [ "$dir" != "${__breto_project-}" ] || __breto_installed "$dir" || __breto_changed "$dir"; then
            if [ -z "${__breto_stamp-}" ] || [ ! -f "$__breto_stamp" ]; then
                __breto_stamp=$(mktemp "${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/breto-hook.XXXXXX") || return
            fi
            touch "$__breto_stamp"
            enter=
            [ "$dir" != "${__breto_project-}" ] && enter=--enter
            code=$(breto env --no-install $enter --dir "$dir" 2> "$__breto_stamp.err")
            status=$?
            if [ "$status" = 3 ]; then
                __breto_install "$dir"
            else
                cat "$__breto_stamp.err" >&2
            fi
            rm -f "$__breto_stamp.err"
            if [ "$status" = 0 ]; then
                eval "$code"
            elif [ "${BRETO_PROJECT-}" != "$dir" ]; then
                __breto_leave
            fi
            __breto_project=$dir
        fi
    elif [ -n "${__breto_project-}" ] || [ -n "${BRETO_ENV_BIN-}" ]; then
        __breto_leave
    fi
}

case ";${PROMPT_COMMAND};" in
    *";__breto_env;"*) ;;
    *) PROMPT_COMMAND="__breto_env${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
esac
