package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A local declaration: {@code <type> <name> = <init>;} or, when {@code init} is null, {@code <type>
 * <name>;}. {@code type} is a {@link TypeRef} resolved through the collector at render time.
 */
public record LocalVar(List<Annotation> annotations, TypeRef type, String name, String init)
    implements Element {
  /** Seed the annotation list when absent; {@code type}/{@code name}/{@code init} unchanged. */
  public LocalVar {
    annotations = annotations == null ? List.of() : annotations;
  }
}
