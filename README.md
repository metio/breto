<!--
SPDX-FileCopyrightText: The breto Authors
SPDX-License-Identifier: 0BSD
 -->

# breto

breto installs prebuilt binaries from their upstream releases, keeps several versions of each side by side, and switches between them with one symlink. What to download and how to install it is declared as data: one EDN file per package. Nothing is built from source and nothing needs root.

## Requirements

breto runs on [babashka](https://babashka.org/). It unpacks zip archives and plain or gzip-compressed tar archives itself; `.tar.xz` and `.tar.bz2` need `tar` with `xz` or `bzip2`. Packages that require a signature need cosign, which breto installs itself when `cosign` is listed in `:packages`.

## Installation

Clone the repository and link the launcher onto your `PATH`:

```shell
git clone https://github.com/metio/breto.git ~/src/breto
ln -s ~/src/breto/breto ~/.local/bin/breto
```

The launcher runs `bb` against the `bb.edn` next to it, so the clone can live anywhere.

## Configuration

breto reads `$XDG_CONFIG_HOME/breto/config.edn` (`~/.config/breto/config.edn` by default), or the file given with `--config`. Every key is optional:

```clojure
{:registries ["~/src/breto/packages" "~/.config/breto/packages"]
 :packages ["helm" "kind" "kubectl"]
 :pins {"helm" "3.19.0"}
 :keep 3
 :prune true
 :delay 300
 :store "~/.local/share/breto/installations"
 :bin-dir "~/.local/bin"}
```

| Key | Meaning | Default |
| --- | --- | --- |
| `:registries` | Directories of package definitions. A later directory overrides an earlier one key by key, so an overlay can change a single key of a package; see [Overlays](#overlays). | none |
| `:packages` | What `update` and `outdated` act on when no package is named: package names and [groups](#groups) such as `"@pimalaya"`. | none |
| `:pins` | Versions `update` installs instead of the latest. | none |
| `:keep` | Versions per package that `gc` keeps. | `3` |
| `:delay` | Seconds `install` and `update` wait after a package that downloaded something, e.g. to spread traffic on a slow link. A package found up to date costs no wait. `--delay N` overrides it. | `0` |
| `:token-command` | A command printing a GitHub token on its first line, e.g. `["gh" "auth" "token"]` or `["gopass" "show" "--password" "github/token"]`. It runs at most once per breto process, only when a GitHub API request is made, and is stopped after 60 seconds. `GITHUB_TOKEN` and `GH_TOKEN` take precedence. | none |
| `:lockfile` | A file breto rewrites after every change to the store with the active version and download hash of each configured package. Commit it, and `update --locked` installs exactly those versions elsewhere. | none |
| `:cosign` | The cosign command that checks `:signature` bundles. Without it, breto uses the cosign it linked into `:bin-dir`, else `cosign` on `PATH`. | none |
| `:hooks` | Commands run on the events of `install` and `update`; see [Hooks](#hooks). | none |
| `:notifier` | The command the `:notify` hook sends with, with `{title}`, `{message}` and `{urgency}` placeholders, or `:auto` for the notifier of the operating system. | `:auto` |
| `:messages` | Message templates of `:notify` per event, e.g. `{:installed "{package} is now {version}"}`. | see [Hooks](#hooks) |
| `:hook-timeout` | Seconds a hook may run before it is stopped. | `30` |
| `:prune` | Whether `gc` without arguments also uninstalls every package `:packages` does not list. | `false` |
| `:store` | Where versions are unpacked, as `<store>/<package>/<version>`. | `$XDG_DATA_HOME/breto/installations` |
| `:bin-dir` | Where binaries are linked. | `~/.local/bin` |
| `:finder` | The fuzzy finder `breto install` without packages chooses with: `:auto` for fzf or sk from `:bin-dir` or `PATH`, or a command that reads tab-separated lines on stdin and prints the chosen ones. | `:auto` |
| `:env-dir` | Where `breto env` keeps the bin directory of each project. | `$XDG_DATA_HOME/breto/envs` |
| `:man-dir` | Where man pages are linked, as `<man-dir>/man<section>/<file>`. | `$XDG_DATA_HOME/man` |
| `:completion-dirs` | Where shell completions are linked, per shell. Leaving a shell out skips its completions. | `{:bash "$XDG_DATA_HOME/bash-completion/completions" :zsh "$XDG_DATA_HOME/zsh/site-functions" :fish "$XDG_CONFIG_HOME/fish/completions"}` |

breto also reads the rate-limit headers of every response. When a host has no requests left (`x-ratelimit-remaining: 0`) or sends `retry-after`, the next package waits until that host accepts requests again, or for `:delay` if that applies and is longer. A package refused by a rate limit that names a wait is retried once after it.

Configure `:token-command`, or set `GITHUB_TOKEN` or `GH_TOKEN`, to raise GitHub's API limit from 60 requests an hour. breto calls the API once for each download it verifies and once for each lookup of a rolling release.

## Commands

| Command | Effect |
| --- | --- |
| `breto list` | Installed packages and their versions; `*` marks the active one. |
| `breto install PKG[@VERSION]...` | Installs and activates the given version, or the latest. |
| `breto install [--query TERMS]` | Without packages, lists every definition in fzf or sk to choose any number from, and installs them. Without a finder it asks for a search and the numbers of the matches instead. |
| `breto search [TERM...]` | The definitions whose name, description or source contains every term, one per line: name, description, source. |
| `breto env [--shell fish] [--dir DIR]` | Links the versions the nearest `.breto.edn` lists into a bin directory of the project and prints the shell code putting it first on `PATH`; see [Project environments](#project-environments). |
| `breto use PKG VERSION` | Activates a version that is already installed. |
| `breto use PKG --previous` | Rolls back to the newest installed version older than the active one. For a configured package it prints the `:pins` entry that keeps the next `update` from switching back. |
| `breto update [PKG...]` | Installs and activates the latest, or pinned, version. |
| `breto outdated [PKG...]` | Packages whose latest release is not the active version. |
| `breto check [PKG...] [--all]` | Checks each configured package, or every package of the registries with `--all`, against its latest release without downloading it: the asset must exist and a GitHub digest, a checksum file or `:verify false` must cover it. Run it after editing definitions, or before a scheduled update. |
| `breto gc [PKG...] [--keep N]` | Deletes all but the N most recently installed versions. The active version is always kept. Also removes what killed installs left behind. With `:prune true` and no package named, also uninstalls unconfigured packages. |
| `breto uninstall PKG...` | Deletes a package with every link into it. |
| `breto sync [--dry-run]` | Makes the store match `:lockfile`: installs and activates each locked version, refusing a download whose hash differs from the locked one, and uninstalls every package the lock file does not list and no project uses. `--dry-run` lists the changes. |
| `breto info PKG` | The package definition after defaults are applied. |
| `breto report [COMMAND...] [--brief]` | The report of the last run of each command, or of the named ones. `--brief` prints only the latest of them in a few lines of at most 100 characters: when it ran, its counts, and the first three failures with their diagnosis. |
| `breto doctor` | Checks tools, directories, configuration, store, GitHub API quota and the last runs. |

Options follow the arguments, as in `breto uninstall helm --force`.

On a terminal, `install`, `update` and `outdated` show a status line with the position in the batch, a tally of the results so far, and the current stage, download progress or remaining wait. Elsewhere, for example under systemd, they log one line per package and per wait instead. `NO_COLOR` turns colors off.

`install` and `update` refuse a download they cannot verify. `--no-verify` skips verification entirely. `gc` and `uninstall` take `--dry-run` to list what they would delete.

Each package directory has a `current` symlink pointing at the active version, and the links in the bin directory resolve through it. Switching versions replaces `current` with an atomic rename, so a command never sees a half-switched package. A version is unpacked into a hidden `.work-*` directory inside the package directory and renamed into place once complete, so an interrupted install never looks installed. Each install holds a lock on its work directory, which the operating system releases when the process dies, however it dies; `gc` deletes the work directories nobody holds, so it can run while another install is in progress. Commands that change the store (`install`, `update`, `use`, `gc`, `uninstall` and `sync`, and `env` while it installs) also take a lock on the whole store, so a manual run started while the timer's update is busy waits for it, naming its process, instead of racing it.

A daily or weekly `breto update && breto gc` from a systemd timer or cron job keeps everything current.

## Choosing packages

`breto install` without packages chooses them interactively. With fzf or sk, linked by breto or on `PATH`, every definition is listed with its description; type to filter, Tab marks several, Enter installs them. The finder in `:bin-dir` counts even before that directory is on `PATH`, so `breto install fzf` makes the finder available on a fresh host. Without one, breto asks for search terms, lists up to 30 numbered matches and reads the numbers to install. `breto doctor` names the finder it found.

A chosen package is not added to `:packages`, since the configuration may be managed elsewhere. breto prints the names to add; until then `update` leaves the package alone, and with `:prune true` the next `gc` uninstalls it.

## Project environments

A `.breto.edn` file in a project lists the packages it needs, with versions where it needs a particular one:

```clojure
{:packages ["helm@3.19.0" "kind" "@pimalaya"]}
```

`breto env`, run anywhere below that directory, links the commands of those versions into a bin directory of the project's own and prints the shell code that puts it first on `PATH` and sets `BRETO_PROJECT` and `BRETO_ENV_BIN`:

```sh
eval "$(breto env)"
breto env --shell fish | source
```

In bash, `shell/env-hook.bash` from this repository does this on its own: sourced from `.bashrc`, it runs `breto env` when the shell enters a directory below a `.breto.edn` or the file changes, and takes the project's bin directory off `PATH` again, through `BRETO_ENV_BIN`, when the shell leaves the project. Between those it costs a few file tests per prompt. The hook runs `breto env --no-install`, which only links versions that are installed and never waits for another breto run, since Ctrl-C may not reach a command a prompt hook runs. A project that needs a missing version gets a message to run `eval "$(breto env)"`, which installs it, waiting for a running update if necessary. `--no-wait` instead fails at once when installing has to wait for another run. A failing `breto env` is retried once the file changes or the shell enters the project again, not on every prompt.

```sh
source ~/src/breto/shell/env-hook.bash
```

A package without a version uses the active version, else the most recently installed one. Versions that are not installed yet are installed without activating them, so the commands in `:bin-dir` keep their versions. Everything but the shell code goes to stderr. Each run makes the project's bin directory hold exactly what the file lists and replaces the `PATH` entry of a project entered before. The `PATH` it prints also drops entries that repeat.

`gc` keeps every version a project links to, and `:prune true` keeps the packages, for as long as the project's `.breto.edn` exists. Once the file is gone, `gc` removes the project's links and treats its versions like any other.

## Lock files

With `:lockfile` set, `install`, `update`, `use`, `gc` and `uninstall` rewrite the file afterwards: one line per configured package with its active version, the SHA-256 of the download it came from, and the asset name. Comment lines at its top, such as a license header, are kept. A package that is not active on this machine keeps its entry, so a failed install never drops it.

On another machine, `breto sync` turns the store into exactly what the lock file lists, removing the rest; `breto sync --dry-run` shows what that would change. A dry run never rewrites the lock file.

`breto update --locked` installs the locked version of each configured package instead of the latest, and refuses a download whose hash differs from the locked one. This also covers releases that upstream publishes without checksums: once one machine installed them, every other one gets the same bytes. A version installed before breto recorded hashes gets one on the next `install` or `update` that finds it up to date: breto looks up the hash upstream publishes for that asset, the GitHub digest or the checksum-file entry its verified download had to match, and records it. The lookup costs one request per version, once; when it fails, a later run tries again. A version installed without verification has no upstream hash to look up, so breto downloads its asset once more and records the hash of that download instead. Such a hash does not show that the bytes are the ones upstream meant to publish; it makes every machine installing with `--locked` get the bytes this one trusted. A fresh install records the hash of its download either way.

## Hooks

`install` and `update` run a command for each event `:hooks` lists, and every command that changes the store runs the `:locked` hook. A hook is a vector of a program and its arguments, never a shell command line, so a version taken from an upstream tag reaches the program as one argument and cannot run anything else. The keyword `:notify` sends a desktop notification instead:

```clojure
{:hooks {:installed :notify
         :finished :notify
         :failed ["logger" "--tag=breto" "{package} failed in {stage}: {summary}"]}}
```

| Event | When | Placeholders | `:notify` message |
| --- | --- | --- | --- |
| `:installed` | after each package that was installed and activated | `{package}`, `{version}`, `{command}` | `{package} {version} installed` |
| `:failed` | after each package that failed | `{package}`, `{version}`, `{stage}`, `{summary}`, `{command}` | `{summary}` |
| `:finished` | once at the end of the run, also when nothing changed | `{summary}`, `{installed}`, `{failed}`, `{current}`, `{command}` | `{summary}`, such as `breto update: 60 up to date, 2 installed` |
| `:locked` | after a command rewrote `:lockfile` because its content changed; never after a dry run | `{lockfile}`, `{command}` | `{lockfile} updated` |

The `:locked` hook can commit the lock file where it is shared, for example in a dotfiles repository that other machines pull: `["git" "-C" "/home/me/dotfiles" "commit" "--message=chore: update breto.lock" "--" "breto.lock"]`, or a script that also pushes. It runs while breto still holds the store lock.

Every placeholder value is also in the hook's environment as `BRETO_<NAME>`, e.g. `BRETO_PACKAGE`, so a script can read them without arguments. A `:notify` hook additionally gets `{title}`, `{message}` and `{urgency}`. A run that stops before it reaches any package, for example on a broken definition, runs no hooks.

`:notifier :auto` uses `notify-send` on Linux and the BSDs and `osascript` on macOS, each only when it is installed. On other systems, or for another tool, set `:notifier` to a command that takes the title and message as arguments, such as `["my-notifier" "{title}" "{message}"]`.

A hook that exits with an error, runs longer than `:hook-timeout` seconds or cannot start is reported as a warning; it never fails the install or update. Invalid hook settings are reported once per run, and no hooks run then. `breto doctor` checks the settings and that the notifier is installed. Under systemd, a user service has the session bus `notify-send` needs; a system service or a cron job usually has not.

## Uninstalling

`breto uninstall PKG` removes the links in the bin directory and the package's extra `:links` that resolve into the package, then the package directory. Links are found by where they point, so a package whose definition was renamed or deleted can still be removed. A package still listed in `:packages` is refused unless `--force` is given, because the next `update` installs it again.

With a configuration that is managed declaratively, for example from dotfiles, `:prune true` turns removing a name from `:packages` into an uninstall: the next `breto gc` deletes it. A package is kept, and the run fails with an explanation, while one of its links carries a command that a configured package provides. That happens when a command moves to a package of another name: `gc` waits until `update` has installed the new package and taken the link over. Run `update` before `gc` so this resolves in one run.

## Diagnosing failures

`install`, `update`, `outdated`, `gc` and `uninstall` keep going past a failing package. Each failure is reported with the stage it happened in (resolve, download, verify, unpack, place or activate), the facts breto could establish, and what to do about it. For a download that returns 404, for example, breto lists the release's assets closest to the expected name and suggests an `:asset` template, or a `:history` entry when only newer releases changed their naming. Rate limits, moved repositories, tag prefixes, archive layouts and missing checksums get the same treatment. The exit code is 1 when any package failed.

The last run of each command is saved to `$XDG_STATE_HOME/breto/last-run.edn`, and updated after every package while a run is still going, so `breto report` shows what a scheduled run did after the fact and how far a running one got. A command that stops before reaching any package, for example on a broken definition, saves its error as its report, so the report never shows an older run as the latest. `breto report` loads no definitions and works while one is broken.

A scheduled update can show its failures as a desktop notification from an `OnFailure=` unit:

```ini
[Service]
Type=oneshot
ExecStart=/usr/bin/sh -c 'notify-send --urgency=critical "Software installations failed" "$$(breto report update gc --brief)"'
```

`breto doctor` checks the environment breto depends on. `--debug` adds every HTTP request and the stack trace of each failure.

## Package definitions

A package is a file `<name>.edn` in a registry directory, and its file name is the package name. The smallest definition names the repository and the release asset:

```clojure
{:source {:github "kubernetes-sigs/kind"}
 :asset "kind-linux-amd64"}
```

| Key | Meaning | Default |
| --- | --- | --- |
| `:description` | One line about the tool, shown and searched by `breto search` and `breto install`. | none |
| `:superseded-by` | The package that replaces this one, such as a maintained fork. `install`, `update`, `sync` and `env` warn when they install the package, `breto search` marks it, and `breto doctor` lists configured packages it applies to. breto never switches to the successor itself, because its commands may have other names. | none |
| `:source` | Where releases are discovered; see below. | required |
| `:replace` | In an overlay, replaces the earlier definition of the name instead of merging over it; see [Overlays](#overlays). | `false` |
| `:asset` | Template for the file name to download. | required |
| `:url` | Template for the download URL. | the GitHub release asset |
| `:tag-prefix` | Text before the version in a tag: `v1.2.3` is version `1.2.3`. | `"v"` |
| `:checksum` | Template for a checksum file, next to the asset unless it is a full URL. | none |
| `:strip` | Leading path components to drop when unpacking, counted as `tar --strip-components` does, a leading `./` included. | `0` |
| `:layout` | `:binary` keeps only the files named in `:bins`; `:tree` keeps the whole archive. | `:binary` |
| `:bins` | Command name to path inside the unpacked archive. | the package name, or the asset itself when it is not an archive |
| `:links` | Further symlinks: target path to path inside the installed version. | none |
| `:history` | Definitions for older versions; see below. | none |
| `:verify` | `false` installs without verification, for releases whose upstream publishes neither GitHub digests nor checksums. Usually set in a `:history` entry, so newer releases are still verified. | `true` |
| `:signature` | A sigstore bundle signing the asset or its checksum file; see below. | none |
| `:skip` | Versions never installed as the latest, e.g. a release without a build for this platform. When the latest release is listed, the newest unlisted one is installed instead, which costs one request listing the releases. An explicitly requested or pinned version is installed regardless. | none |

Templates can use `{name}`, `{tag}`, `{version}`, `{repo}` and, except in `:asset` itself, `{asset}`. Files ending in `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`, `.tar.bz2`, `.tbz2`, `.tar` or `.zip` are unpacked; anything else is the binary itself. An entry that would land outside the unpack directory, or a tar entry that would write through a symlink the archive created, fails the install.

The command name is the key in `:bins`, independent of the package name. A package named `babashka` installs into `<store>/babashka/` and provides the command `bb`:

```clojure
{:source {:github "babashka/babashka"}
 :asset "babashka-{version}-linux-amd64-static.tar.gz"
 :bins {"bb" "bb"}}
```

Several commands can share one binary, which suits tools that dispatch on the name they are called by:

```clojure
:bins {"ansible-wrapper" "ansible-wrapper"
       "ansible-playbook" "ansible-wrapper"}
```

A tool that ships support files next to its binary uses the tree layout, and `:links` exposes those files where the tool expects them:

```clojure
{:source {:github "helix-editor/helix"}
 :tag-prefix ""
 :asset "helix-{version}-x86_64-linux.tar.xz"
 :strip 1
 :layout :tree
 :bins {"hx" "hx"}
 :links {"~/.config/helix/runtime" "runtime"}}
```

### Sources

| Source | Latest release |
| --- | --- |
| `{:github "owner/repo"}` | The release GitHub marks as latest. |
| `{:github "owner/repo" :tag "nightly"}` | A rolling release: every build is an asset under one fixed tag, and `:asset` must contain `{version}`. The newest matching asset by upload time is the latest version. |
| `{:gitlab "gitlab.com/group/project"}` | GitLab's latest release. `:url` is required, because GitLab release links have no fixed shape. |
| `{:text "https://…"}` | A URL whose body is the latest tag, such as Kubernetes' `stable.txt`. `:url` is required. |
| `{:json "https://…" :version [:releases 0 :version]}` | A JSON document holding the latest tag at the `:version` path of keys and indices, such as JetBrains' release API. `:url` is required. |

ble.sh publishes its nightly builds as a rolling release:

```clojure
{:source {:github "akinomyoga/ble.sh" :tag "nightly"}
 :asset "ble-nightly-{version}.tar.xz"
 :strip 1
 :layout :tree
 :bins {}}
```

### Verification

A download from a GitHub release is checked against the sha256 digest GitHub recorded for the asset. Assets uploaded before GitHub began recording digests carry none; for those, and for downloads from other hosts, breto uses the file named by `:checksum`. A checksum file can list many assets in `sha256sum` format or hold a single hash. The algorithm follows from the length of the hash. A download with neither a digest nor a checksum file is refused unless `--no-verify` is given. A failed digest lookup, for example once the API limit is reached, falls back to the checksum file when there is one and fails otherwise.

A checksum shows that a download is what the release lists; a signature shows who built the release. A definition with `:signature` has every verified download checked with `cosign verify-blob` against a sigstore bundle published next to the asset:

```clojure
:signature {:bundle "{asset}.sigstore.json"
            :identity "https://github.com/kyverno/kyverno/.github/workflows/release.yaml@refs/tags/{tag}"
            :issuer "https://token.actions.githubusercontent.com"}
```

| Key | Meaning |
| --- | --- |
| `:bundle` | Template for the bundle file name, or a full URL. |
| `:identity` | Template for the exact certificate identity of the signer, usually the release workflow. |
| `:identity-regexp` | A regular expression for the identity, instead of `:identity`. |
| `:issuer` | The OIDC issuer of the signer's certificate. |
| `:of` | `:asset` when the bundle signs the asset, `:checksum` when it signs the checksum file, whose hash is then trusted. Defaults to `:asset`. |

The signer's identity is in the certificate inside the bundle. Projects usually document it next to their verification instructions. Signed packages need cosign: list `cosign` in `:packages`. `install` and `update` handle cosign before every other package, so on a fresh host the first run installs it, checked by its GitHub digest or checksum file only, since no cosign exists yet to check its signature, and then checks the other signatures with it. The report marks that install. Later updates of cosign are checked by the version already installed. `breto check` confirms the bundle is in the release, and `breto doctor` that cosign can run.

### Groups

A file named `@NAME.edn` in a registry defines a group of packages:

```clojure
{:members ["himalaya" "cardamum" "tcard" "calendula" "tcal"]}
```

`"@pimalaya"` in `:packages`, or as an argument to `install`, `update`, `outdated`, `check`, `gc` and `uninstall`, stands for its members, in order. Members can be groups themselves; a package listed twice is handled once. Everything after that sees the packages: the lock file lists each member, and reports name them. `breto info @pimalaya` shows the members and the files that define the group. A later registry directory replaces the members of a group with the same name, so an overlay can trim or extend one.

With `:prune true`, a member that leaves a configured group is uninstalled by the next `breto gc`, as if its name had been removed from `:packages`. A group is a selection of definitions and declares none: each member still needs its own file.

### Man pages and completions

`:man` lists the man pages of a release as paths or globs inside the unpacked archive, after `:strip`. `:completions` names a completion file per shell, or the arguments and environment that make the command print one:

```clojure
{:man ["share/man/*.1.gz"]
 :completions {:bash "share/completions/himalaya.bash"
               :zsh "share/completions/_himalaya"
               :fish {:args ["completion" "fish"]}}}
```

```clojure
{:completions {:bash {:env {"COMPLETE" "bash"}}}}
```

The binary layout keeps the named files beside the binaries. A completion file the release lacks fails the install, while a man page glob may match nothing. An installed version that lacks a completion file its definition names, such as one installed before the definition named it, counts as incompletely installed, so the next `install` or `update` downloads it again and keeps the files. A printed completion is generated once per installed version, right after it becomes active, by running the command with the given arguments and environment for at most 30 seconds; a command that fails or prints nothing leaves a warning in the report and the install stands. The command is the package's own name when it links a command of that name, else its first command, or `:command` under `:completions`.

Links go to `:man-dir` and `:completion-dirs`, named after the command as each shell expects (`tool`, `_tool`, `tool.fish`), and resolve through the package's `current` link, so `use` and `update` switch them with the binaries and `uninstall` removes them. A file in the way that breto did not link is left alone with a warning.

man finds `$XDG_DATA_HOME/man` through a `PATH` entry `~/.local/bin` once that directory exists, bash-completion 2 loads `$XDG_DATA_HOME/bash-completion/completions` on demand, and fish reads `~/.config/fish/completions`. zsh needs `$XDG_DATA_HOME/zsh/site-functions` in `fpath` before `compinit`.

### Overlays

A definition in a later registry directory is merged over the one of the same name, so an overlay can change one key, such as `:asset` for another architecture. An overlay whose `:source` names another project defines a different tool under the same name; merging the two would mix the keys of both, so breto refuses to load it and names both files. Rename one of them, or set `:replace true` in the overlay to replace the earlier definition instead of merging over it.

### Version history

Projects rename assets, change archive formats and move between hosts. The top level of a definition describes current releases, and each `:history` entry overrides keys for every version older than its `:before`. Entries apply cumulatively from the newest boundary down, so an entry only lists what changed at its boundary:

```clojure
{:source {:github "ulyssa/iamb"}
 :asset "iamb-{version}-x86_64-unknown-linux-musl.tar.gz"
 :strip 1
 :history [{:before "0.0.13" :asset "iamb-x86_64-unknown-linux-musl-binary.zip" :strip 0}
           {:before "0.0.12" :asset "iamb-x86_64-unknown-linux-musl.tgz" :strip 1}]}
```

`{:before "0.5.1" :verify false}` installs releases older than 0.5.1 without verification, because GitHub only records asset digests since mid-2025, while 0.5.1 and newer stay verified. The report marks every install made this way.

Version 0.0.11 installs `iamb-x86_64-unknown-linux-musl.tgz` with one leading component stripped, and 0.13.1 uses the top level. A project that moved from GitLab to GitHub keeps one package name with an entry such as `{:before "2.0.0" :source {:gitlab "gitlab.com/group/project"} :url "…"}`, and both old and new versions stay installable. Defaults such as the GitHub download URL are worked out separately for each entry.

Versions compare by their runs of digits and letters, with numbers compared numerically, so `0.0.13` follows `0.0.12` and `2026.9` follows `2025.12`.

## Definition checks

A scheduled workflow runs `breto check --all` every week against the latest release of every definition and lists the failures in its summary. It makes about one GitHub API request per definition, so a run spends a couple of hours waiting out the rate limit of the workflow's token.

## Development

The flake's dev shell provides babashka and every lint tool CI runs:

```shell
nix develop --command bb test
```

`bb convert SCRIPT-DIR OUT-DIR` turns shell wrappers around the `install-binary-from-github` helper scripts into package definitions. It runs each wrapper with stub helpers that record their arguments, so no download happens.

## License

0BSD, REUSE-compliant. The definitions in `packages/` whose header names Shunsuke Suzuki were converted from the [aqua registry](https://github.com/aquaproj/aqua-registry) and are MIT-licensed. The descriptions of the definitions come from the aqua registry or from the description of the project's repository; `bb descriptions AQUA-REGISTRY-DIR packages` fills in the ones that are missing.
