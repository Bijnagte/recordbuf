package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Guards the closedness of the idiom set (DEC-3 / B4). The body-lift builds toward a closed set of
 * {@link Statement} / {@link Expression} nodes; {@link Statement.Fragment} and {@link
 * Expression.Fragment} are the documented escape for syntax the set has not yet modeled. A growing
 * fragment count signals an idiom the set could learn; widening the sealed set without a reason is
 * a red flag. This test pins both hierarchies to their current, intended closed shape so the set
 * cannot silently grow or drift -- the assertion lists are updated intentionally as control-flow
 * nodes land (the next body-lift slice); raw-string bodies stay measured in the fragment node.
 */
class SourceAstClosednessTest {

  @Test
  void statementHierarchyIsTheIntendedClosedSet() {
    Set<String> permitted = simpleNames(Statement.class.getPermittedSubclasses());
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
                "Throw",
                "While")),
        permitted,
        "the Statement idiom set must stay closed; a new node means a new rendered idiom -- "
            + "justified by a mapping need, not drift");
  }

  @Test
  void expressionHierarchyIsTheIntendedClosedSet() {
    Set<String> permitted = simpleNames(Expression.class.getPermittedSubclasses());
    assertEquals(
        new TreeSet<>(
            Arrays.asList(
                "Assign",
                "Binary",
                "Call",
                "Fragment",
                "Literal",
                "Member",
                "New",
                "Parens",
                "Ternary")),
        permitted,
        "the Expression idiom set must stay closed");
  }

  @Test
  void fragmentEscapesAreLeafVerbatim() {
    // A Statement fragment and an Expression fragment both render their text unaltered.
    assertTrue(new Statement.Fragment("x;").text().equals("x;"));
    assertTrue(
        new Expression.Fragment("Wkt.parseTimestamp(in.readByteArray())")
            .text()
            .equals("Wkt.parseTimestamp(in.readByteArray())"));
  }

  private static Set<String> simpleNames(Class<?>[] subtypes) {
    TreeSet<String> names = new TreeSet<>();
    for (Class<?> c : subtypes) {
      names.add(c.getSimpleName());
    }
    return names;
  }
}
