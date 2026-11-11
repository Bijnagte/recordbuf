package bijnagte.recordbuf.codegen.source;

/** A nested type -- another {@link Type} owned by this type's body (a "fragment"). */
public record Nested(Type type) implements Member {}
