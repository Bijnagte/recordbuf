package bijnagte.recordbuf.codegen.source;

/**
 * An expression element: {@code <expr>;}. The expression is a {@link RawExpr} — today a raw string
 * (args empty, rendered verbatim), but can also carry {@link TypeRef} positional args for Phase B
 * tree-walk resolution.
 */
public record ExprStatement(RawExpr expr) implements Element {}
