package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.TypeCollector;

/**
 * A generated Java source file, in its pure form: a package, a header comment, a {@code
 * TypeCollector} that resolves every referenced type to a short name plus an import block, and a
 * single top-level {@link Type} (which may own nested {@link Type}s as {@link Member#Nested}
 * members).
 *
 * <p>The two-phase import resolution (collect, {@link TypeCollector#finalizeResolution() finalize},
 * render again) that this model inherits is the same contract the hand-written writer used; the
 * only behavioural change is that the file is now a tree of AST nodes instead of a {@code
 * StringBuilder}.
 */
public record JavaFile(String packageName, Javadoc header, Type top, TypeCollector ci) {

  public String toSource() {
    return SourceRenderer.render(this);
  }
}
