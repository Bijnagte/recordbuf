package bijnagte.recordbuf.codegen;

import java.util.Set;

/**
 * A lightweight render-time context that resolves type references against a declared package and a
 * set of collided short names. Passed through the render phase so that expression pieces can
 * resolve types without holding a mutable {@link TypeCollector}.
 *
 * @param declaringPackage the package of the file being generated ({@code null} for the default
 *     package)
 * @param collidedShortNames the set of short names shared by two or more distinct FQNs in this file
 */
public record ResolutionContext(String declaringPackage, Set<String> collidedShortNames) {}
