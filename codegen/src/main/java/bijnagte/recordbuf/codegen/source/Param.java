package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A method/constructor parameter: {@code annotationsType name}. The {@link #type()} is a {@link
 * TypeRef} resolved at render time; the {@link #annotations()} list is the uniform first-class
 * annotation list -- a parameter that receives a presence-tracked value carries {@link
 * Annotation#NULLABLE}.
 */
public record Param(List<Annotation> annotations, TypeRef type, String name) {
  public Param {
    annotations = annotations == null ? List.of() : annotations;
  }

  /** A parameter with no annotations. */
  public static Param of(TypeRef type, String name) {
    return new Param(null, type, name);
  }

  /** A parameter carrying the given annotations. */
  public static Param of(TypeRef type, String name, Annotation... annotations) {
    return new Param(annotations == null ? null : List.of(annotations), type, name);
  }
}
