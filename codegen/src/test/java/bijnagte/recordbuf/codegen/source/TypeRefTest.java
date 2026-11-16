package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypeRefTest {

  @Test
  void of_parsesFqNIntoNamedPackageShort() {
    TypeRef r = TypeRef.of("java.util.HashMap");
    assertTrue(r instanceof TypeRef.Named);
    TypeRef.Named n = (TypeRef.Named) r;
    assertEquals("java.util", n.pkg());
    assertEquals("HashMap", n.shortName());
    assertTrue(n.generics().isEmpty());
    assertEquals("java.util.HashMap", n.fqn());
  }

  @Test
  void of_withGenericSuffix_parsesGenerics() {
    TypeRef r = TypeRef.of("java.util.List<java.lang.Integer>");
    assertTrue(r instanceof TypeRef.Named);
    TypeRef.Named n = (TypeRef.Named) r;
    assertEquals("java.util", n.pkg());
    assertEquals("List", n.shortName());
    assertEquals(1, n.generics().size());
    assertEquals("java.util.List", n.fqn());
  }

  @Test
  void of_handlesDefaultPackage() {
    TypeRef r = TypeRef.of("Color");
    assertTrue(r instanceof TypeRef.Named);
    TypeRef.Named n = (TypeRef.Named) r;
    assertEquals("", n.pkg());
    assertEquals("Color", n.shortName());
    assertEquals("Color", n.fqn());
  }

  @Test
  void primitive_rendersAsKeyword() {
    for (TypeRef.Primitive p : TypeRef.Primitive.values()) {
      assertEquals(
          p.name().toLowerCase(Locale.ROOT), p.fqn(), p.name() + " should render lowercase");
      assertEquals(p.name().toLowerCase(Locale.ROOT), p.shortName());
    }
  }

  @Test
  void array_rendersElementPlusBrackets() {
    TypeRef arr = TypeRef.array(TypeRef.Primitive.INT);
    assertEquals("int[]", arr.fqn());
    assertEquals("int", arr.shortName());
    assertEquals("int[]", arr.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_of_resolvedJavaLangShortWithoutImport() {
    TypeRef r = TypeRef.of("java.lang.String");
    assertEquals("String", r.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_of_resolvedInPackageShortWithoutImport() {
    TypeRef r = TypeRef.of("bijnagte.recordbuf.Message");
    assertEquals("Message", r.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_of_resolvedOtherPackageShortWhenUncollided() {
    TypeRef r = TypeRef.of("java.util.HashMap");
    assertEquals("HashMap", r.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_of_resolvedFqnWhenCollided() {
    TypeRef r = TypeRef.of("com.foo.Builder");
    Set<String> collided = Set.of("Builder");
    assertEquals("com.foo.Builder", r.resolved("bijnagte.recordbuf", collided));
  }

  @Test
  void named_withGenerics_resolvesEachArgIndependently() {
    // java.util.List with generic arg java.lang.String -> java.lang is short
    TypeRef.Named outer =
        TypeRef.named("java.util", "List", List.of(TypeRef.of("java.lang.String")));
    assertEquals("List<String>", outer.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_withGenerics_fqnIsPackageShortWithoutGenerics() {
    TypeRef.Named outer =
        TypeRef.named("java.util", "List", List.of(TypeRef.of("java.lang.String")));
    assertEquals("java.util.List", outer.fqn());
  }

  @Test
  void array_ofNamed_resolvedCollidesThroughElement() {
    TypeRef.Named colliding = (TypeRef.Named) TypeRef.of("com.foo.Builder");
    TypeRef arr = TypeRef.array(colliding);
    assertEquals("com.foo.Builder[]", arr.resolved("bijnagte.recordbuf", Set.of("Builder")));
  }

  @Test
  void array_ofPrimitive_resolvedNoCollision() {
    TypeRef arr = TypeRef.array(TypeRef.Primitive.INT);
    assertEquals("int[]", arr.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_withTypeArgs_createsWithGenerics() {
    TypeRef.Named m =
        ((TypeRef.Named) TypeRef.of("java.util.Map"))
            .withGenerics(TypeRef.of("java.lang.Integer"), TypeRef.of("java.lang.String"));
    assertEquals("java.util.Map", m.fqn());
    assertEquals(2, m.generics().size());
    assertFalse(m.generics().isEmpty());
  }

  @Test
  void named_sub_extendsNestingChain() {
    TypeRef.Named nested = TypeRef.named("com.foo", "Bar").sub("Baz");
    assertEquals("com.foo.Bar.Baz", nested.fqn());
    assertEquals("Baz", nested.shortName());
    // The collision key and import target key off the OUTER type (Bar), not the innermost (Baz).
    assertEquals("Bar", nested.collisionKey());
    assertEquals("com.foo.Bar", nested.importFqn());
    // The un-collided use-site form is the nested short (Bar.Baz), not just Baz.
    assertEquals("Bar.Baz", nested.renderedShort());
    assertEquals(List.of("Bar"), nested.enclosing());
  }

  @Test
  void named_sub_chainsMultipleLevels() {
    TypeRef.Named nested = TypeRef.named("com.foo", "Bar").sub("Baz").sub("Qux");
    assertEquals("com.foo.Bar.Baz.Qux", nested.fqn());
    assertEquals(List.of("Bar", "Baz"), nested.enclosing());
    assertEquals("com.foo.Bar", nested.importFqn());
    assertEquals("Bar", nested.collisionKey());
    assertEquals("Bar.Baz.Qux", nested.renderedShort());
  }

  @Test
  void named_sub_dropsGenericsOfTheParent() {
    TypeRef.Named parent = TypeRef.named("com.foo", "Bar", List.of(TypeRef.of("java.lang.String")));
    TypeRef.Named nested = parent.sub("Baz");
    assertTrue(nested.generics().isEmpty());
    assertEquals(List.of("Bar"), nested.enclosing());
  }

  @Test
  void named_nested_resolvedUncollidedRendersNestedShort() {
    TypeRef.Named nested = TypeRef.named("com.foo", "Bar").sub("Baz");
    assertEquals("Bar.Baz", nested.resolved("bijnagte.recordbuf", Set.of()));
  }

  @Test
  void named_nested_resolvedCollidedFqnsOnOuterShort() {
    TypeRef.Named nested = TypeRef.named("com.foo", "Bar").sub("Baz");
    // A collision on the outer short (Bar) falls the whole nested reference back to its full FQN.
    assertEquals("com.foo.Bar.Baz", nested.resolved("bijnagte.recordbuf", Set.of("Bar")));
  }

  @Test
  void named_topLevel_collisionKeyIsShortName() {
    TypeRef.Named top = TypeRef.named("com.foo", "Bar");
    assertEquals("Bar", top.collisionKey());
    assertEquals("com.foo.Bar", top.importFqn());
    assertEquals("Bar", top.renderedShort());
  }
}
