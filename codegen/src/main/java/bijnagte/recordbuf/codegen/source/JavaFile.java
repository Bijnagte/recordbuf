package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.Set;

/**
 * A generated Java source file, in its pure form: a package, a header comment, a {@code
 * TypeCollector} that resolves every referenced type to a short name plus an import block, and a
 * single top-level {@link Type} (which may own nested {@link Type}s as nested members).
 *
 * <p>The two-phase import resolution (collect, {@link TypeCollector#finalizeResolution() finalize},
 * render again) that this model inherits is the same contract the hand-written writer used; the
 * only behavioural change is that the file is now a tree of AST nodes instead of a {@code
 * StringBuilder}.
 *
 * <p>The {@code externalRefs} component is a <em>side channel</em> for type refs that are not
 * reachable from any bare-short-name AST position: the record's own FQN (a collision seed that
 * makes its short name occupy the declaring package) and, for an Avro converter, the Avro specific
 * record's FQN (an import when the converter and the specific record are in different packages).
 * The renderer aggregates this set into the collect pass alongside the AST walk; it is empty by
 * default.
 */
public record JavaFile(
    String packageName, Javadoc header, Type top, TypeCollector ci, Set<TypeRef> externalRefs) {

  /** Backwards-compatible constructor: external refs default to empty. */
  public JavaFile(String packageName, Javadoc header, Type top, TypeCollector ci) {
    this(packageName, header, top, ci, Set.of());
  }

  /** A {@code null} external-ref set is treated as empty. */
  public JavaFile {
    externalRefs = externalRefs == null ? Set.of() : externalRefs;
  }

  public String toSource() {
    return SourceRenderer.render(this);
  }
}
