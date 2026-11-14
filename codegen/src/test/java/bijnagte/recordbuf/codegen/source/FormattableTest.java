package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TypeRef} implements {@link Formattable}:
 *
 * <ul>
 *   <li>{@link TypeRef#render(ResolutionContext)} delegates to {@code resolved(...)} for all
 *       variants.
 *   <li>{@link TypeRef#collect(TypeCollector)} registers the FQN for a {@link TypeRef.Named}, is a
 *       no-op for {@link TypeRef.Primitive}, and recurses into the element for {@link
 *       TypeRef.Array}.
 * </ul>
 */
class FormattableTest {

  private static final ResolutionContext CTX =
      new ResolutionContext("bijnagte.recordbuf", Set.of());

  @Test
  void named_collect_registersImport() {
    TypeCollector ci = new TypeCollector("com.example");
    TypeRef.Named ref = TypeRef.named("java.util", "HashMap", List.of());
    ref.collect(ci);
    List<String> lines = ci.importLines();
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("java.util.HashMap")),
        "HashMap import should be registered, got: " + lines);
  }

  @Test
  void named_collect_skipsJavaLang() {
    TypeCollector ci = new TypeCollector("com.example");
    TypeRef.Named ref = TypeRef.named("java.lang", "String", List.of());
    ref.collect(ci);
    List<String> lines = ci.importLines();
    assertTrue(
        lines.stream().noneMatch(l -> l.contains("java.lang.String")),
        "java.lang types should not produce import lines, got: " + lines);
  }

  @Test
  void primitive_collect_isNoOp() {
    TypeCollector ci = new TypeCollector("com.example");
    TypeRef.Primitive.INT.collect(ci);
    List<String> lines = ci.importLines();
    assertTrue(lines.isEmpty(), "primitive collection should produce no imports, got: " + lines);
  }

  @Test
  void array_collect_registersElementType() {
    TypeCollector ci = new TypeCollector("com.example");
    TypeRef.Named map = TypeRef.named("java.util", "Map", List.of());
    TypeRef.Array arr = new TypeRef.Array(map);
    arr.collect(ci);
    List<String> lines = ci.importLines();
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("java.util.Map")),
        "Array collect should register element type, got: " + lines);
  }

  @Test
  void named_render_returnsShortForm_whenUncolllided() {
    TypeRef.Named ref = TypeRef.named("java.util", "HashMap", List.of());
    String rendered = ref.render(CTX);
    assertEquals("HashMap", rendered);
  }

  @Test
  void named_render_returnsFqN_whenCollided() {
    TypeRef.Named ref = TypeRef.named("com.other", "Foo", List.of());
    ResolutionContext collidedCtx = new ResolutionContext("bijnagte.recordbuf", Set.of("Foo"));
    String rendered = ref.render(collidedCtx);
    assertEquals("com.other.Foo", rendered);
  }

  @Test
  void primitive_render_isKeyword() {
    assertEquals("int", TypeRef.Primitive.INT.render(CTX));
  }

  @Test
  void array_render_appendsBrackets() {
    TypeRef.Array arr = TypeRef.array(TypeRef.of("java.util.List"));
    assertEquals("List[]", arr.render(CTX));
  }

  @Test
  void named_with_generics_collectRegistersBase() {
    TypeCollector ci = new TypeCollector("com.example");
    TypeRef.Named map =
        TypeRef.named(
            "java.util",
            "Map",
            List.of(TypeRef.of("java.lang.String"), TypeRef.of("java.lang.Integer")));
    map.collect(ci);
    List<String> lines = ci.importLines();
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("java.util.Map")),
        "generics-bearing Named should register the base, got: " + lines);
  }

  @Test
  void genericNamed_render_preservesArgumentForms() {
    TypeRef.Named map =
        TypeRef.named(
            "java.util",
            "Map",
            List.of(TypeRef.of("java.lang.String"), TypeRef.of("java.lang.Integer")));
    assertEquals("Map<String, Integer>", map.render(CTX));
  }

  @Test
  void collect_and_render_areIndependent() {
    // collect() is a side-effect; render() does not return anything.
    // Verify they can be called in either order without interaction.
    TypeCollector ci = new TypeCollector("com.example");
    TypeRef.Named ref = TypeRef.named("java.util", "HashMap", List.of());
    String r1 = ref.render(CTX); // render first
    ref.collect(ci);
    String r2 = ref.render(CTX); // render after collect
    assertEquals(r1, r2, "render is deterministic, unaffected by collect");
    assertDoesNotThrow(() -> ref.collect(ci));
  }
}
