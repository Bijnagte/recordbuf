package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.TypeCollector;

/**
 * A renderable expression piece in a generated source file, supporting both the render phase and
 * the import-collection phase via the same tree.
 *
 * <p>Two methods:
 *
 * <ul>
 *   <li>{@link #render(ResolutionContext)} — produces the source text for this piece, resolving any
 *       type references against {@code ctx}.
 *   <li>{@link #collect(TypeCollector)} — contributes type references to an import collector.
 * </ul>
 *
 * <p>Implementations: {@link TypeRef} (resolves a type name), {@link RawExpr} (substitutes
 * positional {@code @N} args in a template string).
 */
public interface Formattable {

  /**
   * Render this piece to source text, resolving any type references against {@code ctx}.
   *
   * @param ctx the render-time resolution context
   * @return the rendered source text
   */
  String render(ResolutionContext ctx);

  /**
   * Contribute any type references to the import collector.
   *
   * @param collector the collector to register type references with
   */
  void collect(TypeCollector collector);
}
