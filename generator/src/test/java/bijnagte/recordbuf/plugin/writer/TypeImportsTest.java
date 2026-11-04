package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-file {@link TypeImports} collector: same-package types resolve to their short
 * name with no import, cross-package types are imported under their short form, a short name
 * collision falls back to a fully-qualified name at every reference site, {@code java.lang.*} types
 * never import, and {@link TypeImports#finalizeResolution()} switches {@code use} to
 * collision-aware names, fixing un-collided references back to their short form.
 *
 * <p>{@code importLines()} is what actually renders, so every case is checked against that real
 * surface (not an internal {@code imports()} accessor this class does not expose).
 */
class TypeImportsTest {

  private static final String PKG = "bijnagte.recordbuf.fixture.iface";

  @Test
  void samePackageTypeIsShortWithoutImport() {
    TypeImports ci = new TypeImports(PKG);
    String resolved = ci.use("bijnagte.recordbuf.fixture.iface.Sub");
    assertEquals("Sub", resolved);
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void crossPackageTypeBecomesShortAndImported() {
    TypeImports ci = new TypeImports(PKG);
    ci.use("com.example.other.Marker");
    assertEquals("Marker", ci.use("com.example.other.Marker"));
    assertEquals(List.of("import com.example.other.Marker;"), ci.importLines());
  }

  @Test
  void javaLangTypesAreShortAndNeverImported() {
    TypeImports ci = new TypeImports(PKG);
    assertEquals("String", ci.use("java.lang.String"));
    assertEquals("Integer", ci.use("java.lang.Integer"));
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void collisionFallsBackToFqnAndIsNotImported() {
    TypeImports ci = new TypeImports(PKG);
    ci.use("com.example.Marker");
    ci.use("com.other.Marker");
    ci.finalizeResolution();
    // Both distinct FQNs share the short name, so neither is imported; both are emitted FQN.
    assertEquals("com.example.Marker", ci.use("com.example.Marker"));
    assertEquals("com.other.Marker", ci.use("com.other.Marker"));
    assertEquals(List.of(), ci.importLines());
  }

  @Test
  void importLinesAreGroupedJavaFirstThenOther() {
    TypeImports ci = new TypeImports(PKG);
    ci.use("bijnagte.recordbuf.runtime.Encoder");
    ci.use("bijnagte.recordbuf.runtime.CodecDefaults");
    ci.use("org.jspecify.annotations.Nullable");
    ci.use("java.util.List");
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
    TypeImports ci = new TypeImports(PKG);
    ci.use("com.example.a.Sub");
    ci.use("com.example.b.Marker");
    ci.finalizeResolution();
    assertEquals("Sub", ci.use("com.example.a.Sub"));
    assertEquals("Marker", ci.use("com.example.b.Marker"));
    assertTrue(ci.importLines().contains("import com.example.a.Sub;"));
    assertTrue(ci.importLines().contains("import com.example.b.Marker;"));
  }

  @Test
  void useIsIdempotentAfterFinalization() {
    TypeImports ci = new TypeImports(PKG);
    ci.use("com.example.Marker");
    ci.use("com.example.Marker");
    ci.finalizeResolution();
    assertEquals("Marker", ci.use("com.example.Marker"));
    assertEquals(List.of("import com.example.Marker;"), ci.importLines());
    assertFalse(ci.importLines().isEmpty());
  }
}
