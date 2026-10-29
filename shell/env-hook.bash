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
# A prompt hook cannot always be interrupted, so it never installs or waits:
# a project needing a missing version is reported, and eval "$(breto env)"
# installs it.

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

__breto_env() {
    local dir=$PWD code enter
    while [ -n "$dir" ] && [ ! -f "$dir/.breto.edn" ] && [ ! -f "$dir/.breto.local.edn" ]; do
        dir=${dir%/*}
    done
    if [ -n "$dir" ]; then
        # The stamp belongs to this shell, so runs of other shells in the same
        # project do not count, and a failed run is retried once a file changes
        # or the shell enters the project again, not on every prompt.
        if [ "$dir" != "${__breto_project-}" ] || __breto_changed "$dir"; then
            if [ -z "${__breto_stamp-}" ] || [ ! -f "$__breto_stamp" ]; then
                __breto_stamp=$(mktemp "${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/breto-hook.XXXXXX") || return
            fi
            touch "$__breto_stamp"
            enter=
            [ "$dir" != "${__breto_project-}" ] && enter=--enter
            if code=$(breto env --no-install $enter --dir "$dir"); then
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
