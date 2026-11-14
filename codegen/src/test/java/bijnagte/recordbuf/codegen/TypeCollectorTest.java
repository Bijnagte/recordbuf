package bijnagte.recordbuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link TypeCollector} contract -- a port of the plugin's original import-behavior test
 * (our own MIT asset), the authoritative behavioral spec this clean-room implementation reproduces:
 * same-package types resolve to their short name with no import, cross-package types are imported
 * under their short form, a short-name collision falls back to a fully-qualified name at every
 * reference site, {@code java.lang.*} is never imported, and {@link
 * TypeCollector#finalizeResolution} switches {@link TypeCollector#use} to collision-aware names
 * while restoring un-collided references to their short form.
 *
 * <p>Every case is checked against {@link TypeCollector#importLines() --} the rendered surface --
 * not any internal accessor.
 */
class TypeCollectorTest {

  private static final String PKG = "bijnagte.recordbuf.fixture.iface";

  @Test
  void samePackageTypeIsShortWithoutImport() {
    TypeCollector ci = new TypeCollector(PKG);
    String resolved = ci.use(TypeRef.of("bijnagte.recordbuf.fixture.iface.Sub"));
    assertEquals("Sub", resolved);
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void crossPackageTypeBecomesShortAndImported() {
    TypeCollector ci = new TypeCollector(PKG);
    ci.use(TypeRef.of("com.example.other.Marker"));
    assertEquals("Marker", ci.use(TypeRef.of("com.example.other.Marker")));
    assertEquals(List.of("import com.example.other.Marker;"), ci.importLines());
  }

  @Test
  void javaLangTypesAreShortAndNeverImported() {
    TypeCollector ci = new TypeCollector(PKG);
    assertEquals("String", ci.use(TypeRef.of("java.lang.String")));
    assertEquals("Integer", ci.use(TypeRef.of("java.lang.Integer")));
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void collisionFallsBackToFqnAndIsNotImported() {
    TypeCollector ci = new TypeCollector(PKG);
    ci.use(TypeRef.of("com.example.Marker"));
    ci.use(TypeRef.of("com.other.Marker"));
    ci.finalizeResolution();
    // Both distinct FQNs share the short name, so neither is imported; both are emitted FQN.
    assertEquals("com.example.Marker", ci.use(TypeRef.of("com.example.Marker")));
    assertEquals("com.other.Marker", ci.use(TypeRef.of("com.other.Marker")));
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void inPackageCollisionFqnsOutOfPackageOnly() {
    TypeCollector ci = new TypeCollector(PKG);
    // An in-package type and an out-of-package type share the short name "Sub": the in-package
    // type occupies the short name, so only the out-of-package type is emitted fully-qualified.
    ci.use(TypeRef.of(PKG + ".Sub"));
    ci.use(TypeRef.of("com.example.other.Sub"));
    ci.finalizeResolution();
    assertEquals("Sub", ci.use(TypeRef.of(PKG + ".Sub")));
    assertEquals("com.example.other.Sub", ci.use(TypeRef.of("com.example.other.Sub")));
    // No import for the in-package type; the out-of-package type is FQN'd, so neither is imported.
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void importLinesAreGroupedJavaFirstThenOther() {
    TypeCollector ci = new TypeCollector(PKG);
    ci.use(TypeRef.of("bijnagte.recordbuf.runtime.Encoder"));
    ci.use(TypeRef.of("bijnagte.recordbuf.runtime.CodecDefaults"));
    ci.use(TypeRef.of("org.jspecify.annotations.Nullable"));
    ci.use(TypeRef.of("java.util.List"));
    ci.finalizeResolution();
    List<String> lines = ci.importLines();

    // java.* (java.util.List) first, a blank separator, then the other packages alphabetical.
    assertEquals(
        List.of(
            "import java.util.List;",
            "",
            "import bijnagte.recordbuf.runtime.CodecDefaults;",
            "import bijnagte.recordbuf.runtime.Encoder;",
            "import org.jspecify.annotations.Nullable;"),
        lines);
  }

  @Test
  void finalizeResolutionRestoresUniqueShortNames() {
    TypeCollector ci = new TypeCollector(PKG);
    ci.use(TypeRef.of("com.example.a.Sub"));
    ci.use(TypeRef.of("com.example.b.Marker"));
    ci.finalizeResolution();
    assertEquals("Sub", ci.use(TypeRef.of("com.example.a.Sub")));
    assertEquals("Marker", ci.use(TypeRef.of("com.example.b.Marker")));
    assertTrue(ci.importLines().contains("import com.example.a.Sub;"));
    assertTrue(ci.importLines().contains("import com.example.b.Marker;"));
  }

  @Test
  void useIsIdempotentAfterFinalization() {
    TypeCollector ci = new TypeCollector(PKG);
    ci.use(TypeRef.of("com.example.Marker"));
    ci.use(TypeRef.of("com.example.Marker"));
    ci.finalizeResolution();
    assertEquals("Marker", ci.use(TypeRef.of("com.example.Marker")));
    assertEquals(List.of("import com.example.Marker;"), ci.importLines());
    assertFalse(ci.importLines().isEmpty());
  }

  @Test
  void genericSuffixIsPreservedForCrossPackageType() {
    TypeCollector ci = new TypeCollector(PKG);
    String resolved = ci.use(TypeRef.of("java.util.List<java.lang.Integer>"));
    // The base type java.util.List is imported; the generic arg is resolved through the
    // structured TypeRef path, so java.lang.Integer collapses to its short form Integer.
    // (The legacy string-based use preserved the generic suffix verbatim; the structured API
    // resolves each arg, which is the desired behavior.)
    assertEquals("List<Integer>", resolved);
    assertTrue(ci.importLines().contains("import java.util.List;"));
  }

  @Test
  void genericSuffixIsPreservedForInPackageType() {
    TypeCollector ci = new TypeCollector(PKG);
    String resolved = ci.use(TypeRef.of("bijnagte.recordbuf.fixture.iface.Sub<java.lang.String>"));
    // Sub is in the same package; the generic arg java.lang.String resolves to its
    // short form String via the structured TypeRef path.
    assertEquals("Sub<String>", resolved);
    assertTrue(ci.importLines().isEmpty());
  }
}
