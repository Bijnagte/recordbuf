package bijnagte.recordbuf.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>Resolution is phased.</b> {@link #use(String)} is called once per type reference while a
 * file is emitted; because a collision depends on the whole referral set (not any single
 * reference), the set is only known once the file is walked end to end. The caller therefore emits
 * the file into a throwaway collector to gather the set, calls {@link #finalizeResolution()}, and
 * emits again. After finalization {@link #use(String)} returns collision-aware names and {@link
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
   * Records a fully-qualified type reference that should ALWAYS be emitted in fully-qualified form
   * (never imported, even when the short name is unique).
   *
   * <p>This is for reference types in a different compilation unit where a short-name import would
   * create a shadowing conflict, e.g. the protobuf-java reference type of a {@code
   * recordbuf.protobuf_java}-opted message. Returns the FQN as-is.
   *
   * @deprecated internal bridge for {@link
   *     bijnagte.recordbuf.codegen.source.TypeRef#useFqn(TypeCollector)}; callers should use {@code
   *     TypeRef.of(fqn).useFqn(ci)} instead.
   */
  @Deprecated
  public String useFqn(String fqn) {
    int lastDot = fqn.lastIndexOf('.');
    if (lastDot < 0) return fqn; // prime number / bare name
    String shortName = fqn.substring(lastDot + 1);
    // Mark the short name as collided so no import is ever emitted for it.
    collidedShortNames.add(shortName);
    return fqn;
  }

  /**
   * {@link #useFqn(String)} overload for a {@link bijnagte.recordbuf.codegen.source.TypeRef}. The
   * base FQN is extracted via {@link bijnagte.recordbuf.codegen.source.TypeRef#fqn()} and marked as
   * collided (no import emitted for its short name).
   *
   * @deprecated internal bridge for {@code TypeRef.useFqn(ci)}; callers should use {@code
   *     TypeRef.of(fqn).useFqn(ci)} instead.
   */
  @Deprecated
  public void useFqn(bijnagte.recordbuf.codegen.source.TypeRef ref) {
    useFqn(ref.fqn());
  }

  /**
   * Records one type reference and returns the name to emit at that site, preserving any generic
   * suffix from {@code reference} (e.g. {@code java.util.List<java.lang.Integer>}). This is the
   * primary <b>tree-walker entry point</b> — {@code SourceRenderer.collect()} walks the AST and
   * calls {@code ci.use(type)} for each annotation, field, and parameter type it encounters. A bare
   * name carrying no package segment — a primitive, a {@code byte[]} array, or a same-package /
   * {@code java.lang} short name — is returned unchanged and never imported. Before {@code
   * finalizeResolution()} the returned name is always the short name; afterwards it is the
   * fully-qualified name only when that short name is shared by another distinct type.
   */
  public String use(String reference) {
    int lt = reference.indexOf('<');
    String genericSuffix = lt < 0 ? "" : reference.substring(lt);
    String fullyQualified = stripGenericArguments(reference);
    int lastDot = fullyQualified.lastIndexOf('.');
    if (lastDot < 0) {
      return reference; // primitive / array element / bare name -- no package segment
    }
    String owner = fullyQualified.substring(0, lastDot);
    String shortName = fullyQualified.substring(lastDot + 1);
    if (owner.equals("java.lang") || owner.equals(declaringPackage)) {
      // in-package or java.lang: short, never imported
      return shortName + genericSuffix;
    }
    importEligible.add(fullyQualified);
    LinkedHashSet<String> seen = byShortName.computeIfAbsent(shortName, k -> new LinkedHashSet<>());
    seen.add(fullyQualified);
    if (seen.size() > 1) {
      collidedShortNames.add(shortName);
    }
    return ((frozen && collidedShortNames.contains(shortName) ? fullyQualified : shortName)
        + genericSuffix);
  }

  /**
   * Marks the referral set complete; after this {@link #use(String)} returns collision-aware names.
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
  public String use(bijnagte.recordbuf.codegen.source.TypeRef ref) {
    return resolveTypeRef(ref);
  }

  private String resolveTypeRef(bijnagte.recordbuf.codegen.source.TypeRef ref) {
    return switch (ref) {
      case bijnagte.recordbuf.codegen.source.TypeRef.Named n -> resolveNamed(n);
      case bijnagte.recordbuf.codegen.source.TypeRef.Primitive p -> p.fqn();
      case bijnagte.recordbuf.codegen.source.TypeRef.Array a -> resolveTypeRef(a.element()) + "[]";
    };
  }

  private String resolveNamed(bijnagte.recordbuf.codegen.source.TypeRef.Named n) {
    // Compute the resolved base (short or fully-qualified), then append generics.
    String base;
    if (n.pkg().isEmpty()) {
      base = n.shortName(); // bare name: no package, emitted as-is
    } else {
      use(n.pkg() + "." + n.shortName()); // register for import / collision tracking
      base = n.shortName();
      if (frozen && collidedShortNames.contains(base)) {
        base = n.fqn(); // collided: emit the full qualified form
      }
    }
    return base + genericsOf(n);
  }

  private String genericsOf(bijnagte.recordbuf.codegen.source.TypeRef.Named n) {
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

  private static String stripGenericArguments(String reference) {
    int lt = reference.indexOf('<');
    return lt < 0 ? reference : reference.substring(0, lt);
  }

  /**
   * The post-finalization {@code ResolutionContext} — used by mappers to build {@link
   * bijnagte.recordbuf.codegen.source.ResolveableExpr}.
   */
  public bijnagte.recordbuf.codegen.source.ResolutionContext resolutionContext() {
    return new bijnagte.recordbuf.codegen.source.ResolutionContext(
        declaringPackage, java.util.Set.copyOf(collidedShortNames));
  }

  /**
   * Registers the import for a {@link bijnagte.recordbuf.codegen.source.TypeRef} (no return — the
   * import is a side-effect). This is an internal bridge for {@link
   * bijnagte.recordbuf.codegen.source.Formattable#collect(TypeCollector)} which is the public API
   * for collect-phase registration.
   *
   * @deprecated use {@code TypeRef.of(fqn).collect(ci)} or {@code Formattable.collect(ci)} instead.
   */
  @Deprecated
  public void register(bijnagte.recordbuf.codegen.source.TypeRef ref) {
    resolveTypeRef(ref);
  }
}
