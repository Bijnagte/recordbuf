package bijnagte.recordbuf.codegen.source;

/**
 * A member of a type body. The pure-Java AST is structural here: a member is a declaration (a
 * component {@link Component}, an {@link EnumConstant}, a nested {@link Type}, a {@link Method}, a
 * {@link Constructor}, a {@link StaticBlock}) the enclosing {@link Type} owns. The proto logic
 * assembles these from its model; the module never sees a proto.
 *
 * <p>A {@link Method method} or {@link Constructor} carries a body that is a {@code List} of {@link
 * Element}s -- control-flow and element idioms ({@code If}/{@code For}/.../ {@code Return}/{@code
 * Throw}) plus the {@link Fragment} escape -- which the {@link SourceRenderer} lays out; it is not
 * a flat list of hand-indented strings.
 *
 * <p>Each variant is built via a hand-written, ergonomic nested {@code Builder}: named modifier
 * toggles ({@code public_()}/{@code static_()}/{@code final_()}/...), collection accumulators
 * ({@code thrown()}/{@code body()} ...), and the raw component setters. Compact constructors still
 * seed optional fields to their null-safe defaults, so {@code new Method(...)} callers work
 * unchanged.
 */
public sealed interface Member
    permits Component, EnumConstant, Method, Constructor, StaticBlock, Nested {}
