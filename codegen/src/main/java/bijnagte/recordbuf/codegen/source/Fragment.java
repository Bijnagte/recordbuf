package bijnagte.recordbuf.codegen.source;

/**
 * A raw body-line fragment whose text the renderer emits verbatim at the block's indent -- the
 * documented escape hatch for syntax the closed idiom set has not yet modeled. Its text already
 * carries any local indent the old flat-line emitter hand-bookkept, so wrapping a legacy body line
 * for it is byte-identical.
 */
public record Fragment(String text) implements Element {}
