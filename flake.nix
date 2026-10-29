{
  description = "内製フレームワークのAPIスタブ生成ツール – JDK 25 + Gradle dev environment";

  # SpringBootStudyと同じinputs / flake.lockを使い、jdk25・gradle_9のstore pathを共有してディスクを節約する。
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    treefmt-nix.url = "github:numtide/treefmt-nix";
  };

  outputs = { self, nixpkgs, flake-utils, treefmt-nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        treefmtEval = treefmt-nix.lib.evalModule pkgs {
          projectRootFile = "flake.nix";

          # Markdown
          # mdformat 本体は thematic break を `___` で出力する仕様で設定不可のため、
          # `---` で出力する mdformat-simple-breaks プラグインを同梱した独自パッケージを使う。
          settings.formatter.mdformat = {
            command = "${pkgs.mdformat.withPlugins (ps: with ps; [ mdformat-simple-breaks ])}/bin/mdformat";
            includes = [ "*.md" ];
          };

          # Java
          programs.google-java-format.enable = true;
        };
      in {
        # --- フォーマッタ（nix fmt で一括実行）---
        formatter = treefmtEval.config.build.wrapper;

        # --- 開発シェル ---
        devShells.default = pkgs.mkShell {
          name = "api-stub-generator";

          packages = with pkgs; [
            jdk25
            gradle_9
            git
          ];

          # IntelliJ 等は JDK ホームを release ファイルで検証するため jdk25.home を渡す。
          JAVA_HOME = "${pkgs.jdk25.home}";

          # プロジェクト内にキャッシュ（~/.gradle を汚さない）
          GRADLE_USER_HOME = ".gradle-home";

          shellHook = ''
            echo "──────────────────────────────────────────────"
            echo " API Stub Generator Shell"
            echo " Java  : $(java -version 2>&1 | head -1)"
            echo " Gradle: $(gradle --version 2>/dev/null | grep '^Gradle' || echo 'not found')"
            echo ""
            echo " 使い方: README.md 参照"
            echo "   gradle test"
            echo "   gradle run --args=\"--src <dir> --out <dir> --internal-annotation <name>\""
            echo "──────────────────────────────────────────────"
          '';
        };
      }
    );
}
