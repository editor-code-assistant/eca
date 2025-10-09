{
  description = "Editor Code Assistant (ECA) - AI pair programming capabilities agnostic of editor ";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, clj-nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        cljpkgs = clj-nix.packages."${system}";

        jdk = pkgs.jdk21_headless;
        graalvm = pkgs.graalvmPackages.graalvm-ce;
        clojure = pkgs.clojure.override { jdk = pkgs.jdk21_headless; };

      in
      {
        devShells.default =
          let
            deps-lock-update = pkgs.writeShellApplication {
              name = "deps-lock-update";
              runtimeInputs = [ cljpkgs.deps-lock ];
              text = "deps-lock --bb --alias-exclude debug";
            };
          in
            pkgs.mkShell
              {
                packages = [ deps-lock-update ];
                nativeBuildInputs = with pkgs; [
                  babashka
                  graalvm
                  jdk
                  clojure
                  clojure-lsp
                  git
                ];

                env = {
                  GRAALVM_HOME = "${graalvm}";
                  JAVA_HOME = "${jdk}";
                };
              };

        packages = rec {
          default = eca;

          eca-jdk = cljpkgs.mkCljBin {
            projectSrc = ./.;
            name = "com.github.editor-code-assistant/eca";
            main-ns = "eca.main";
            buildInputs = [ pkgs.babashka ];

            jdkRunner = jdk;
            buildCommand =
              ''
                bb prod-jar
                export jarPath=eca.jar
              '';
            doCheck = true;
            checkPhase = "bb test";
          };

          eca = cljpkgs.mkGraalBin {
            cljDrv = self.packages."${system}".eca-jdk;
          };
        };

      }) // {
        overlays.default = (final: prev: {
          eca = self.packages.${final.system}.default;
        });
      };
}
