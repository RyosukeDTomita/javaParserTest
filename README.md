# api-stub-generator

内製フレームワークのソースから実装を取り除き、AIエージェントに読ませるためのAPIスタブ(Javadoc + シグネチャ)を生成するツール。

---

## 環境構築

JDK / Gradleはnix flakeで管理する。`flake.lock`は`~/SpringBootStudy`と同じものを使い、`jdk25`・`gradle_9`のstore pathを共有している(ディスク節約のため、更新するときは両方そろえる)。

```bash
direnv allow        # もしくは nix develop
gradle test
```

---

## 使い方

```bash
gradle run --args="--src <framework>/src/main/java --out build/stubs --internal-annotation Internal"
```

| 引数 | 説明 |
|---|---|
| `--src <dir>` | 入力ソースルート。複数指定可(マルチモジュールなら各モジュールの`src/main/java`を並べる) |
| `--out <dir>` | 出力先。入力と同じ相対パスで`.java`を書き出す |
| `--internal-annotation <name>` | 除外するアノテーション。複数指定可。単純名 / 完全修飾名どちらでもよい(比較は単純名) |
| `--language-level <level>` | JavaParserの言語レベル。既定は`JAVA_21` |

相対パスはプロジェクトルート基準で解決される(`gradle run`の作業ディレクトリ)。

終了コードは、パース失敗があれば`1`、引数エラーなら`2`。

---

## 変換ルール

- 除去するもの
  - 内部向けアノテーションが付いた型・メンバ・enum定数
  - public / protected以外のメンバ(interface / annotationのメンバは暗黙publicとして残す。privateメソッドは除去)
  - 非publicのトップレベル型(公開型が残らないファイルは出力しない)
  - メソッド本体、初期化ブロック、enum定数のクラス本体
  - 上記の除去で不要になったimport
- 残すもの
  - Javadoc、シグネチャ、アノテーション
  - static finalかつリテラルの定数の初期値(例: `MAX = -1`)
  - コンストラクタ(本体は空ブロック)
  - `module-info.java` / `package-info.java`(そのまま出力)

---

## 注意

- Lombokなどで生成されるメンバはASTに存在しないため、スタブに出ない。Lombokをimportしているファイルは実行時に警告を出すので、該当する場合はdelombok後のソースを入力する
- アノテーション名は単純名で比較するため、同じ単純名の別アノテーションも除外対象になる
- 出力はコンパイルできる形ではない(`default`メソッドも`;`で終わる)。読ませるためのスタブなので意図どおり
