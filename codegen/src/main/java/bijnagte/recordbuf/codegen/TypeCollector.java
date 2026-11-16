package bijnagte.recordbuf.codegen;

import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.codegen.source.TypeRef.Array;
import bijnagte.recordbuf.codegen.source.TypeRef.Named;
import bijnagte.recordbuf.codegen.source.TypeRef.Primitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves, for a single generated source file, the Java types it references -- and renders that
 * file's deduplicated, grouped import block.
 *
 * <p>This is a clean-room, original implementation of the import-resolution contract the plugin's
 * hand-rolled writer used to provide; it is neither a dependency on, nor a copy of, {@code
 * palantir/javapoet}. The authoritative behavior it reproduces is the plugin's own (MIT-licensed)
 * collector, pinned by this module's {@code TypeCollectorTest}. See the plan's IP / clean-room
 * section in {@code docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md}.
 *
 * <p><b>Never imported</b> -- resolved to its bare short name and contributing no import line --
 * are {@code java.lang.*} types and types in the file's own package. Every other referenced type is
 * imported.
 *
 * <p><b>Collision:</b> a short name shared by two or more distinct fully-qualified names is emitted
 * in fully-qualified form at every reference site and contributes no import, so the consumer's
 * compiler disambiguates by the distinct fully-qualified names themselves.
 *
 * <p><b>Resolution is phased.</b> {@link #use(TypeRef)} is called once per type reference while a
 * file is emitted; because a collision depends on the whole referral set (not any single
 * reference), the set is only known once the file is walked end to end. The caller therefore emits
 * the file into a throwaway collector to gather the set, calls {@link #finalizeResolution()}, and
 * emits again. After finalization {@link #use(TypeRef)} returns collision-aware names and {@link
 * #importLines()} is final -- un-collided references are restored to their short form and the
 * behavior is idempotent.
 */
public final class TypeCollector {

  /** Fully-qualified names eligible for an import line, in first-seen order (drives grouping). */
  private final LinkedHashSet<String> importEligible = new LinkedHashSet<>();

  /**
   * Short name to the set of distinct fully-qualified names seen under it (collision detection).
   */
  private final Map<String, LinkedHashSet<String>> byShortName = new LinkedHashMap<>();

  /** Short names shared by two or more distinct fully-qualified names: never imported. */
  private final LinkedHashSet<String> collidedShortNames = new LinkedHashSet<>();

  private final String declaringPackage;

  /** The declaring package. */
  public String declaringPackage() {
    return declaringPackage;
  }

  private boolean frozen = false;

  /**
   * @param declaringPackage the package the collected references are being resolved within ({@code
   *     null} is treated as the empty package)
   */
  public TypeCollector(String declaringPackage) {
    this.declaringPackage = declaringPackage == null ? "" : declaringPackage;
  }

  /**
   * Marks the referral set complete; after this {@link #use(TypeRef)} returns collision-aware
   * names.
   */
  public void finalizeResolution() {
    this.frozen = true;
  }

  /**
   * Resolve a structured {@link TypeRef} to the form to emit at that position. During the collect
   * pass this registers the base FQN (for import-line purposes) and returns the short name; after
   * {@link #finalizeResolution()} it returns the collision-aware form (short or fully-qualified).
   * Generic arguments are rendered recursively. A {@code java.lang} or same-package type renders as
   * a bare short name; every other type is collected for an import line.
   */
  public String use(TypeRef ref) {
    return resolveTypeRef(ref);
  }

  /**
   * Resolves every {@link bijnagte.recordbuf.codegen.source.TypeRef} in the batch, registering
   * imports and updating collision state — the collect-phase entry point the renderer calls once
   * with the set aggregated from the tree's {@code referencedTypes()} results. Order within the
   * batch is irrelevant: {@link #importLines()} sorts, and collision is set-based.
   */
  public void accumulate(Set<TypeRef> refs) {
    for (TypeRef ref : refs) {
      resolveTypeRef(ref);
    }
  }

  private String resolveTypeRef(TypeRef ref) {
    return switch (ref) {
      case Named n -> resolveNamed(n);
      case Primitive p -> p.fqn();
      case Array a -> resolveTypeRef(a.element()) + "[]";
    };
  }

  private String resolveNamed(Named n) {
    // Compute the resolved base (short or fully-qualified), then append generics.
    String base;
    if (n.pkg().isEmpty()) {
      base = n.renderedShort(); // no package: emitted in its nesting form, no import
    } else {
      registerType(n); // register for import / collision tracking
      base = n.renderedShort();
      // A collided short name is emitted fully-qualified for the out-of-package type; an in-package
      // type occupies the short name in the declaring package and stays short.
      if (frozen
          && collidedShortNames.contains(n.collisionKey())
          && !n.pkg().equals(declaringPackage)) {
        base = n.fqn(); // collided out-of-package: emit the full qualified form
      }
    }
    return base + genericsOf(n);
  }

  /**
   * Registers a type as import-eligible and tracks collision by its {@link Named#collisionKey()}
   * (the outermost short name in the nesting chain). No return value — registration is a
   * side-effect consumed by {@link #importLines()} and by the frozen-state branch of {@link
   * #resolveNamed(TypeRef.Named)}. For a nested type the import target is the enclosing type's FQN
   * (Java imports {@code Outer} and references {@code Outer.Inner}); {@code java.lang.*} and
   * same-package types are deliberately excluded, so they contribute no import line.
   */
  private void registerType(Named n) {
    String importFqn = n.importFqn();
    int lastDot = importFqn.lastIndexOf('.');
    if (lastDot < 0) return; // bare name -- skip
    String owner = importFqn.substring(0, lastDot);
    if (owner.equals("java.lang")) {
      return; // java.lang: short, never imported, and never a collision source
    }
    String collisionKey = n.collisionKey();
    // Track every non-java.lang collision key for collision detection, including in-package types:
    // an
    // in-package type's outer short name can collide with an out-of-package type that shares it
    // (e.g.
    // the protobuf-java reference type of a recordbuf message), and that collision must be detected
    // so
    // the out-of-package type is emitted fully-qualified.
    LinkedHashSet<String> seen =
        byShortName.computeIfAbsent(collisionKey, k -> new LinkedHashSet<>());
    seen.add(importFqn);
    if (seen.size() > 1) {
      collidedShortNames.add(collisionKey);
    }
    // Only out-of-package types contribute an import line; in-package types stay short.
    if (!owner.equals(declaringPackage)) {
      importEligible.add(importFqn);
    }
  }

  private String genericsOf(Named n) {
    if (n.generics().isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    sb.append('<');
    for (int i = 0; i < n.generics().size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(resolveTypeRef(n.generics().get(i)));
    }
    sb.append('>');
    return sb.toString();
  }

  /**
   * The file's import elements, each {@code import x.y.Z;}, deduplicated and grouped: the {@code
   * java.*} package first, a single blank separator line, then every other package -- alphabetical
   * within each group. Collided names are omitted (emitted in fully-qualified form instead). Empty
   * when nothing is imported.
   */
  public List<String> importLines() {
    TreeSet<String> java = new TreeSet<>();
    TreeSet<String> other = new TreeSet<>();
    for (String fullyQualified : importEligible) {
      if (collidedShortNames.contains(shortNameOf(fullyQualified))) {
        continue; // collided: emitted fully-qualified, so no import
      }
      if (fullyQualified.startsWith("java.")) {
        java.add(fullyQualified);
      } else {
        other.add(fullyQualified);
      }
    }
    List<String> lines = new ArrayList<>();
    for (String fullyQualified : java) {
      lines.add("import " + fullyQualified + ";");
    }
    if (!java.isEmpty() && !other.isEmpty()) {
      lines.add("");
    }
    for (String fullyQualified : other) {
      lines.add("import " + fullyQualified + ";");
    }
    return lines;
  }

  private static String shortNameOf(String fullyQualifiedName) {
    int lastDot = fullyQualifiedName.lastIndexOf('.');
    return lastDot < 0 ? fullyQualifiedName : fullyQualifiedName.substring(lastDot + 1);
  }

  /**
   * The post-finalization {@code ResolutionContext} — used by mappers to build {@link
   * bijnagte.recordbuf.codegen.source.ResolveableExpr}.
   */
  public ResolutionContext resolutionContext() {
    return new ResolutionContext(declaringPackage, Set.copyOf(collidedShortNames));
  }
}
