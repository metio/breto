# SPDX-FileCopyrightText: The breto Authors
# SPDX-License-Identifier: 0BSD

{
  description = "Declarative installs of prebuilt binaries";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/65179426c83bb3f6bc14898b42ea1c6f01d374b0";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      runtimeTools = pkgs: with pkgs; [
        babashka
        gnutar # archive extraction, including --strip-components
        gzip
        xz
        bzip2
        zstd
      ];

      ciTools = pkgs: with pkgs; [
        reuse
        typos
        yamllint
        actionlint
        shellcheck # actionlint shells out to it for run: blocks
        markdownlint-cli2
      ];
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = (runtimeTools pkgs) ++ (ciTools pkgs);
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
