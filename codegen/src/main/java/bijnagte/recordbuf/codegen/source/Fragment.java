package bijnagte.recordbuf.codegen.source;

/**
 * A raw body-line fragment whose text the renderer emits verbatim at the block's indent -- the
 * documented escape hatch for syntax the closed idiom set has not yet modeled. Its text already
 * carries any local indent the old flat-line emitter hand-bookkept, so wrapping a legacy body line
 * for it is byte-identical.
 */
public record Fragment(String text) implements Element {

  /**
   * Build a {@code Fragment} from a {@code @N}-templated string. The template is substituted at
   * construction time with the positional args. Use only when all positional args are plain {@link
   * String}s (no {@link TypeRef} or nested {@link Formattable} that would need a resolution
   * context).
   */
  public static Fragment of(String template, Object... args) {
    return new Fragment(RawExpr.of(template, args).render(null));
  }
}
