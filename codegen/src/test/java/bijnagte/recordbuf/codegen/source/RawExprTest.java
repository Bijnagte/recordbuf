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
  void of_withTypeEmbedding_argSubstitutes() {
    RawExpr r = RawExpr.of("new %d<>()", new TypeEmbedding(TypeRef.of("java.util.HashMap")));
    // java.util is not java.lang nor the declaring pkg → uncollided → short
    assertEquals("new HashMap<>()", r.render(ctx(false)));
  }

  @Test
  void of_withCollidedEmbedding_argSubstitutesFqn() {
    RawExpr r = RawExpr.of("new %d<>()", new TypeEmbedding(TypeRef.of("com.foo.Builder")));
    assertEquals("new com.foo.Builder<>()", r.render(ctx(true)));
  }

  @Test
  void withEscapePercent_literalPercent() {
    // %% renders as a literal %, arg is not consumed
    RawExpr r = RawExpr.of("printf %%.100", new TypeEmbedding(TypeRef.of("java.util.List")));
    assertEquals("printf %.100", r.render(ctx(false))); // list arg is not consumed when template
    //  has no %d
  }

  @Test
  void withEscapePercent_andPlaceholder() {
    // %% is a literal, %d is the arg placeholder
    RawExpr r = RawExpr.of("%%fmt=%.2f val", new TypeEmbedding(TypeRef.of("java.util.List")));
    // %% → literal %, then "fmt=2f val" — the arg is not consumed (no %d)
    // Actually the template is "%%fmt=%.2f val": %% renders as literal %, "fmt=", % then '.'
    // which is not %d, so 'f' and '2' are literal, and the arg remains unconsumed
    assertEquals("%fmt=%.2f val", r.render(ctx(false)));
  }

  @Test
  void withTwoArgs_rendersInOrder() {
    RawExpr r =
        RawExpr.of(
            "%d = new %d()",
            new TypeEmbedding(TypeRef.of("java.util.HashMap")),
            new TypeEmbedding(TypeRef.of("java.lang.Object")));
    assertEquals("HashMap = new Object()", r.render(ctx(false)));
  }

  @Test
  void typeEmbedding_rendersCollidedAsFqn() {
    TypeEmbedding t = new TypeEmbedding(TypeRef.of("com.example.Builder"));
    assertEquals("com.example.Builder", t.render(ctx(true)));
  }
}
