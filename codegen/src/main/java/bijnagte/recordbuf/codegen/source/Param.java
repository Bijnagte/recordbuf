package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A method/constructor parameter: {@code annotationsType name} (the type and name are emitted,
 * joined by a space; annotations render in {@link Annotation} form ahead of the type when present).
 *
 * <p>The {@link #annotations()} list is the uniform first-class annotation list -- a parameter that
 * receives a presence-tracked value carries {@link Annotation#NULLABLE}. The {@code Param.of(type,
 * name)} factory leaves it empty; the varargs overload seeds it from the supplied annotations.
 */
public record Param(List<Annotation> annotations, String type, String name) {
  public Param {
    annotations = annotations == null ? List.of() : annotations;
  }

  /** A parameter with no annotations. */
  public static Param of(String type, String name) {
    return new Param(null, type, name);
  }

  /** A parameter carrying the given annotations. */
  public static Param of(String type, String name, Annotation... annotations) {
    return new Param(annotations == null ? null : List.of(annotations), type, name);
  }
}
