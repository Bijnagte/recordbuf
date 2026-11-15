package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.ResolutionContext;
import java.util.Set;

/**
 * A node in the source-generation AST that can both contribute its type references to a {@link
 * bijnagte.recordbuf.codegen.TypeCollector} and render itself to a string under a {@link
 * ResolutionContext}. Implemented by the AST types that the renderer walks (e.g. {@link TypeRef},
 * {@link RawExpr}).
 *
 * <p>The {@link #referencedTypes()} / {@link #render(ResolutionContext)} pair is the two-pass
 * contract: {@code referencedTypes()} is called in the <em>collect</em> pass to gather every import
 * reference (returning the {@link TypeRef}s this node contributes, unioning its children), and
 * {@code render} is called in the <em>render</em> pass to produce the source text. This pull shape
 * (returning refs) keeps the {@link bijnagte.recordbuf.codegen.TypeCollector} <em>out</em> of the
 * AST: a node reports what it references rather than mutating a collector that is threaded through
 * the whole tree — the renderer aggregates the returned sets and resolves them.
 */
public interface Formattable {

  /**
   * The type references this node contributes to the import set, unioning any children. A node with
   * no import reference (e.g. a {@link TypeRef.Primitive}) returns an empty set. The renderer
   * aggregates these sets across the tree and resolves them in a single pass.
   *
   * @return the {@link TypeRef}s this node references (never {@code null}; empty if none)
   */
  Set<TypeRef> referencedTypes();

  /**
   * Renders this node to a single-line Java expression string under the given resolution context.
   *
   * @param ctx the resolution context (provides collision-aware short-name vs. FQN resolution); may
   *     be {@code null} when no resolution is required (e.g. a bare string {@link RawExpr})
   * @return the rendered source expression, never {@code null}
   */
  String render(ResolutionContext ctx);
}
