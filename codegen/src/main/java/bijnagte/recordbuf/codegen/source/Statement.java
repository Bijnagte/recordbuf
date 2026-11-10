package bijnagte.recordbuf.codegen.source;

/**
 * A single statement inside a method, constructor, or {@code static {}} body. A body is a {@link
 * List List<Statement>}; the {@link SourceRenderer} owns brace, paren, and join layout for the
 * list, not the emitter.
 *
 * <p>This is the closed idiom set the body-lift is building toward (DEC-3): control flow ({@code
 * If/For/Foreach/While/Switch+CaseClause}), statements ({@code
 * Return/Throw/LocalVar/ExprStatement}), and a bare {@link Block} for nested scopes -- plus the
 * {@link Fragment} escape node used while the per-mapping idiom conversion (the body-lift's final
 * unit) works line by line. Fragments may coexist with modeled idioms but are counted, not
 * celebrated: a growing fragment count signals an idiom the closed set has not yet learned.
 *
 * <p>Today the only emitted node is {@link Fragment}; the idiom nodes land alongside it as the
 * mappings convert, each byte-identical to the line it replaces.
 */
public sealed interface Statement permits Statement.Fragment {

  /**
   * A raw body-line fragment whose text the renderer emits verbatim at the block's indent -- the
   * documented escape hatch for syntax the closed idiom set has not yet modeled. Its text already
   * carries any local indent the old flat-line emitter hand-bookkept, so wrapping a legacy body
   * line for it is byte-identical.
   */
  record Fragment(String text) implements Statement {}

  /**
   * Wrap a body-line string as a {@link Fragment}.
   *
   * @param text the line content, rendered verbatim at the block's indent
   */
  static Fragment of(String text) {
    return new Fragment(text);
  }
}
