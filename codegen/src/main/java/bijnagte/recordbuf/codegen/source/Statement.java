package bijnagte.recordbuf.codegen.source;

/**
 * A single statement inside a method, constructor, or {@code static {}} body. A body is a {@link
 * java.util.List List<Statement>}; the {@link SourceRenderer} owns brace, paren, and join layout
 * for the list, not the emitter.
 *
 * <p>This is the closed idiom set the body-lift is building toward (DEC-3): recurring statement
 * shapes ({@code Return/Throw/ExprStatement/LocalVar}, a line {@code Comment}, an {@link If}
 * control-flow chain) plus the {@link Fragment} escape used for syntax the closed set has not yet
 * modeled -- the control-flow and switch idioms land as the heavier mappings convert. Fragments may
 * coexist with modeled idioms but are counted, not celebrated: a growing fragment count signals an
 * idiom the closed set has not yet learned.
 *
 * <p>The node set grows incrementally as the mappings convert; each new node renders
 * byte-identically to the flat {@code Line} it replaced.
 */
public sealed interface Statement
    permits Statement.Fragment,
        Statement.Return,
        Statement.Throw,
        Statement.ExprStatement,
        Statement.LocalVar,
        Statement.Comment,
        If,
        While,
        For,
        Switch {

  /**
   * A raw body-line fragment whose text the renderer emits verbatim at the block's indent -- the
   * documented escape hatch for syntax the closed idiom set has not yet modeled. Its text already
   * carries any local indent the old flat-line emitter hand-bookkept, so wrapping a legacy body
   * line for it is byte-identical.
   */
  record Fragment(String text) implements Statement {}

  /** {@code return <expr>;} or, when {@code expr} is null, a bare {@code return;}. */
  record Return(String expr) implements Statement {}

  /** {@code throw <expr>;} */
  record Throw(String expr) implements Statement {}

  /**
   * An expression statement: {@code <expr>;}. {@code expr} is raw expression text the renderer
   * emits verbatim.
   */
  record ExprStatement(String expr) implements Statement {}

  /**
   * A local declaration: {@code <type> <name> = <init>;} or, when {@code init} is null, {@code
   * <type> <name>;}. {@code type} is a pre-resolved short-form string (the mapping resolved it
   * through the collector), so the renderer emits it verbatim -- a generic type like {@code
   * List<T>} would be mangled by a second {@code ci.use}.
   */
  record LocalVar(String type, String name, String init) implements Statement {}

  /** A line comment: {@code // text}. */
  record Comment(String text) implements Statement {}

  /**
   * Wrap a body-line string as a {@link Fragment}.
   *
   * @param text the line content, rendered verbatim at the block's indent
   */
  static Fragment of(String text) {
    return new Fragment(text);
  }

  static Return ret(String expr) {
    return new Return(expr);
  }

  static Return ret() {
    return new Return(null);
  }

  static Throw throwOf(String expr) {
    return new Throw(expr);
  }

  static ExprStatement expr(String e) {
    return new ExprStatement(e);
  }

  static LocalVar local(String type, String name, String init) {
    return new LocalVar(type, name, init);
  }

  static Comment comment(String text) {
    return new Comment(text);
  }
}
