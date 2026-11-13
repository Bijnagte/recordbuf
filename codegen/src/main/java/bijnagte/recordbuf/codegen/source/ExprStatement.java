package bijnagte.recordbuf.codegen.source;

/**
 * An expression element: {@code <expr>;}. The expression is a {@link Formattable} — today a {@link
 * RawExpr} (with {@code @N} positional args and {@link TypeRef}s resolved at render time), but may
 * be any {@link Formattable} in the future.
 */
public record ExprStatement(Formattable expr) implements Element {}
