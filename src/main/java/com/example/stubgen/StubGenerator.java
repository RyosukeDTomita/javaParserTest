package com.example.stubgen;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ソースディレクトリ配下の.javaをスタブ化し、同じ相対パスで出力ディレクトリに書き出すCLI。
 *
 * <pre>
 * --src &lt;dir&gt;                  入力ソースルート(複数指定可。マルチモジュールなら各src/main/java)
 * --out &lt;dir&gt;                  出力先
 * --internal-annotation &lt;name&gt;  除外するアノテーション(複数指定可。単純名 or 完全修飾名)
 * --language-level &lt;level&gt;     JavaParserの言語レベル(既定: JAVA_21)
 * </pre>
 */
public final class StubGenerator {

  private record Options(
      List<Path> srcDirs,
      Path outDir,
      Set<String> internalAnnotations,
      ParserConfiguration.LanguageLevel languageLevel) {}

  private record Summary(int written, int skipped, List<Path> failed, List<Path> lombok) {}

  public static void main(String[] args) throws IOException {
    Options options = parseArgs(args);
    if (options.internalAnnotations().isEmpty()) {
      System.err.println("警告: --internal-annotation が未指定のため、アノテーションによる除外は行いません");
    }
    Summary summary = generate(options);
    System.out.printf(
        "出力: %d, スキップ(公開型なし): %d, パース失敗: %d%n",
        summary.written(), summary.skipped(), summary.failed().size());
    if (!summary.lombok().isEmpty()) {
      System.err.println("警告: Lombokを使うファイルがあります。生成メンバはスタブに出ないため、delombok後のソースを入力してください:");
      summary.lombok().forEach(p -> System.err.println("  " + p));
    }
    if (!summary.failed().isEmpty()) {
      System.exit(1);
    }
  }

  private static Summary generate(Options options) throws IOException {
    JavaParser parser =
        new JavaParser(new ParserConfiguration().setLanguageLevel(options.languageLevel()));
    StubTransformer transformer = new StubTransformer(options.internalAnnotations());
    int written = 0;
    int skipped = 0;
    List<Path> failed = new ArrayList<>();
    List<Path> lombok = new ArrayList<>();

    for (Path srcDir : options.srcDirs()) {
      for (Path file : listJavaFiles(srcDir)) {
        ParseResult<CompilationUnit> result = parser.parse(file);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
          failed.add(file);
          System.err.println("パース失敗: " + file);
          result.getProblems().forEach(p -> System.err.println("  " + p.getVerboseMessage()));
          continue;
        }
        CompilationUnit cu = result.getResult().get();
        if (StubTransformer.usesLombok(cu)) {
          lombok.add(file);
        }
        Optional<CompilationUnit> stub = transformer.transform(cu);
        if (stub.isEmpty()) {
          skipped++;
          continue;
        }
        Path outFile = options.outDir().resolve(srcDir.relativize(file));
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, stub.get().toString());
        written++;
      }
    }
    return new Summary(written, skipped, failed, lombok);
  }

  private static List<Path> listJavaFiles(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      return paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static Options parseArgs(String[] args) {
    List<Path> srcDirs = new ArrayList<>();
    Path outDir = null;
    Set<String> internalAnnotations = new LinkedHashSet<>();
    ParserConfiguration.LanguageLevel languageLevel = ParserConfiguration.LanguageLevel.JAVA_21;

    for (int i = 0; i < args.length; i++) {
      String value = i + 1 < args.length ? args[i + 1] : null;
      switch (args[i]) {
        case "--src" -> srcDirs.add(Path.of(require(args[i], value)));
        case "--out" -> outDir = Path.of(require(args[i], value));
        case "--internal-annotation" -> internalAnnotations.add(require(args[i], value));
        case "--language-level" ->
            languageLevel = ParserConfiguration.LanguageLevel.valueOf(require(args[i], value));
        default -> usage("不明な引数: " + args[i]);
      }
      i++;
    }
    if (srcDirs.isEmpty() || outDir == null) {
      usage("--src と --out は必須です");
    }
    for (Path srcDir : srcDirs) {
      if (!Files.isDirectory(srcDir)) {
        usage("ディレクトリが存在しません: " + srcDir.toAbsolutePath());
      }
    }
    return new Options(srcDirs, outDir, internalAnnotations, languageLevel);
  }

  private static String require(String flag, String value) {
    if (value == null) {
      usage(flag + " に値がありません");
    }
    return value;
  }

  private static void usage(String message) {
    System.err.println(message);
    System.err.println(
        "usage: --src <dir> [--src <dir>...] --out <dir> [--internal-annotation <name>...]"
            + " [--language-level JAVA_21]");
    System.exit(2);
  }
}
