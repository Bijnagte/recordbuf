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

  /** A parameter with no annotations. Takes a bare FQN string, parsed to a {@link TypeRef}. */
  public static Param of(String fqn, String name) {
    return new Param(null, TypeRef.of(fqn), name);
  }

  /**
   * A parameter carrying the given annotations. Takes a bare FQN string, parsed to a {@link
   * TypeRef}.
   */
  public static Param of(String fqn, String name, Annotation... annotations) {
    return new Param(annotations == null ? null : List.of(annotations), TypeRef.of(fqn), name);
  }

  /** Build from a pre-structured {@link TypeRef}. */
  public static Param param(TypeRef type, String name, Annotation... annotations) {
    return new Param(annotations == null ? null : List.of(annotations), type, name);
  }
}
