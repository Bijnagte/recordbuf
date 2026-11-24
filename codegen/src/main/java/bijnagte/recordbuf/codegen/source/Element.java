package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.SourceRenderer;
import java.util.List;

/**
 * A single element inside a method, constructor, or {@code static {}} body. A body is a {@code
 * List<Element>}; the {@link SourceRenderer} owns brace, paren, and join layout for the list, not
 * the emitter.
 *
 * <p>This is the closed idiom set the body-lift is building toward (DEC-3): recurring element
 * shapes ({@code Return/Throw/ExprStatement/LocalVar}, a line {@code Comment}, an {@link If}
 * control-flow chain) plus the {@link Fragment} escape used for syntax the closed set has not yet
 * modeled -- the control-flow and switch idioms land as the heavier mappings convert. Fragments may
 * coexist with modeled idioms but are counted, not celebrated: a growing fragment count signals an
 * idiom the closed set has not yet learned.
 *
 * <p>The node set grows incrementally as the mappings convert; each new node renders
 * byte-identically to the flat {@code Line} it replaced.
 */
public sealed interface Element
    permits Fragment, Return, Throw, ExprStatement, LocalVar, Comment, If, While, For, Switch {

  /**
   * Wrap a body-line string as a {@link Fragment}.
   *
   * @param text the line content, rendered verbatim at the block's indent
   */
  static Fragment of(String text) {
    return new Fragment(text);
  }

  static Return ret(String template, Object... args) {
    return new Return(RawExpr.of(template, args));
  }

  static Return ret(RawExpr expr) {
    return new Return(expr);
  }

  static Return ret() {
    return new Return(null);
  }

  static Throw throwOf(String template, Object... args) {
    return new Throw(RawExpr.of(template, args));
  }

  static Throw throwOf(RawExpr expr) {
    return new Throw(expr);
  }

  static ExprStatement expr(String e) {
    return new ExprStatement(RawExpr.of(e));
  }

  /** Build an {@link ExprStatement} from a template string with {@code @N} positional args. */
  static ExprStatement expr(String template, Object... args) {
    return new ExprStatement(RawExpr.of(template, args));
  }

  /** Build an {@link ExprStatement} from a pre-built {@link RawExpr}. */
  static ExprStatement expr(RawExpr rawExpr) {
    return new ExprStatement(rawExpr);
  }

  static LocalVar local(String fqn, String name, String init) {
    return new LocalVar(null, TypeRef.of(fqn), name, RawExpr.of(init));
  }

  /** A local declaration carrying annotations, rendered ahead of its type. */
  static LocalVar local(String fqn, String name, String init, Annotation... annotations) {
    return new LocalVar(
        annotations == null ? null : List.of(annotations), TypeRef.of(fqn), name, RawExpr.of(init));
  }

  /**
   * A local variable with an {@code @N}-templated initializer built via the {@link
   * LocalVar.Builder}.
   */
  static LocalVar local(TypeRef type, String name, String initTemplate, Object... initArgs) {
    return LocalVar.builder(type, name).assigned(initTemplate, initArgs);
  }

  static Comment comment(String text) {
    return new Comment(text);
  }
}
