package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Guards the closedness of the idiom set (DEC-3 / B4). The body-lift builds toward a closed set of
 * {@link Element} nodes; {@link Fragment} is the documented escape for syntax the set has not yet
 * modeled. A growing fragment count signals an idiom the set could learn; widening the sealed set
 * without a reason is a red flag. This test pins the hierarchy to its current, intended closed
 * shape so the set cannot silently grow or drift -- the assertion list is updated intentionally as
 * control-flow nodes land (the next body-lift slice); raw-string bodies stay measured in the
 * fragment node.
 */
class SourceAstClosednessTest {

  @Test
  void elementHierarchyIsTheIntendedClosedSet() {
    Set<String> permitted = simpleNames(Element.class.getPermittedSubclasses());
    assertEquals(
        new TreeSet<>(
            Arrays.asList(
                "Comment",
                "ExprStatement",
                "For",
                "Fragment",
                "If",
                "LocalVar",
                "Return",
                "Switch",
                "Throw",
                "While")),
        permitted,
        "the Element idiom set must stay closed; a new node means a new rendered idiom -- "
            + "justified by a mapping need, not drift");
  }

  @Test
  void fragmentEscapeIsLeafVerbatim() {
    // A Element fragment renders its text unaltered.
    assertTrue(new Fragment("x;").text().equals("x;"));
  }

  /**
   * U3: every {@code Type.*} header FQN list must be a {@code List} of {@code TypeRef}, not {@code
   * List} of {@code String}. Guards against regression to string-based FQN handling.
   */
  @Test
  void typeHeaderListsAreTypeRef() throws Exception {
    checkTypeRefList(
        Arrays.asList(Type.Record.class, Type.Class.class, Type.Enum.class, Type.Interface.class));
  }

  private static void checkTypeRefList(List<Class<?>> typeClasses) throws Exception {
    for (Class<?> tc : typeClasses) {
      for (String fieldName : Arrays.asList("extendsFqns", "implementsFqns", "permitsFqns")) {
        Field f = findField(tc, fieldName);
        if (f == null) {
          continue; // not every variant declares every list; skip absent fields
        }
        ParameterizedType pType = (ParameterizedType) f.getGenericType();
        Class<?> argType = (Class<?>) pType.getActualTypeArguments()[0];
        assertEquals(
            TypeRef.class,
            argType,
            tc.getSimpleName()
                + "#"
                + fieldName
                + " must be List<TypeRef>, found "
                + argType.getSimpleName());
      }
    }
  }

  private static Field findField(Class<?> clazz, String name) {
    try {
      return clazz.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  private static Set<String> simpleNames(Class<?>[] subtypes) {
    TreeSet<String> names = new TreeSet<>();
    for (Class<?> c : subtypes) {
      names.add(c.getSimpleName());
    }
    return names;
  }
}
