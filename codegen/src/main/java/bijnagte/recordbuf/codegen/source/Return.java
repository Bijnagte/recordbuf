package bijnagte.recordbuf.codegen.source;

/** {@code return <expr>;} or, when {@code expr} is null, a bare {@code return;}. */
public record Return(RawExpr expr) implements Element {}
