package bijnagte.recordbuf.codegen.source;

/**
 * An expression element: {@code <expr>;}. {@code expr} is raw expression text the renderer emits
 * verbatim.
 */
public record ExprStatement(String expr) implements Element {}
