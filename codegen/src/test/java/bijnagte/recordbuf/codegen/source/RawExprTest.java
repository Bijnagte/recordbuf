package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    assertEquals("var x = 42;", r.render(null));
  }

  @Test
  void of_withTypeRef_argSubstitutes() {
    RawExpr r = RawExpr.of("new %d<>()", TypeRef.of("java.util.HashMap"));
    assertEquals("new HashMap<>()", r.render(ctx(false)));
  }

  @Test
  void of_withCollidedTypeRef_argSubstitutesFqn() {
    RawExpr r = RawExpr.of("new %d<>()", TypeRef.of("com.foo.Builder"));
    assertEquals("new com.foo.Builder<>()", r.render(ctx(true)));
  }

  @Test
  void escapePercent_literalPercent() {
    RawExpr r = RawExpr.of("printf %%.100", TypeRef.of("java.util.List"));
    assertEquals("printf %.100", r.render(ctx(false)));
  }

  @Test
  void twoTypeRefArgs_renderInOrder() {
    RawExpr r =
        RawExpr.of(
            "%d = new %d()", TypeRef.of("java.util.HashMap"), TypeRef.of("java.lang.Object"));
    assertEquals("HashMap = new Object()", r.render(ctx(false)));
  }
}
