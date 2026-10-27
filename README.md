<!--
SPDX-FileCopyrightText: The breto Authors
SPDX-License-Identifier: 0BSD
 -->

# breto

breto installs prebuilt binaries from their upstream releases, keeps several versions of each side by side, and switches between them with one symlink. What to download and how to install it is declared as data: one EDN file per package. Nothing is built from source and nothing needs root.

## Requirements

breto runs on [babashka](https://babashka.org/). Unpacking uses GNU `tar` together with `gzip`, `xz` and `bzip2`; zip archives are unpacked by babashka itself.

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
| `:registries` | Directories of package definitions. A later directory overrides an earlier one key by key, so an overlay can change a single key of a package. | none |
| `:packages` | What `update` and `outdated` act on when no package is named. | none |
| `:pins` | Versions `update` installs instead of the latest. | none |
| `:keep` | Versions per package that `gc` keeps. | `3` |
| `:delay` | Seconds `install` and `update` wait after a package that downloaded something, e.g. to spread traffic on a slow link. A package found up to date costs no wait. `--delay N` overrides it. | `0` |
| `:prune` | Whether `gc` without arguments also uninstalls every package `:packages` does not list. | `false` |
| `:store` | Where versions are unpacked, as `<store>/<package>/<version>`. | `$XDG_DATA_HOME/breto/installations` |
| `:bin-dir` | Where binaries are linked. | `~/.local/bin` |

breto also reads the rate-limit headers of every response. When a host has no requests left (`x-ratelimit-remaining: 0`) or sends `retry-after`, the next package waits until that host accepts requests again, or for `:delay` if that applies and is longer. A package refused by a rate limit that names a wait is retried once after it.

Set `GITHUB_TOKEN` or `GH_TOKEN` to raise GitHub's API limit from 60 requests an hour. breto calls the API once for each download it verifies and once for each lookup of a rolling release.

## Commands

| Command | Effect |
| --- | --- |
| `breto list` | Installed packages and their versions; `*` marks the active one. |
| `breto install PKG[@VERSION]...` | Installs and activates the given version, or the latest. |
| `breto use PKG VERSION` | Activates a version that is already installed. |
| `breto update [PKG...]` | Installs and activates the latest, or pinned, version. |
| `breto outdated [PKG...]` | Packages whose latest release is not the active version. |
| `breto gc [PKG...] [--keep N]` | Deletes all but the N most recently installed versions. The active version is always kept. Also removes what killed installs left behind. With `:prune true` and no package named, also uninstalls unconfigured packages. |
| `breto uninstall PKG...` | Deletes a package with every link into it. |
| `breto info PKG` | The package definition after defaults are applied. |
| `breto report` | The report of the last run of each command. |
| `breto doctor` | Checks tools, directories, configuration, store, GitHub API quota and the last runs. |

Options follow the arguments, as in `breto uninstall helm --force`.

On a terminal, `install`, `update` and `outdated` show a status line with the position in the batch, a tally of the results so far, and the current stage, download progress or remaining wait. Elsewhere, for example under systemd, they log one line per package and per wait instead. `NO_COLOR` turns colors off.

`install` and `update` refuse a download they cannot verify. `--no-verify` skips verification entirely. `gc` and `uninstall` take `--dry-run` to list what they would delete.

Each package directory has a `current` symlink pointing at the active version, and the links in the bin directory resolve through it. Switching versions replaces `current` with an atomic rename, so a command never sees a half-switched package. A version is unpacked into a hidden `.work-*` directory inside the package directory and renamed into place once complete, so an interrupted install never looks installed. Each install holds a lock on its work directory, which the operating system releases when the process dies, however it dies; `gc` deletes the work directories nobody holds, so it can run while another install is in progress.

A daily or weekly `breto update && breto gc` from a systemd timer or cron job keeps everything current.

## Uninstalling

`breto uninstall PKG` removes the links in the bin directory and the package's extra `:links` that resolve into the package, then the package directory. Links are found by where they point, so a package whose definition was renamed or deleted can still be removed. A package still listed in `:packages` is refused unless `--force` is given, because the next `update` installs it again.

With a configuration that is managed declaratively, for example from dotfiles, `:prune true` turns removing a name from `:packages` into an uninstall: the next `breto gc` deletes it. A package is kept, and the run fails with an explanation, while one of its links carries a command that a configured package provides. That happens when a command moves to a package of another name: `gc` waits until `update` has installed the new package and taken the link over. Run `update` before `gc` so this resolves in one run.

## Diagnosing failures

`install`, `update`, `outdated`, `gc` and `uninstall` keep going past a failing package. Each failure is reported with the stage it happened in (resolve, download, verify, unpack, place or activate), the facts breto could establish, and what to do about it. For a download that returns 404, for example, breto lists the release's assets closest to the expected name and suggests an `:asset` template, or a `:history` entry when only newer releases changed their naming. Rate limits, moved repositories, tag prefixes, archive layouts and missing checksums get the same treatment. The exit code is 1 when any package failed.

The last run of each command is saved to `$XDG_STATE_HOME/breto/last-run.edn`, so `breto report` shows what a scheduled run did after the fact. `breto doctor` checks the environment breto depends on. `--debug` adds every HTTP request and the stack trace of each failure.

## Package definitions

A package is a file `<name>.edn` in a registry directory, and its file name is the package name. The smallest definition names the repository and the release asset:

```clojure
{:source {:github "kubernetes-sigs/kind"}
 :asset "kind-linux-amd64"}
```

| Key | Meaning | Default |
| --- | --- | --- |
| `:source` | Where releases are discovered; see below. | required |
| `:asset` | Template for the file name to download. | required |
| `:url` | Template for the download URL. | the GitHub release asset |
| `:tag-prefix` | Text before the version in a tag: `v1.2.3` is version `1.2.3`. | `"v"` |
| `:checksum` | Template for a checksum file, next to the asset unless it is a full URL. | none |
| `:strip` | Leading path components to drop when unpacking, as `tar --strip-components`. | `0` |
| `:layout` | `:binary` keeps only the files named in `:bins`; `:tree` keeps the whole archive. | `:binary` |
| `:bins` | Command name to path inside the unpacked archive. | the package name, or the asset itself when it is not an archive |
| `:links` | Further symlinks: target path to path inside the installed version. | none |
| `:history` | Definitions for older versions; see below. | none |
| `:skip` | Versions never installed as the latest, e.g. a release without a build for this platform. When the latest release is listed, the newest unlisted one is installed instead, which costs one request listing the releases. An explicitly requested or pinned version is installed regardless. | none |

Templates can use `{name}`, `{tag}`, `{version}`, `{repo}` and, except in `:asset` itself, `{asset}`. Files ending in `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`, `.tar.bz2`, `.tbz2`, `.tar` or `.zip` are unpacked; anything else is the binary itself.

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

### Version history

Projects rename assets, change archive formats and move between hosts. The top level of a definition describes current releases, and each `:history` entry overrides keys for every version older than its `:before`. Entries apply cumulatively from the newest boundary down, so an entry only lists what changed at its boundary:

```clojure
{:source {:github "ulyssa/iamb"}
 :asset "iamb-{version}-x86_64-unknown-linux-musl.tar.gz"
 :strip 1
 :history [{:before "0.0.13" :asset "iamb-x86_64-unknown-linux-musl-binary.zip" :strip 0}
           {:before "0.0.12" :asset "iamb-x86_64-unknown-linux-musl.tgz" :strip 1}]}
```

Version 0.0.11 installs `iamb-x86_64-unknown-linux-musl.tgz` with one leading component stripped, and 0.13.1 uses the top level. A project that moved from GitLab to GitHub keeps one package name with an entry such as `{:before "2.0.0" :source {:gitlab "gitlab.com/group/project"} :url "…"}`, and both old and new versions stay installable. Defaults such as the GitHub download URL are worked out separately for each entry.

Versions compare by their runs of digits and letters, with numbers compared numerically, so `0.0.13` follows `0.0.12` and `2026.9` follows `2025.12`.

## Development

The flake's dev shell provides babashka and every lint tool CI runs:

```shell
nix develop --command bb test
```

`bb convert SCRIPT-DIR OUT-DIR` turns shell wrappers around the `install-binary-from-github` helper scripts into package definitions. It runs each wrapper with stub helpers that record their arguments, so no download happens.

## License

0BSD, REUSE-compliant.
