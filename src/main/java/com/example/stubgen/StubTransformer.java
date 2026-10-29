package com.example.stubgen;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CompilationUnitから実装を取り除き、利用者向けのAPIスタブに変換する。
 *
 * <ul>
 *   <li>内部向けアノテーションが付いた型・メンバを除去
 *   <li>public / protected以外のメンバを除去(interface / annotationのメンバは暗黙publicとして扱う)
 *   <li>メソッド本体・コンストラクタ本体・初期化ブロック・enum定数のクラス本体を除去
 *   <li>フィールド初期値は、static finalかつリテラルの定数のみ残す
 *   <li>上記の除去で不要になったimportを除去
 * </ul>
 */
public final class StubTransformer {

  /** 比較用に単純名へ正規化した内部向けアノテーション名。 */
  private final Set<String> internalAnnotations;

  /**
   * @param internalAnnotations 内部向けアノテーション名。単純名(`Internal`)でも完全修飾名でもよいが、比較は単純名で行う。
   */
  public StubTransformer(Set<String> internalAnnotations) {
    this.internalAnnotations =
        internalAnnotations.stream().map(StubTransformer::simpleName).collect(Collectors.toSet());
  }

  /**
   * CUをスタブに変換する(引数のCUを破壊的に変更する)。
   *
   * @return 公開する型が1つも残らなければ空
   */
  public Optional<CompilationUnit> transform(CompilationUnit cu) {
    // module-info / package-info は宣言とJavadocだけなのでそのまま出す
    if (cu.getModule().isPresent() || cu.getTypes().isEmpty()) {
      return Optional.of(cu);
    }
    for (TypeDeclaration<?> type : new ArrayList<>(cu.getTypes())) {
      if (!type.isPublic() || isInternal(type)) {
        type.remove();
      } else {
        stripType(type);
      }
    }
    if (cu.getTypes().isEmpty()) {
      return Optional.empty();
    }
    removeUnusedImports(cu);
    return Optional.of(cu);
  }

  /** CUがLombokをimportしているか。Lombokが生成するメンバはASTに現れないため、警告に使う。 */
  public static boolean usesLombok(CompilationUnit cu) {
    return cu.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("lombok"));
  }

  private void stripType(TypeDeclaration<?> type) {
    if (type instanceof EnumDeclaration enumDecl) {
      for (EnumConstantDeclaration constant : new ArrayList<>(enumDecl.getEntries())) {
        if (isInternal(constant)) {
          constant.remove();
        } else {
          constant.getClassBody().clear();
        }
      }
    }
    boolean implicitlyPublic = isImplicitlyPublicContainer(type);
    for (BodyDeclaration<?> member : new ArrayList<>(type.getMembers())) {
      if (!isExposed(member, implicitlyPublic)) {
        member.remove();
        continue;
      }
      stripMember(member, implicitlyPublic);
    }
  }

  private void stripMember(BodyDeclaration<?> member, boolean inImplicitlyPublic) {
    if (member instanceof MethodDeclaration method) {
      method.removeBody();
    } else if (member instanceof ConstructorDeclaration constructor) {
      constructor.setBody(new BlockStmt());
    } else if (member instanceof CompactConstructorDeclaration compact) {
      compact.setBody(new BlockStmt());
    } else if (member instanceof FieldDeclaration field) {
      stripField(field, inImplicitlyPublic);
    } else if (member instanceof TypeDeclaration<?> nested) {
      stripType(nested);
    }
  }

  private void stripField(FieldDeclaration field, boolean inImplicitlyPublic) {
    // interface / annotationのフィールドは暗黙static final
    boolean isConstant = field.isStatic() && field.isFinal() || inImplicitlyPublic;
    for (VariableDeclarator variable : field.getVariables()) {
      boolean keep = isConstant && variable.getInitializer().map(this::isLiteral).orElse(false);
      if (!keep) {
        variable.removeInitializer();
      }
    }
  }

  private boolean isLiteral(Expression expr) {
    // -1 のような符号付きリテラルもUnaryExprとして定数扱いにする
    return expr.isLiteralExpr()
        || expr.isUnaryExpr() && expr.asUnaryExpr().getExpression().isLiteralExpr();
  }

  /** 利用者から見えるメンバか。 */
  private boolean isExposed(BodyDeclaration<?> member, boolean implicitlyPublic) {
    if (member instanceof InitializerDeclaration) {
      return false;
    }
    if (member instanceof NodeWithAnnotations<?> annotated && isInternal(annotated)) {
      return false;
    }
    if (!(member instanceof NodeWithModifiers<?> modified)) {
      return true;
    }
    if (modified.hasModifier(Modifier.Keyword.PRIVATE)) {
      return false;
    }
    return implicitlyPublic
        || modified.hasModifier(Modifier.Keyword.PUBLIC)
        || modified.hasModifier(Modifier.Keyword.PROTECTED);
  }

  /** interface / annotationのメンバはアクセス修飾子なしでもpublic。 */
  private static boolean isImplicitlyPublicContainer(TypeDeclaration<?> type) {
    return type instanceof AnnotationDeclaration
        || type instanceof ClassOrInterfaceDeclaration c && c.isInterface();
  }

  private boolean isInternal(NodeWithAnnotations<?> node) {
    for (AnnotationExpr annotation : node.getAnnotations()) {
      if (internalAnnotations.contains(simpleName(annotation.getNameAsString()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * スタブ化で使われなくなったimportを除去する。
   *
   * <p>import以外の出力テキスト(Javadocを含む)に単純名が単語として現れるかで判定する。Javadocの{@code {@link Foo}}が壊れないよう、
   * 型解決ではなく文字列で保守的に判定している。
   */
  private static void removeUnusedImports(CompilationUnit cu) {
    List<ImportDeclaration> imports = new ArrayList<>(cu.getImports());
    cu.getImports().clear();
    String body = cu.toString();
    for (ImportDeclaration imp : imports) {
      if (imp.isAsterisk() || isReferenced(body, simpleName(imp.getNameAsString()))) {
        cu.addImport(imp);
      }
    }
  }

  private static boolean isReferenced(String text, String name) {
    return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
  }

  private static String simpleName(String name) {
    return name.substring(name.lastIndexOf('.') + 1);
  }
}
