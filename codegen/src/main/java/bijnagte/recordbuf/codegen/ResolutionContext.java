package bijnagte.recordbuf.codegen;

import java.util.List;
import java.util.Set;

/**
 * A lightweight render-time context that resolves type references against a declared package, a set
 * of collided short names, and the current lexical class-scope chain. Passed through the render
 * phase so that expression pieces can resolve types without holding a mutable {@link
 * TypeCollector}.
 *
 * @param declaringPackage the package of the file being generated ({@code null} for the default
 *     package)
 * @param collidedShortNames the set of short names shared by two or more distinct FQNs in this file
 * @param scope the lexical scope chain of enclosing type names, outermost first — the classes the
 *     reference is rendered inside. A qualified {@link
 *     bijnagte.recordbuf.codegen.source.TypeRef.Named} reference whose outermost enclosing type is
 *     one of these renders without that leading segment (a member type of an enclosing class is in
 *     simple-name scope inside it, so the qualifier is redundant)
 */
public record ResolutionContext(
    String declaringPackage, Set<String> collidedShortNames, List<String> scope) {

  /** Context for use sites at the top level of a file (no enclosing class scope). */
  public ResolutionContext(String declaringPackage, Set<String> collidedShortNames) {
    this(declaringPackage, collidedShortNames, List.of());
  }
}
