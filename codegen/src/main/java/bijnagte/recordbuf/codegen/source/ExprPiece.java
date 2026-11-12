package bijnagte.recordbuf.codegen.source;

/**
 * A renderable expression piece: a fragment of Java code that can be embedded in an expression
 * body. The sealed hierarchy is the bridge between today's raw-string expression bodies (R7 escape
 * hatch) and future fully-structured expression trees (R7 full form, deferred to Phase C).
 *
 * <p>Two variants:
 *
 * <ul>
 *   <li>{@link RawExpr} — an escape-hatch raw string, substituted positionally against its argument
 *       list via {@code %d} printf placeholders. A raw string with zero arguments renders verbatim,
 *       identical to today's {@code String}-based {@code ExprStatement}.
 *   <li>{@link TypeEmbedding} — a structured piece that resolves a {@link TypeRef} at render time
 *       against a {@link ResolutionContext}.
 * </ul>
 *
 * <p>Each piece produces its text via {@link #render(ResolutionContext)}, which is called by {@link
 * SourceRenderer} during the render phase.
 */
public sealed interface ExprPiece permits RawExpr, TypeEmbedding {

  /**
   * Render this piece to its string form, resolving any enclosed {@link TypeRef} references against
   * the given context.
   *
   * @param ctx the render-time resolution context
   * @return the text to emit
   */
  String render(ResolutionContext ctx);
}
