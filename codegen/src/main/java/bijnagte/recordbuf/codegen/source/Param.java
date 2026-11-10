package bijnagte.recordbuf.codegen.source;

/**
 * One method or constructor parameter: a {@code type} and a {@code name}. An empty parameter list
 * (zero params) renders as {@code ()} -- the renderer owns the parens, so a caller never writes its
 * own {@code ( )}. The {@code type} is a pre-resolved short-form string (a type like {@code
 * byte[]}, {@code List<T>}, or a short class name); the renderer emits it verbatim.
 */
public record Param(String type, String name) {

  /** Build a parameter from its type and name. */
  public static Param of(String type, String name) {
    return new Param(type, name);
  }
}
