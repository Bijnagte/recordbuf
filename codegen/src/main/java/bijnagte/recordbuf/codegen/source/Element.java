package bijnagte.recordbuf.codegen.source;

/**
 * A single element inside a method, constructor, or {@code static {}} body. A body is a {@link
 * java.util.List List<Element>}; the {@link SourceRenderer} owns brace, paren, and join layout for
 * the list, not the emitter.
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
    return new LocalVar(null, type, name, init);
  }

  /** A local declaration carrying annotations, rendered ahead of its type. */
  static LocalVar local(String type, String name, String init, Annotation... annotations) {
    return new LocalVar(
        annotations == null ? null : java.util.List.of(annotations), type, name, init);
  }

  static Comment comment(String text) {
    return new Comment(text);
  }
}
