package com.example.stubgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StubTransformerTest {

  private final StubTransformer transformer = new StubTransformer(Set.of("com.example.Internal"));

  private String stub(String source) {
    CompilationUnit cu = StaticJavaParser.parse(source);
    return transformer.transform(cu).orElseThrow().toString();
  }

  @Test
  void メソッド本体を除去しJavadocとシグネチャを残す() {
    String out =
        stub(
            """
            package p;
            public class A {
              /** 挨拶を返す。 */
              public String hello(String name) { return "hi " + name; }
            }
            """);
    assertTrue(out.contains("挨拶を返す。"));
    assertTrue(out.contains("public String hello(String name);"));
    assertFalse(out.contains("return"));
  }

  @Test
  void publicとprotected以外のメンバを除去する() {
    String out =
        stub(
            """
            package p;
            public class A {
              public void a() {}
              protected void b() {}
              void c() {}
              private void d() {}
              private int secret;
              static { System.out.println(); }
            }
            """);
    assertTrue(out.contains("void a()"));
    assertTrue(out.contains("void b()"));
    assertFalse(out.contains("void c()"));
    assertFalse(out.contains("void d()"));
    assertFalse(out.contains("secret"));
    assertFalse(out.contains("static {"));
  }

  @Test
  void 内部向けアノテーションの型とメンバを除去する() {
    String out =
        stub(
            """
            package p;
            import com.example.Internal;
            public class A {
              public void open() {}
              @Internal public void hidden() {}
              @com.example.Internal public static class Nested {}
              public enum E { X, @Internal Y }
            }
            """);
    assertTrue(out.contains("open()"));
    assertFalse(out.contains("hidden"));
    assertFalse(out.contains("Nested"));
    assertTrue(out.contains("X"));
    assertFalse(out.contains("Y"));
    // 除去により不要になったimportも消える
    assertFalse(out.contains("import com.example.Internal"));
  }

  @Test
  void 内部向けのトップレベル型や非public型のみのファイルは出力しない() {
    Optional<CompilationUnit> internal =
        transformer.transform(StaticJavaParser.parse("package p; @Internal public class A {}"));
    Optional<CompilationUnit> packagePrivate =
        transformer.transform(StaticJavaParser.parse("package p; class B {}"));
    assertTrue(internal.isEmpty());
    assertTrue(packagePrivate.isEmpty());
  }

  @Test
  void interfaceのメンバは修飾子なしでも残しprivateメソッドは除去する() {
    String out =
        stub(
            """
            package p;
            public interface I {
              int LIMIT = 10;
              void run();
              default void twice() { run(); run(); }
              private void helper() {}
            }
            """);
    assertTrue(out.contains("int LIMIT = 10;"));
    assertTrue(out.contains("void run();"));
    assertTrue(out.contains("default void twice();"));
    assertFalse(out.contains("helper"));
  }

  @Test
  void 定数リテラルだけ初期値を残す() {
    String out =
        stub(
            """
            package p;
            import java.util.List;
            import java.util.ArrayList;
            public class A {
              public static final int MAX = -1;
              public static final String NAME = "x";
              public static final List<String> LIST = new ArrayList<>();
              public int counter = 3;
            }
            """);
    assertTrue(out.contains("MAX = -1;"));
    assertTrue(out.contains("NAME = \"x\";"));
    assertTrue(out.contains("List<String> LIST;"));
    assertTrue(out.contains("int counter;"));
    assertTrue(out.contains("import java.util.List;"));
    assertFalse(out.contains("ArrayList"));
  }

  @Test
  void enum定数のクラス本体とコンストラクタ本体を除去する() {
    String out =
        stub(
            """
            package p;
            public enum Op {
              PLUS("+") { public int apply(int a, int b) { return a + b; } };
              private final String symbol;
              Op(String symbol) { this.symbol = symbol; }
              public String symbol() { return symbol; }
            }
            """);
    assertTrue(out.contains("PLUS(\"+\")"));
    assertFalse(out.contains("apply"));
    assertFalse(out.contains("this.symbol"));
    assertTrue(out.contains("public String symbol();"));
  }

  @Test
  void Javadocで参照されるimportは残す() {
    String out =
        stub(
            """
            package p;
            import java.util.Map;
            public class A {
              /** {@link Map}を返す。 */
              public Object get() { return null; }
            }
            """);
    assertTrue(out.contains("import java.util.Map;"));
  }

  @Test
  void Lombokのimportを検出する() {
    assertTrue(
        StubTransformer.usesLombok(
            StaticJavaParser.parse("package p; import lombok.Getter; public class A {}")));
  }
}
