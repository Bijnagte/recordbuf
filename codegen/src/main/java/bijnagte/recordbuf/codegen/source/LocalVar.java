package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A local declaration: {@code <type> <name> = <init>;} or, when {@code init} is null, {@code <type>
 * <name>;}. {@code type} is a pre-resolved short-form string (the mapping resolved it through the
 * collector), so the renderer emits it verbatim -- a generic type like {@code List<T>} would be
 * mangled by a second {@code ci.use}.
 */
public record LocalVar(List<Annotation> annotations, String type, String name, String init)
    implements Element {
  // `annotations` may be null at the canonical boundary; the compact ctor seeds it to an empty list
  // so a local declaration with no annotations renders as before. `type`/`name`/`init` are
  // unchanged
  // pre-resolved short-form strings, emitted verbatim by the renderer.
  public LocalVar {
    annotations = annotations == null ? List.of() : annotations;
  }
}
