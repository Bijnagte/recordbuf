package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.ResolutionContext;
import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RawExprTest {

  private static ResolutionContext ctx(boolean collidedBuilder) {
    return new ResolutionContext(
        "bijnagte.recordbuf", collidedBuilder ? Set.of("Builder") : Set.of());
  }

  @Test
  void of_withNoArgs_rendersTemplateVerbatim() {
    RawExpr r = RawExpr.of("var x = 42;");
    assertEquals("var x = 42;", r.render(ctx(false)));
  }

  @Test
  void at0_rendersFirstTypeRefArg() {
    RawExpr r = RawExpr.of("new @0<>()", TypeRef.of("java.util.HashMap"));
    assertEquals("new HashMap<>()", r.render(ctx(false)));
  }

  @Test
  void at0_collidedTypeRef_rendersFqn() {
    RawExpr r = RawExpr.of("new @0<>()", TypeRef.of("com.foo.Builder"));
    assertEquals("new com.foo.Builder<>()", r.render(ctx(true)));
  }

  @Test
  void escapedAt_rendersLiteralAt() {
    RawExpr r = RawExpr.of("price is @@", TypeRef.of("java.util.List"));
    assertEquals("price is @", r.render(ctx(false)));
  }

  @Test
  void twoTypeRefArgs_renderInOrder() {
    RawExpr r =
        RawExpr.of(
            "@0 = new @1()", TypeRef.of("java.util.HashMap"), TypeRef.of("java.lang.Object"));
    // Note: "java.lang.Object" → Named("java.lang","Object",[]) → resolves to "Object" (java.lang
    // skip).
    assertEquals("HashMap = new Object()", r.render(ctx(false)));
  }

  @Test
  void mixedStringAndTypeRefArgs() {
    RawExpr r =
        RawExpr.of(
            "var @0 = new @1<>();",
            "items",
            TypeRef.of("java.util.ArrayList"),
            TypeRef.of("java.lang.String"));
    // TypeRef.of("java.util.ArrayList") has no generic args so renders as bare "ArrayList".
    assertEquals("var items = new ArrayList<>();", r.render(ctx(false)));
  }

  @Test
  void nestedRawExpr_inArgs() {
    RawExpr inner = RawExpr.of("@0.foo()", TypeRef.of("java.util.List"));
    RawExpr outer = RawExpr.of("return @0;", inner);
    assertEquals("return List.foo();", outer.render(ctx(false)));
  }

  @Test
  void of_outOfBounds_throwsIAE() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RawExpr.of("@0 = @1", TypeRef.of("java.util.HashMap")));
  }

  @Test
  void of_emptyTemplate_doesNotThrow() {
    RawExpr r = RawExpr.of("");
    assertEquals("", r.render(ctx(false)));
  }

  @Test
  void collect_registersTypeRefs() {
    TypeCollector ci = new TypeCollector("com.example");
    RawExpr r = RawExpr.of("var x = new @0<>()", TypeRef.of("java.util.HashMap"));
    ci.accumulate(r.referencedTypes());
    ci.finalizeResolution();
    assertTrue(
        ci.importLines().stream().anyMatch(l -> l.contains("java.util.HashMap")),
        "collect should register HashMap import");
  }

  @Test
  void failFast_atOneWithZeroArgs_throws() {
    assertThrows(IllegalArgumentException.class, () -> RawExpr.of("x = @1"));
  }

  @Test
  void failFast_at0WithOneArg_ok() {
    RawExpr r = RawExpr.of("x = @0", TypeRef.of("java.util.List"));
    assertTrue(r.render(ctx(false)).contains("List"));
  }

  @Test
  void collect_skipsStringArgs() {
    TypeCollector ci = new TypeCollector("com.example");
    // String arg should produce no import
    RawExpr r = RawExpr.of("return @0;", "someString");
    ci.accumulate(r.referencedTypes());
    ci.finalizeResolution();
    assertTrue(ci.importLines().isEmpty(), "string args produce no imports");
  }
}
