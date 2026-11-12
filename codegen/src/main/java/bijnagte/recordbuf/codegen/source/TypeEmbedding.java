package bijnagte.recordbuf.codegen.source;

/**
 * A structured expression piece that resolves a {@link TypeRef} at render time against a {@link
 * ResolutionContext}. This is the bridge toward fully structured expression bodies: a type
 * reference held in the AST that renders lazily when the render phase walks the tree.
 *
 * @param ref the type reference to resolve and emit
 */
public record TypeEmbedding(TypeRef ref) implements ExprPiece {

  @Override
  public String render(ResolutionContext ctx) {
    return ref.resolved(ctx.declaringPackage(), ctx.collidedShortNames());
  }
}
