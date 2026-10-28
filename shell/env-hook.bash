# SPDX-FileCopyrightText: The breto Authors
# SPDX-License-Identifier: 0BSD

# Runs breto env when the shell enters a directory with a .breto.edn above it,
# or that file or a group file it names changes, and takes the project's bin
# directory off PATH when the shell leaves the project. Source it from an
# interactive bash.
#
# A prompt hook cannot always be interrupted, so it never installs or waits:
# a project needing a missing version is reported, and eval "$(breto env)"
# installs it.

# Whether the project file or a file it takes packages from, such as a group
# in a shared registry, changed since the last run.
__breto_changed() {
    local file files
    [ "$1/.breto.edn" -nt "$__breto_stamp" ] && return 0
    IFS=: read -r -a files <<< "$BRETO_ENV_FILES"
    for file in "${files[@]}"; do
        [ -n "$file" ] && [ "$file" -nt "$__breto_stamp" ] && return 0
    done
    return 1
}

__breto_env() {
    local dir=$PWD code
    while [ -n "$dir" ] && [ ! -f "$dir/.breto.edn" ]; do
        dir=${dir%/*}
    done
    if [ -n "$dir" ]; then
        # A failed run stamps a marker of this shell, so it is retried once the
        # file changes or the shell enters the project again, not on every prompt.
        if [ "$dir" != "$__breto_project" ] || __breto_changed "$dir"; then
            __breto_project=$dir
            if code=$(breto env --no-install --dir "$dir"); then
                eval "$code"
                __breto_stamp=$BRETO_ENV_BIN
            else
                __breto_stamp=${XDG_RUNTIME_DIR:-/tmp}/breto-hook-$$
                : > "$__breto_stamp"
            fi
        fi
    elif [ -n "$__breto_project" ]; then
        if [ -n "$BRETO_ENV_BIN" ]; then
            PATH=":$PATH:"
            PATH=${PATH//":$BRETO_ENV_BIN:"/:}
            PATH=${PATH#:}
            PATH=${PATH%:}
        fi
        unset BRETO_ENV_BIN BRETO_ENV_FILES BRETO_PROJECT __breto_project __breto_stamp
    fi
}

case ";${PROMPT_COMMAND};" in
    *";__breto_env;"*) ;;
    *) PROMPT_COMMAND="__breto_env${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
esac
