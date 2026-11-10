package bijnagte.recordbuf.codegen.source;

/**
 * A single source line that the proto logic emits into a body -- a method/constructor body, or a
 * {@code static { ... }} initializer. The renderer indents each line to the enclosing block's
 * indent and joins with newlines; a body is just a {@code List<Line>}. This is what keeps method
 * bodies "line-emitting" even though the surrounding structure is an AST.
 */
public record Line(String text) {

  public static Line of(String text) {
    return new Line(text);
  }
}
