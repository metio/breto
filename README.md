<!--
SPDX-FileCopyrightText: The breto Authors
SPDX-License-Identifier: 0BSD
-->

# breto

breto installs prebuilt binaries from their upstream releases, keeps several versions of each side by side, and switches between them with one symlink. What to download and how to install it is declared as data: one EDN file per package. Nothing is built from source and nothing needs root.

## Requirements

breto runs on [babashka](https://babashka.org/), which the release executables carry inside them. It unpacks zip archives, plain or gzip-compressed tar archives and gzip-compressed single files itself; `.tar.xz`, `.tar.bz2` and `.tar.zst` need `tar` with `xz`, `bzip2` or `zstd`, and a bzip2- or zstd-compressed single file needs `bzip2` or `zstd`. Packages that require a signature, and every [published registry](#published-registries), need cosign. breto installs it itself when `cosign` is listed in `:packages`, and before a published registry when it finds none.

## Installation

Every release carries breto as one executable for `linux-amd64`, `linux-aarch64`, `darwin-amd64` and `darwin-aarch64`: babashka with breto and the definitions of cosign and breto inside it, so it needs neither babashka nor a checkout. Download it from the [latest release](https://github.com/metio/breto/releases/latest) and let it install itself:

```shell
chmod +x breto-linux-amd64
./breto-linux-amd64 install cosign breto
rm breto-linux-amd64
```

breto installs cosign first, then checks the signature of its own release with it, and links `breto` into `~/.local/bin`. Listed in `:packages` with cosign, breto is then kept up to date by `breto update` like any other package. The download itself can be checked the same way before running it, with the `SHA256SUMS` file and its signature bundle from the release:

```shell
cosign verify-blob breto_VERSION_SHA256SUMS --bundle breto_VERSION_SHA256SUMS.bundle \
  --certificate-identity https://github.com/metio/breto/.github/workflows/release.yml@refs/heads/main \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
sha256sum --check --ignore-missing breto_VERSION_SHA256SUMS
```

Every other definition comes from [metio/packages](https://github.com/metio/packages), the published registry breto reads by default; `breto update` installs it before the packages it defines. The two built-in definitions let a fresh machine verify that registry and keep breto up to date. They come before every registry, which can add packages or override keys of the built-in ones.

To work on breto itself, clone the repository and link the launcher onto your `PATH`. It runs `bb` against the `bb.edn` next to it, so the clone can live anywhere. It has no built-in definitions, so it reads cosign and breto from the default registry as well and needs cosign on `PATH` to verify that registry:

```shell
git clone https://github.com/metio/breto.git ~/src/breto
ln -s ~/src/breto/breto ~/.local/bin/breto
```

`bb build` writes the uberjar the release appends to babashka to `target/breto.jar`.

## GitHub Actions

The `breto` action of [metio/ci](https://github.com/metio/ci) installs breto from its signed release, installs the packages of a project and puts the project's bin directory on `PATH` for the later steps, with the project's `:env` set:

```yaml
- uses: actions/checkout@v6
- uses: metio/ci/breto@VERSION
  with:
    registries: |
      github:metio/toolchains
- run: breto run check
```

`project` names the project directory, `.` by default. `registries` lists the registries read after the default registry, one per line: `github:OWNER/REPO` installs the latest release of a [published registry](#published-registries), and any other line is a directory of definitions and groups relative to the workspace. `version` picks a breto release, `latest` by default. The action verifies the release's `SHA256SUMS` with cosign against the release workflow's identity before running the binary, and keeps the installed packages in the Actions cache, keyed on the project files and the registries' definitions and lock files; `cache: false` turns that off. It runs on Linux and macOS runners, x86_64 and arm64.

## Configuration

breto reads the file given with `--config`, else the one `BRETO_CONFIG` names, else `$XDG_CONFIG_HOME/breto/config.edn` (`~/.config/breto/config.edn` by default). Every key is optional:

```clojure
{:extra-registries ["~/.config/breto/packages"]
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
| `:registries` | Directories of package definitions, or maps such as `{:github "metio/toolchains"}` naming a [published registry](#published-registries). A later registry overrides an earlier one key by key, so an overlay can change a single key of a package; see [Overlays](#overlays). Setting it replaces the default, so a list without metio/packages reads only the registries it names. | `[{:github "metio/packages"}]` |
| `:extra-registries` | Registries read after `:registries`, in the same forms. `:registries` replaces the default registries, while `:extra-registries` adds to them. | none |
| `:packages` | What `update` and `outdated` act on when no package is named: package names and [groups](#groups) such as `"@pimalaya"`. | none |
| `:pins` | Versions `update` installs instead of the latest. | none |
| `:keep` | Versions per package that `gc` keeps. | `3` |
| `:delay` | Seconds `install`, `update` and `sync` wait after a package that downloaded something, e.g. to spread traffic on a slow link. A package found up to date costs no wait. `breto env` and `breto run` install what a project needs without it, since somebody waits for them. `--delay N` overrides it for every command. | `0` |
| `:token-command` | A command printing a GitHub token on its first line, e.g. `["gh" "auth" "token"]` or `["gopass" "show" "--password" "github/token"]`. It runs at most once per breto process, only when a GitHub API request is made, and is stopped after 60 seconds. `GITHUB_TOKEN` and `GH_TOKEN` take precedence. | none |
| `:require-locked` | Whether installing a version a group pins fails when no `breto.lock` of the registry directories lists it, instead of warning; see [Registry lock files](#registry-lock-files). | `false` |
| `:python` | The Python version environments of `:pypi` packages are created for, unless their definition names one. | `"3.13"` |
| `:python-dir` | Where uv keeps the Pythons of those environments. | `$XDG_DATA_HOME/breto/python` |
| `:lockfile` | A file breto rewrites after every change to the store with the active version and download hash of each configured package. Commit it, and `update --locked` installs exactly those versions elsewhere. | none |
| `:cosign` | The cosign command that checks `:signature` bundles. Without it, breto uses the cosign it linked into `:bin-dir`, else `cosign` on `PATH`. | none |
| `:hooks` | Commands run on the events of `install` and `update`; see [Hooks](#hooks). | none |
| `:notifier` | The command the `:notify` hook sends with, with `{title}`, `{message}` and `{urgency}` placeholders, or `:auto` for the notifier of the operating system. | `:auto` |
| `:messages` | Message templates of `:notify` per event, e.g. `{:installed "{package} is now {version}"}`. | see [Hooks](#hooks) |
| `:hook-timeout` | Seconds a hook may run before it is stopped. | `30` |
| `:prune` | Whether `gc` without arguments also uninstalls every package `:packages` does not list. | `false` |
| `:store` | Where versions are unpacked, as `<store>/<package>/<version>`. | `$XDG_DATA_HOME/breto/installations` |
| `:bin-dir` | Where binaries are linked. | `~/.local/bin`, or `~/bin` when `PATH` lists it before `~/.local/bin` or alone |
| `:finder` | The fuzzy finder `breto install` without packages chooses with: `:auto` for fzf or sk from `:bin-dir` or `PATH`, or a command that reads tab-separated lines on stdin and prints the chosen ones. | `:auto` |
| `:env-dir` | Where `breto env` keeps the bin directory of each project. | `$XDG_DATA_HOME/breto/envs` |
| `:platform` | The platform definitions are resolved for, one of `linux-x86_64`, `linux-aarch64`, `darwin-x86_64` and `darwin-aarch64`; see [Platforms](#platforms). `--platform` overrides it for one run. | the platform breto runs on |
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
| `breto exec COMMAND [ARG...] [--dir DIR]` | Brings the project up to date and runs a command of its packages, which the scripts in its bin directory do when a file deciding its versions changed; see [Project environments](#project-environments). |
| `breto env [--shell fish] [--only project\|declared] [--dir DIR]` | Links the versions the nearest `.breto.edn` lists into a bin directory of the project and prints the shell code putting it first on `PATH`; see [Project environments](#project-environments). |
| `breto use PKG VERSION` | Activates a version that is already installed. |
| `breto use PKG --previous` | Rolls back to the newest installed version older than the active one. For a configured package it prints the `:pins` entry that keeps the next `update` from switching back. |
| `breto update [PKG...]` | Installs and activates the latest, or pinned, version. |
| `breto outdated [PKG...]` | Packages whose latest release is not the active version, or not the version a group member or `PKG@VERSION` pins. |
| `breto check [PKG...] [--all]` | Checks each configured package, or every package of the registries with `--all`, against its latest release without downloading it: the asset must exist and a GitHub digest, a checksum file or `:verify false` must cover it. Run it after editing definitions, or before a scheduled update. |
| `breto gc [PKG...] [--keep N]` | Deletes all but the N most recently installed versions. The active version is always kept. Also removes what killed installs left behind. With `:prune true` and no package named, also uninstalls unconfigured packages. |
| `breto uninstall PKG...` | Deletes a package with every link into it. |
| `breto sync [--dry-run]` | Makes the store match `:lockfile`: installs and activates each locked version, refusing a download whose hash differs from the locked one, and uninstalls every package the lock file does not list and no project uses. `--dry-run` lists the changes. |
| `breto lock DIR [--check]` | Writes `DIR/breto.lock` with the hash of every version the groups defined in the registry directory DIR pin; see [Registry lock files](#registry-lock-files). `--check` exits 1 when the file is out of date instead of writing it. |
| `breto validate DIR` | Checks every definition and group in the registry directory DIR on every platform without downloading anything: each file must be valid EDN, each definition valid and its release rendered at a sample version fetched over https with its commands inside the install directory, and each group valid. Exits 1 on any problem, for the CI of a registry. |
| `breto info PKG` | The package definition after defaults are applied. |
| `breto report [COMMAND...] [--brief]` | The report of the last run of each command, or of the named ones. `--brief` prints only the latest of them in a few lines of at most 100 characters: when it ran, its counts, and the first three failures with their diagnosis. |
| `breto doctor` | Checks tools, directories, configuration, store, commands that several installed packages provide, GitHub API quota and the last runs. |

Options follow the arguments, as in `breto uninstall helm --force`.

On a terminal, `install`, `update` and `outdated` show a status line with the position in the batch, a tally of the results so far, and the current stage, download progress or remaining wait. Elsewhere, for example under systemd, they log one line per package and per wait instead. `NO_COLOR` turns colors off.

`install` and `update` refuse a download they cannot verify. `--no-verify` skips verification entirely. `gc` and `uninstall` take `--dry-run` to list what they would delete.

Each package directory has a `current` symlink pointing at the active version, and the links in the bin directory resolve through it. Switching versions replaces `current` with an atomic rename, so a command never sees a half-switched package. A version is unpacked into a hidden `.work-*` directory inside the package directory and renamed into place once complete, so an interrupted install never looks installed. Once in place, a version is read-only: every write permission is taken off its files and directories, and a version installed before that is sealed the next time an install finds it present. A program that keeps state in its own installation directory has to be told to write elsewhere; one that tries anyway gets a permission error. Completions a command prints are still written into the version on activation, and `gc`, `uninstall` and `sync` restore the permissions they need to delete a version. Each install holds a lock on its work directory, which the operating system releases when the process dies, however it dies; `gc` deletes the work directories nobody holds, so it can run while another install is in progress. Commands that change the store (`install`, `update`, `use`, `gc`, `uninstall` and `sync`, and `env` while it installs) also take a lock on the whole store, so a manual run started while the timer's update is busy waits for it, naming its process, instead of racing it.

A daily or weekly `breto update && breto gc` from a systemd timer or cron job keeps everything current.

## Choosing packages

`breto install` without packages chooses them interactively. With fzf or sk, linked by breto or on `PATH`, every definition is listed with its description; type to filter, Tab marks several, Enter installs them. The finder in `:bin-dir` counts even before that directory is on `PATH`, so `breto install fzf` makes the finder available on a fresh host. Without one, breto asks for search terms, lists up to 30 numbered matches and reads the numbers to install. `breto doctor` names the finder it found.

A chosen package is not added to `:packages`, since the configuration may be managed elsewhere. breto prints the names to add; until then `update` leaves the package alone, and with `:prune true` the next `gc` uninstalls it.

## Project environments

A `.breto.edn` file in a project lists the packages it needs, with versions where it needs a particular one:

```clojure
{:packages ["helm@3.19.0" "kind" "@pimalaya"]}
```

A `.breto.local.edn` beside it holds the settings of one machine and stays out of version control. It has the same keys and is read over `.breto.edn`: a package or group it names takes the place of the shared file's spec for it, such as `"helm@3.20.0"` for `"helm@3.19.0"`, other packages are added, and its variables and commands replace those of the same name. Either file alone also makes a project. breto warns when `.breto.local.edn` is in a git work tree that does not ignore it; add it to `.gitignore`.

`breto env`, run anywhere below that directory, links the commands of those versions into a bin directory of the project's own and prints the shell code that puts it first on `PATH` and sets `BRETO_PROJECT`, `BRETO_ENV_BIN`, and `BRETO_ENV_FILES`, the project file and the group files its packages come from:

```sh
eval "$(breto env)"
breto env --shell fish | source
```

`--only project` narrows `PATH` to the project's bin directory and the directories of `PATH` the user cannot write to, which hold what the system installed, so a command installed anywhere else, the global versions breto links included, is not found. `--only declared` also keeps the commands of the configuration's `:packages`, through a bin directory that holds just those. Both work outside a project as well, where only the system directories, or those and the declared commands, stay on `PATH`:

```sh
bash -c 'eval "$(breto env --only project)"; exec bash --norc'
```

In bash, the hook `breto hook bash` prints does this on its own: evaluated in `.bashrc`, it runs `breto env` when the shell enters a directory below a `.breto.edn` or one of the files in `BRETO_ENV_FILES` changes, and takes the project's bin directory off `PATH` again, through `BRETO_ENV_BIN`, when the shell leaves the project. Between those it costs a few file tests per prompt. The hook runs `breto env --no-install`, which only links versions that are installed and never waits for another breto run, since Ctrl-C may not reach a command a prompt hook runs; it exits with code 3 when the project needs a version that is missing. The hook then starts a `breto env` in the background that installs it, waiting for a running update if necessary, and the first prompt after that finishes switches the shell to the project; a failed install prints its output once. `eval "$(breto env)"` installs in the foreground instead, and `--no-wait` makes it fail at once when installing has to wait for another run. A failing `breto env` is retried once the file changes or the shell enters the project again, not on every prompt.

```sh
eval "$(breto hook bash)"
```

`breto hook` reads neither the configuration nor the registry, so it adds little to the start of a shell. The hook is `shell/env-hook.bash` in this repository, which a checkout can also source directly.

A package whose definition has `:env` also sets those variables, such as `JAVA_HOME`. `{install-dir}` in their values is a link of the project's own to the version directory, so the variable keeps its value when the version changes and `update` or `sync` only moves the link. `{install-dirs}` is the directory holding those links, one per package of the project that sets a variable, for tools that look for plugins in the subdirectories of one directory. `{home}` and `{homes}` are deprecated names of the two and still work. The values the variables had before the project are saved, and the hook restores them, or unsets the variables, when the shell leaves the project; entering another project does the same first. Two packages of one project setting the same variable to different values is an error.

A package without a version uses the active version, else the most recently installed one. Versions that are not installed yet are installed without activating them, so the commands in `:bin-dir` keep their versions. Everything but the shell code goes to stderr. Each run makes the project's bin directory hold exactly what the file lists and replaces the `PATH` entry of a project entered before. The `PATH` it prints also drops entries that repeat.

Switching to a branch that names other versions needs no extra step. The commands of the packages in the project's bin directory that are scripts or native binaries are short sh scripts that compare the modification times of `.breto.edn`, `.breto.local.edn` and the group files with a stamp breto wrote when it linked them; a project file that appears or disappears counts as well. Nothing changed costs a few file tests, about a millisecond, and the command starts directly. Otherwise the script calls `breto exec`, which compares a hash of those files with the one in the stamp: for the same content it only renews the stamp, and for anything else it installs what is missing and links the project anew, then runs the command. This works outside an interactive shell too, so an editor or a script calling a tool by its path gets the right version. A project pinning breto itself links it without such a script. Anything else a package links, such as a jar a command names on a classpath, stays a plain link, so it reads as itself and does not trigger an update.

### Project variables and commands

`:env` sets variables in the project's shell, and `:commands` defines commands that run with the project's tools first on `PATH`:

```clojure
{:packages ["@lint"]
 :env {"GOTOOLCHAIN" "local"}
 :commands {"ci-reuse" {:run ["reuse" "lint"]}
            "ci-markdown" {:run "git ls-files -z -- '*.md' | xargs -0 -r markdownlint-cli2"
                           :description "Lint the Markdown git tracks"}
            "check" {:depends ["ci-reuse" "ci-markdown"] :description "Every lint"}}
 :on-enter "check"}
```

A vector `:run` starts the program with its arguments; a string runs through `sh -c`, where the arguments of the call are `"$@"`. `:depends` names commands that run first, each once, depth first and in the order given; the first one failing stops the run with its exit code. A command runs in the project directory, or in its `:dir` below it, with its own `:env` on top of the project's. `:platforms` overrides `:run` and `:env` for a platform, such as `{"darwin-aarch64" {:run ["gmake" "dev"]}}`. In `:run`, `:env` and the project's `:env`, `{project}` is the project directory, `{bin}` its bin directory, `{install-dirs}` the directory of its links to version directories (`{homes}` is a deprecated name for it), and `{platform}`, `{os}` and `{arch}` the platform; other braces, such as a shell's `${var}`, stay as written.

`breto run lint --fix` runs the command `lint` with `--fix`; options after the command name are its own. `breto run` without a name chooses the command in fzf or sk, else in a numbered prompt. Every command is also linked into the project's bin directory as a script calling `breto run`, so `lint --fix` works in the project's shell as well. A command may not take the name of a command one of the project's packages links.

On entering a project, the hook runs `breto env --enter`, which lists the project's commands with their descriptions and then runs the command `:on-enter` names, in the shell that entered. `:menu false` keeps the list quiet. A group can carry `:env` and `:commands` too; the project's files override them, and two groups defining one variable or command differently is an error. Variables of the project's `:env` win over those of its packages and groups.

`breto update` without package names and `breto sync` finish by bringing every project with a bin directory up to date: they install the versions its `.breto.edn` names, again without activating them, and link its bin directory anew. A shell already working in the project runs the new versions from its next command on. A project whose file no longer loads keeps its links and is reported with `[WARN]`.

### Java

`temurin` follows the newest generally available release of Eclipse Temurin, and `temurin-17`, `temurin-21` and `temurin-25` the newest update of one long-term support release each. A project names the line it needs and gets its JDK first on `PATH` with `JAVA_HOME` pointing at it:

```clojure
{:packages ["temurin-21"]}
```

`graalvm` follows the newest feature release of Oracle GraalVM that Oracle publishes for download, and `graalvm-21` and `graalvm-25` the newest update of one long-term support release each. They set `GRAALVM_HOME` as well as `JAVA_HOME` and link `native-image` with the other commands of the JDK. The versions come from the [foojay Disco API](https://api.foojay.io), the archives and their checksum files from download.oracle.com. Oracle GraalVM is licensed under the GraalVM Free Terms and Conditions.

Every command in the JDK's `bin` directory is linked. Outside of projects, the JDK listed in `:packages` is the one in `:bin-dir`. A `JAVA_HOME` set for the whole session can point at its `current` link, such as `$XDG_DATA_HOME/breto/installations/temurin/current`, which follows `update`; the hook restores that value when the shell leaves a project.

The definitions show how `:vars` fits a release whose asset name is not a plain copy of its version: the tag `jdk-27.0.1.0.2+1` is version `27.0.1.0.2+1`, and the asset `OpenJDK27U-jdk_x64_linux_hotspot_27.0.1.0.2_1.tar.gz` uses `{feature}` and `{file-version}`:

```clojure
:vars {:feature {:match "^(\\d+)"} :file-version {:replace ["+" "_"]}}
```

### Shared toolchains

A group can pin versions, so projects of one kind can share a toolchain instead of each pinning its own:

```clojure
;; @go.edn in a registry directory of its own
{:members ["go@1.25.3" "gopls@0.20.0" "staticcheck@2025.1.1"]}
```

```clojure
;; .breto.edn of every Go project
{:packages ["@go"]}
```

List the directory holding the group files under `:extra-registries`. When a new version lands in a group file, for example through a Renovate pull request and a `git pull`, the next `breto update` or `breto sync` installs it and moves every project using the group to it, and the hook relinks a shell that is open in such a project. A `breto.lock` next to the group files fixes the hash of each pinned version; see [Registry lock files](#registry-lock-files).

A group file can also hold the commands every project of its kind runs, so they are defined once:

```clojure
;; @lint.edn
{:members ["reuse@6.2.0" "typos@1.51.1"]
 :commands {"ci-reuse" {:run ["reuse" "lint"]}
            "ci-typos" {:run ["typos"]}}}
```

`gc` keeps every version a project links to, and `:prune true` keeps the packages, for as long as the project's `.breto.edn` or `.breto.local.edn` exists. Once the file is gone, `gc` removes the project's links and treats its versions like any other.

### Published registries

A registry can be published as releases of a GitHub repository, so every machine and CI job reads the same signed definitions and groups without a clone:

```clojure
{:extra-registries [{:github "metio/toolchains"}]
 :packages ["breto"]}
```

breto installs the release like a package named `registry-OWNER-REPO`, which comes first in `:packages`, and reads the registry from its active version. `breto update` and `breto sync` install it before everything else and then load the registry again, so a group that a new release changes reaches the projects in the same run; the lock file records the registry's version like any other package's. `install`, `env` and `run` install a registry that is missing before the packages they need, `env` and `run` with their report on stderr and not under `--no-install`. Other commands read the installed release offline, and warn when none is installed yet.

A release named `VERSION` carries three assets: `REPO-VERSION.tar.gz` with the registry's `.edn` files and `breto.lock` at its root, `REPO_VERSION_SHA256SUMS`, and its cosign bundle `REPO_VERSION_SHA256SUMS.bundle`. The checksum file must be signed by the repository's `.github/workflows/release.yml` on `main`, as GitHub's OIDC issuer certifies it. `:signature {:identity … :issuer …}` names another identity, and `:tag-prefix` the text before the version in a tag, empty by default.

## Lock files

With `:lockfile` set, `install`, `update`, `use`, `gc` and `uninstall` rewrite the file afterwards: one line per configured package with its active version, the SHA-256 of the download it came from, or the module hash of a package built with `go install`, and the asset name. Comment lines at its top, such as a license header, are kept. A package that is not active on this machine keeps its entry, so a failed install never drops it.

On another machine, `breto sync` turns the store into exactly what the lock file lists, removing the rest; `breto sync --dry-run` shows what that would change. A dry run never rewrites the lock file.

`breto update --locked` installs the locked version of each configured package instead of the latest, and refuses a download whose hash differs from the locked one. This also covers releases that upstream publishes without checksums: once one machine installed them, every other one gets the same bytes. A version installed before breto recorded hashes gets one on the next `install` or `update` that finds it up to date: breto looks up the hash upstream publishes for that asset, the GitHub digest or the checksum-file entry its verified download had to match, and records it. The lookup costs one request per version, once; when it fails, a later run tries again. A version installed without verification has no upstream hash to look up, so breto downloads its asset once more and records the hash of that download instead. Such a hash does not show that the bytes are the ones upstream meant to publish; it makes every machine installing with `--locked` get the bytes this one trusted. A fresh install records the hash of its download either way.

### Registry lock files

A registry directory can hold a `breto.lock` with the hash of every version its groups pin, keyed `name@version`. breto reads the lock files of all `:registries`, and any install of a listed version, whether by `install`, `update`, `sync`, `env` or a project refresh, must match its entry; the check comes on top of the upstream verification. A pinned version that no lock file lists installs with a warning, or fails with `:require-locked true`.

`breto lock DIR` installs the pinned versions of the groups defined in DIR beside the active ones, without activating them, and writes the file. An entry that already exists has to match, so a release whose asset changed upstream fails instead of being locked anew. Entries of versions no group pins any more are dropped. `--platforms all`, or a comma-separated list such as `--platforms linux-x86_64,darwin-aarch64`, also downloads, verifies and hashes the releases of the other platforms without installing them, so one machine can lock a group for every platform it supports; the hashes of platforms a run does not name stay in the file. Since `breto lock` downloads and runs nothing a project would not, it suits CI: a workflow can run it on every Renovate pull request against the group files and push the result, and `breto lock DIR --check` fails a pull request whose lock file lags behind its groups.

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

A package is a file `<name>.edn` in a registry directory, and its file name is the package name. The smallest definition names the repository and, for each platform with a release, the asset to download; see [Platforms](#platforms):

```clojure
{:source {:github "kubernetes-sigs/kind"}
 :platforms {"linux-x86_64" {:asset "kind-linux-amd64"}
             "darwin-aarch64" {:asset "kind-darwin-arm64"}}}
```

| Key | Meaning | Default |
| --- | --- | --- |
| `:description` | One line about the tool, shown and searched by `breto search` and `breto install`. | none |
| `:superseded-by` | The package that replaces this one, such as a maintained fork. `install`, `update`, `sync` and `env` warn when they install the package, `breto search` marks it, and `breto doctor` lists configured packages it applies to. breto never switches to the successor itself, because its commands may have other names. | none |
| `:source` | Where releases are discovered; see below. | required |
| `:replace` | In an overlay, replaces the earlier definition of the name instead of merging over it; see [Overlays](#overlays). | `false` |
| `:asset` | Template for the file name to download, usually set per platform. | required |
| `:platforms` | The platforms with releases, each with the keys that differ there; see [Platforms](#platforms). | required, except for sources built on the machine |
| `:url` | Template for the download URL. | the GitHub release asset |
| `:tag-prefix` | Text before the version in a tag: `v1.2.3` is version `1.2.3`. | `"v"` |
| `:checksum` | Template for a checksum file, next to the asset unless it is a full URL, or `{:json URL :hash [path]}` for a hash inside a JSON or YAML document. | none |
| `:strip` | Leading path components to drop when unpacking, counted as `tar --strip-components` does, a leading `./` included. | `0` |
| `:layout` | `:binary` keeps only the files named in `:bins`; `:tree` keeps the whole archive. | `:binary` |
| `:bins` | Command name to path inside the unpacked archive. | the package name, or the asset itself when it is not an archive, without `.gz`, `.bz2` or `.zst` when it is a compressed single file |
| `:bin-dir` | With the `:tree` layout, a directory inside the release whose executable files all become commands, read from each installed version, so a command a newer version drops loses its link on activation. `:bins` adds to them. | none |
| `:vars` | Further placeholders derived from the version, as `{:match REGEX}`, the first group or else the whole match, or `{:replace [FROM TO]}`; see [Java](#java). `{:text URL :match REGEX}` reads one from a document instead, such as a build stamp the version does not contain, and `{:json URL :path [path]}` takes the value at a path in a JSON or YAML document, read like the path of a `:checksum`; the URL can use the other placeholders. | none |
| `:python` | For a `:pypi` source, the Python version of its environment, overriding the config's `:python`. | none |
| `:env` | Variables `breto env` sets in a project using the package, as templates; `{install-dir}` is a link to the version the project uses, `{install-dirs}` the directory of those links. | none |
| `:links` | Further symlinks: target path to path inside the installed version. | none |
| `:history` | Definitions for older versions; see below. | none |
| `:verify` | `false` installs without verification, for releases whose upstream publishes neither GitHub digests nor checksums. Usually set in a `:history` entry, so newer releases are still verified. | `true` |
| `:signature` | A sigstore bundle signing the asset or its checksum file; see below. | none |
| `:skip` | Versions never installed as the latest, e.g. a broken release. When the latest release is listed, the newest unlisted one is installed instead, which costs one request listing the releases. An explicitly requested or pinned version is installed regardless. | none |

Templates can use `{name}`, `{tag}`, `{version}`, `{repo}` and, except in `:asset` itself, `{asset}`. Files ending in `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`, `.tar.bz2`, `.tbz2`, `.tar.zst`, `.tzst`, `.tar` or `.zip` are unpacked. Any other file ending in `.gz`, `.bz2` or `.zst` is a single compressed file, decompressed under its name without that suffix, so `taplo-linux-x86_64.gz` becomes `taplo-linux-x86_64`. Anything else is the binary itself. An entry that would land outside the unpack directory, or a tar entry that would write through a symlink the archive created, fails the install.

The command name is the key in `:bins`, independent of the package name. A package named `babashka` installs into `<store>/babashka/` and provides the command `bb`:

```clojure
{:source {:github "babashka/babashka"}
 :bins {"bb" "bb"}
 :platforms {"darwin-aarch64" {:asset "babashka-{version}-macos-aarch64.tar.gz"}
             "linux-x86_64" {:asset "babashka-{version}-linux-amd64-static.tar.gz"}}}
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
 :strip 1
 :layout :tree
 :bins {"hx" "hx"}
 :links {"~/.config/helix/runtime" "runtime"}
 :platforms {"linux-x86_64" {:asset "helix-{version}-x86_64-linux.tar.xz"}}}
```

### Sources

| Source | Latest release |
| --- | --- |
| `{:github "owner/repo"}` | The release GitHub marks as latest. |
| `{:github "owner/repo" :tag "nightly"}` | A rolling release: every build is an asset under one fixed tag, and `:asset` must contain `{version}`. The newest matching asset by upload time is the latest version. |
| `{:gitlab "gitlab.com/group/project"}` | GitLab's latest release. `:url` is required, because GitLab release links have no fixed shape. |
| `{:text "https://…"}` | A URL whose body is the latest tag, such as Kubernetes' `stable.txt`. `:url` is required. |
| `{:text "https://…" :match "(?m)^version = '([^']+)'"}` | The first group of a regular expression in the body, or the whole match without one, is the latest tag. |
| `{:text "https://…" :match "milestones/([0-9.]+)" :newest true}` | Every match in the body is a release, and the newest of them is the latest, for listings such as a download directory that are not in version order. |
| `{:json "https://…" :version [:releases 0 :version]}` | A JSON document holding the latest tag at the `:version` path of keys and indices, such as JetBrains' release API. `:url` is required. |
| `{:pypi "reuse"}` | The version PyPI reports as latest for the project. breto installs it with uv into a virtual environment of its own; see [Python packages](#python-packages). `:asset` and `:url` are filled in, and `:bins` defaults to the package name in the environment's `bin`. |
| `{:go "golang.org/x/vuln" :package "golang.org/x/vuln/cmd/govulncheck"}` | The version the Go module proxy reports as latest for the module. breto builds `:package`, which defaults to the module itself, with `go install`; see [Go modules](#go-modules). `:asset` and `:url` are filled in. |
| `{:npm "prettier"}` | The version the npm registry tags as latest for the package, which may be scoped, such as `@biomejs/biome`. breto installs it with npm into a directory of its own; see [npm packages](#npm-packages). `:asset` and `:url` are filled in, and `:bins` defaults to the package name in `node_modules/.bin`. |
| `{:cargo "ripgrep"}` | The newest version of the crate on crates.io that is neither yanked nor a pre-release, from the crates.io index. breto downloads the crate, checks it against the index, and builds it with cargo; see [Cargo crates](#cargo-crates). `:asset`, `:url` and `:checksum` are filled in, and `:bins` defaults to the package name in `bin`. |
| `{:cargo "himalaya-tui" :git "pimalaya/himalaya-tui"}` | The newest commit on the default branch of a crate that is only published as a GitHub repository. breto downloads the archive of that commit and builds it with cargo; see [Cargo crates](#cargo-crates). `:asset`, `:url` and `:verify false` are filled in, and `:bins` defaults to the package name in `bin`. |
| `{:coursier "org.scalameta:metals_2.13"}` | The newest final release of the module that Maven Central's metadata lists. breto resolves it with coursier and builds a standalone launcher; see [JVM applications](#jvm-applications). `:main` names the main class when the jar's manifest has none, and `:java-opts` are built into the launcher. `:asset` and `:url` are filled in, and `:bins` defaults to the package name in `bin`. |
| `{:ghcup "ghc"}` | The newest version of `ghc`, `cabal`, `hls` or `stack` that ghcup's metadata lists with a generic Linux or macOS download for the platform and does not tag as a prerelease. breto downloads and checks that bindist and has ghcup install it; see [Haskell tools](#haskell-tools). `:asset`, `:url`, `:checksum` and the commands are filled in. |

A tag is a release of the package when it starts with `:tag-prefix`. A repository that releases several components under one name each, such as `cli/v0.18.1` beside `helm/v9.0.0`, needs only the prefix of its own: when the release GitHub or GitLab marks as latest belongs to another component, breto takes the newest listed release with the package's prefix, which costs one request listing the releases. A repository that changed its naming scheme keeps the old prefix in a `:history` entry, and a tag then counts when its prefix is the one that applies to the version it names:

```clojure
{:source {:github "acme/tool"}
 :tag-prefix "release-"
 :history [{:before "2.0.0" :tag-prefix "v"}]
 :platforms {"linux-x86_64" {:asset "tool"}}}
```

Here `release-2.1.0` and `v1.9.0` are releases, while `v2.5.0` is not.

ble.sh publishes its nightly builds as a rolling release. The tarball holds shell scripts that run on every platform, so the asset is shared and each platform has an empty entry:

```clojure
{:source {:github "akinomyoga/ble.sh" :tag "nightly"}
 :asset "ble-nightly-{version}.tar.xz"
 :strip 1
 :layout :tree
 :bins {}
 :platforms {"darwin-aarch64" {} "darwin-x86_64" {} "linux-aarch64" {} "linux-x86_64" {}}}
```

### Go modules

A package with a `:go` source is built from source with the `go` command of the installed `go` package, its active version or else the newest installed one. Any batch installs `go` before the packages that need it, so `breto install go govulncheck` works on a fresh host. The build runs with `GOTOOLCHAIN=local`, `CGO_ENABLED=0`, an empty `GOFLAGS` and `-trimpath`, and the binary is moved into the store like a downloaded asset. Nothing builds with cgo, so no C compiler is needed.

The `go` command checks every module it downloads against the Go checksum database, sum.golang.org, so a module the proxy serves differently from the first time anyone fetched it fails. breto records the module's `h1:` hash, the one `go mod download -json` reports, in place of a SHA-256; a lock file entry with `:sum` makes a later build fail when the hash differs. `breto check` reports such packages as verifiable by the checksum database once the module zip exists on the proxy.

### Python packages

A package with a `:pypi` source is installed with the `uv` command of the installed `uv` package, which any batch installs first. uv resolves the project and all its dependencies into a requirements file that pins every file by its hash, creates a relocatable virtual environment in the version's directory, and installs exactly those files into it. Each version has an environment of its own, so projects can use different versions side by side. A dependency without a fitting wheel is built from its source distribution, which works for pure Python packages; one that needs a C compiler fails.

The environment's Python is one uv downloads itself, in the version `:python` of the definition or of the config names, kept in `:python-dir` and shared by every environment of that version. uv runs without the user's uv configuration and never uses a Python of the system or links one into the user's bin directory.

The resolution depends on a cut-off: uv considers only files published before it. breto records it with the hash of the requirements file, and a lock file entry carries both, so resolving again elsewhere gives the same file or fails:

```clojure
{"reuse@6.2.0" {:sha256 "…" :asset "requirements.txt" :exclude-newer "2026-10-28T13:53:11Z"}}
```

The files of a resolution are pinned by hash, the build dependencies of a source distribution are not.

### npm packages

A package with an `:npm` source is installed with the `npm` command of the installed `node` package, which any batch installs first: the version of `node` that the project, or the group being locked, pins next to it, else the active one. npm resolves the package and its dependencies, checks every tarball against the integrity hash the registry publishes for it, and installs them into the version's directory, recording the resolution in `package-lock.json`. Each version has a directory of its own, so projects can use different versions side by side.

npm runs with the npm registry, without the user's or the global npmrc, without `npm_config_` variables from the environment, with an empty cache of its own, and without install scripts, so a package whose install script downloads or builds its program does not work. The installed commands are usually scripts that start `node` from `PATH`, and those need `node` on `PATH` when they run.

The resolution depends on a cut-off: npm considers only versions published before it, given as `--before`. breto records it with the hash of `package-lock.json`, and a lock file entry carries both, so resolving again elsewhere gives the same file or fails:

```clojure
{"prettier@3.9.10" {:sha256 "…" :asset "package-lock.json" :exclude-newer "2026-10-29T10:12:45Z"}}
```

When the resolution differs from the lock, breto keeps the `package-lock.json` it got in `$XDG_STATE_HOME/breto/resolutions/<package>@<version>/` (`~/.local/state` by default) and names it in the report, so it can be compared with the file the lock was made from. The same holds for the `requirements.txt` of a pypi package. Go, uv and cargo builds likewise use the version of `go`, `uv` or `rustup` pinned next to them.

### Cargo crates

A package with a `:cargo` source is built from its crate on crates.io with the `cargo` proxy of the installed `rustup` package, which any batch installs first. breto downloads the `.crate` file, checks it against the checksum the crates.io index lists, and unpacks it, so a lock file records the hash of the crate like that of any other asset. `cargo install --locked` then builds it with the dependencies the crate's own `Cargo.lock` pins, which cargo checks against the index, and only the built binaries are kept.

A crate that is not on crates.io is built from its GitHub repository with `:git "OWNER/REPO"` beside `:cargo`. Its version is the date of the newest commit on the default branch in UTC, followed by the commit, such as `2026.10.27.142816+<commit>`, so versions order by date and each names the source it was built from. breto downloads GitHub's archive of that commit. Nothing upstream lists a checksum for it, so the definition sets `:verify false`: the commit in the URL pins the source, and a lock file records the archive's hash, which every later install of that version must match. Should GitHub ever serve different bytes for that archive, an install from the lock file fails instead of building them.

cargo runs with the toolchain `rustup default` selects and reads the user's cargo configuration. Building needs a C linker such as `cc`, and some crates need further system libraries.

### JVM applications

A package with a `:coursier` source is built with the `cs` command of the installed `coursier` package, which any batch installs first. coursier resolves the module from Maven Central alone, ignoring the user's repositories, mirrors and Maven settings, into a cache inside the install's work directory, and checks every file against the SHA-256 or SHA-1 checksum Central publishes beside it, failing for a file without one. breto then lists every resolved jar with its sha256, and a lock file records the hash of that listing, so resolving the same version elsewhere fails when any jar differs. `cs bootstrap --standalone` builds the launcher from that cache without the network, with every jar inside it, so it needs no coursier cache to run, only a java: `$JAVA_HOME/bin/java` when it exists, else `java` on `PATH`.

Maven Central never replaces a published file, so the same version resolves to the same jars unless a POM in the tree names a version range, which can pick up a newer release later.

### Haskell tools

A package with a `:ghcup` source is installed by the `ghcup` command of the installed `ghcup` package, which any batch installs first. breto reads ghcup's metadata, downloads the generic bindist it lists for the version and platform, on Linux the one ghcup itself uses on a distribution without a bindist of its own, and checks it against the hash listed beside it, so a lock file records the hash of the bindist like that of any other asset. ghcup then installs it with `--isolate` into the version directory, from a copy of the metadata that points the download at the verified file, with `--platform` naming that generic bindist. GHC and HLS bindists write their install location into their wrapper scripts, so ghcup installs straight into the store instead of a work directory, and a version counts as installed only once breto has recorded it.

ghcup keeps its cache, logs and configuration in the work directory of the install, so the user's `~/.ghcup` and its configuration take no part. GHC needs `gcc`, `g++`, `make`, `perl`, `pkg-config` and the gmp and ncurses libraries, and HLS needs `make`. Every command in GHC's and HLS's `bin` directory is linked, so `ghc` sits beside `ghc-9.14.1`, and HLS brings one server per GHC version it supports and `haskell-language-server-wrapper`, which picks the one matching a project's compiler.

### Rust

`rustup` links the rustup binary as `rustup`, `cargo`, `rustc`, `rustfmt`, `rust-analyzer`, `cargo-clippy` and the other commands it proxies. Each proxy runs the toolchain the nearest `rust-toolchain.toml` names, else the one `rustup default` chose, and rustup installs a missing toolchain on first use. Toolchains live in `~/.rustup` and are downloaded and checked by rustup, outside of breto's store and lock files. breto updates rustup itself, so `rustup self update` is not needed. Linking a Rust program needs a C linker such as `cc`.

### Verification

A download from a GitHub release is checked against the sha256 digest GitHub recorded for the asset. Assets uploaded before GitHub began recording digests carry none; for those, and for downloads from other hosts, breto uses the file named by `:checksum`. A checksum file can list many assets in `sha256sum` format or hold a single hash. The algorithm follows from the length of the hash.

Some projects publish their hashes in a JSON index instead. `{:json URL :hash [path]}` reads the hash at a path of keys, indices and field selectors, whose strings are templates, so Zig's index gives the hash of each release. A URL whose path ends in `.yaml` or `.yml` is read as YAML. A selector such as `{"vers" "{version}"}` picks the first element of a list whose fields have those values, and a document of one JSON value per line, such as the crates.io index, is the list of them:

```clojure
:checksum {:json "https://ziglang.org/download/index.json" :hash ["{version}" "x86_64-linux" "shasum"]}
```

A download with neither a digest nor a checksum file is refused unless `--no-verify` is given. A failed digest lookup, for example once the API limit is reached, falls back to the checksum file when there is one and fails otherwise.

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
| `:of` | `:asset` when the bundle signs the asset, `:checksum` when it signs the checksum file, whose hash is then trusted, or `{:file "path"}` when it signs a file inside an archive asset. Defaults to `:asset`. |

Some projects sign the binary inside the archive instead of the archive itself. `{:file "path"}` names that binary as a template, relative to the unpacked archive after `:strip`; breto verifies the download by its digest or checksum file first, unpacks it, and then checks the signature of the unpacked file before anything is placed. An archive that lacks the file fails the install. codex signs its binaries this way:

```clojure
:signature {:bundle "codex-x86_64-unknown-linux-musl.sigstore"
            :of {:file "codex-x86_64-unknown-linux-musl"}
            :identity "https://github.com/openai/codex/.github/workflows/rust-release.yml@refs/tags/{tag}"
            :issuer "https://token.actions.githubusercontent.com"}
```

The signer's identity is in the certificate inside the bundle. Projects usually document it next to their verification instructions. Signed packages need cosign: list `cosign` in `:packages`. `install` and `update` handle cosign before every other package, so on a fresh host the first run installs it, checked by its GitHub digest or checksum file only, since no cosign exists yet to check its signature, and then checks the other signatures with it. The report marks that install. Later updates of cosign are checked by the version already installed. `breto check` confirms the bundle is in the release, and `breto doctor` that cosign can run.

### Groups

A file named `@NAME.edn` in a registry defines a group of packages:

```clojure
{:members ["himalaya" "cardamum" "tcard" "calendula" "tcal"]}
```

In a project, a group's `:env` and `:commands` apply as well; see [Project variables and commands](#project-variables-and-commands).

`"@pimalaya"` in `:packages`, or as an argument to `install`, `update`, `outdated`, `check`, `gc` and `uninstall`, stands for its members, in order. Members can be groups themselves; a package listed twice is handled once. A package listed next to a group takes the place of the group's member for it, so `["@lint" "node@24.11.0"]` uses node 24.11.0 where `@lint` pins another version. Everything after that sees the packages: the lock file lists each member, and reports name them. `breto info @pimalaya` shows the members and the files that define the group. A later registry directory replaces the members of a group with the same name, so an overlay can trim or extend one.

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

### Platforms

breto treats the four platforms alike: `linux-x86_64`, `linux-aarch64`, `darwin-x86_64` and `darwin-aarch64`. The top level of a definition holds what every platform shares, and `:platforms` lists each platform the project publishes a build for, with the keys that differ there, such as the asset name, the archive layout or the commands:

```clojure
{:source {:github "sigstore/cosign"}
 :checksum "cosign_checksums.txt"
 :platforms {"darwin-aarch64" {:asset "cosign-darwin-arm64"}
             "darwin-x86_64" {:asset "cosign-darwin-amd64"}
             "linux-aarch64" {:asset "cosign-linux-arm64"}
             "linux-x86_64" {:asset "cosign-linux-amd64"}}}
```

A platform without an entry has no release. The top level holds only what holds on every platform: a value that differs by platform, such as a checksum file or a path inside the archive that names the platform, goes into the entry, and one that only repeats the asset name uses `{asset}` and stays shared, such as `:checksum "{asset}.sha256"`. A new platform entry then inherits nothing that belongs to another platform. An entry can have a `:history` of its own, which applies over the definition's `:history` for that platform. Definitions with a `:go`, `:pypi`, `:npm`, `:cargo` or `:coursier` source build or resolve the package on the machine and serve every platform without entries, and `:ghcup` serves every platform. An overlay's `:platforms` entries are merged key by key into those of the definition it is merged over, so an overlay can add a platform to an upstream definition or change one key of one platform.

A package named on the command line that has no release for the platform fails to install and says which platforms it has. One that a group, the config's `:packages` or a project brings in is skipped with a warning instead, so a shared group or project file still works on every platform for the tools that exist there. `breto check --platforms all` checks the release assets of every platform from one machine.

A lock entry keeps the hashes of each platform's download under `:platforms`, so machines of different platforms share one lock file; a package built on the machine has one set of hashes at the top level:

```clojure
{"cosign@3.0.2" {:platforms {"darwin-aarch64" {:sha256 "…" :asset "cosign-darwin-arm64"}
                             "linux-x86_64" {:sha256 "…" :asset "cosign-linux-amd64"}}}}
```

A lock file written by an earlier breto holds the `linux-x86_64` hashes at the top level of an entry; breto reads them as that platform's and moves them under `:platforms` when it next writes the entry.

### Version history

Projects rename assets, change archive formats and move between hosts. The keys of a definition describe current releases, and each `:history` entry overrides keys for every version older than its `:before`. Entries apply cumulatively from the newest boundary down, so an entry only lists what changed at its boundary:

```clojure
{:source {:github "ulyssa/iamb"}
 :strip 1
 :platforms {"linux-x86_64" {:asset "iamb-{version}-x86_64-unknown-linux-musl.tar.gz"
                             :history [{:before "0.0.13" :asset "iamb-x86_64-unknown-linux-musl-binary.zip" :strip 0}
                                       {:before "0.0.12" :asset "iamb-x86_64-unknown-linux-musl.tgz" :strip 1}]}}}
```

`{:before "0.5.1" :verify false}` installs releases older than 0.5.1 without verification, because GitHub only records asset digests since mid-2025, while 0.5.1 and newer stay verified. The report marks every install made this way.

Version 0.0.11 installs `iamb-x86_64-unknown-linux-musl.tgz` with one leading component stripped, and 0.13.1 uses the current keys. A project that moved from GitLab to GitHub keeps one package name with an entry such as `{:before "2.0.0" :source {:gitlab "gitlab.com/group/project"} :url "…"}`, and both old and new versions stay installable. Defaults such as the GitHub download URL are worked out separately for each entry.

Versions compare by their runs of digits and letters, with numbers compared numerically, so `0.0.13` follows `0.0.12` and `2026.9` follows `2025.12`.

## Development

The `.breto.edn` names the `metio/toolchains` groups that pin babashka and every lint tool CI runs. With that registry under `:extra-registries`, `breto env` links them into the project:

```shell
eval "$(breto env)"
bb test
```

`bb convert SCRIPT-DIR OUT-DIR` turns shell wrappers around the `install-binary-from-github` helper scripts into package definitions. It runs each wrapper with stub helpers that record their arguments, so no download happens.

`bb descriptions AQUA-REGISTRY-DIR PACKAGES-DIR` fills in the missing descriptions of a definitions directory, such as a clone of metio/packages, from the aqua registry or the description of the project's repository.

## License

0BSD, REUSE-compliant.
