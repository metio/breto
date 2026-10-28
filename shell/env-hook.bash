# SPDX-FileCopyrightText: The breto Authors
# SPDX-License-Identifier: 0BSD

# Runs breto env when the shell enters a directory with a .breto.edn above it
# or the file changes, and takes the project's bin directory off PATH when the
# shell leaves the project. Source it from an interactive bash.

__breto_env() {
    local dir=$PWD code
    while [ -n "$dir" ] && [ ! -f "$dir/.breto.edn" ]; do
        dir=${dir%/*}
    done
    if [ -n "$dir" ]; then
        # A failed run stamps the file itself, which is never newer than
        # itself, so it is retried once the file changes and not on every prompt.
        if [ "$dir" != "$__breto_project" ] || [ "$dir/.breto.edn" -nt "$__breto_stamp" ]; then
            __breto_project=$dir
            if code=$(breto env --dir "$dir"); then
                eval "$code"
                __breto_stamp=$BRETO_ENV_BIN
            else
                __breto_stamp=$dir/.breto.edn
            fi
        fi
    elif [ -n "$__breto_project" ]; then
        if [ -n "$BRETO_ENV_BIN" ]; then
            PATH=":$PATH:"
            PATH=${PATH//":$BRETO_ENV_BIN:"/:}
            PATH=${PATH#:}
            PATH=${PATH%:}
        fi
        unset BRETO_ENV_BIN BRETO_PROJECT __breto_project __breto_stamp
    fi
}

case ";${PROMPT_COMMAND};" in
    *";__breto_env;"*) ;;
    *) PROMPT_COMMAND="__breto_env${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
esac
