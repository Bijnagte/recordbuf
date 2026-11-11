package bijnagte.recordbuf.codegen.source;

/**
 * A local declaration: {@code <type> <name> = <init>;} or, when {@code init} is null, {@code <type>
 * <name>;}. {@code type} is a pre-resolved short-form string (the mapping resolved it through the
 * collector), so the renderer emits it verbatim -- a generic type like {@code List<T>} would be
 * mangled by a second {@code ci.use}.
 */
public record LocalVar(String type, String name, String init) implements Element {}
