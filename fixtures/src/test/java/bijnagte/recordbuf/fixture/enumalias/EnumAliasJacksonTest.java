package bijnagte.recordbuf.fixture.enumalias;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.enumalias.rec.EnumAlias;
import bijnagte.recordbuf.fixture.enumalias.rec.Flavor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a golden cannot say about the case-colliding alias: Jackson reads the alias spelling {@code
 * foo} and the canonical {@code FOO} to the one {@link Flavor#FOO} constant, and writes {@code
 * FOO}. The generated enum has one constant per distinct upper-cased name, so {@code foo} is not a
 * second constant.
 */
class EnumAliasJacksonTest {

  private static final JsonMapper M = new JsonMapper();

  @Test
  void aliasAndCanonicalSpellingReadTheSameConstant() {
    assertEquals(Flavor.FOO, M.readValue("\"foo\"", Flavor.class));
    assertEquals(Flavor.FOO, M.readValue("\"FOO\"", Flavor.class));
  }

  @Test
  void mergedConstantWritesItsCanonicalSpelling() {
    assertEquals("\"FOO\"", M.writeValueAsString(Flavor.FOO));
  }

  @Test
  void aliasInARecordReadsThroughBothEnumShapes() {
    EnumAlias read =
        M.readValue(
            "{\"singularFlavor\":\"foo\",\"repeatedFlavors\":[\"foo\",\"BAR\"]}", EnumAlias.class);

    assertEquals(Flavor.FOO, read.singularFlavor());
    assertEquals(List.of(Flavor.FOO, Flavor.BAR), read.repeatedFlavors());
  }

  @Test
  void mergedConstantsKeepTheProtoNumbers() {
    assertEquals(
        List.of("ZERO", "FOO", "BAR"),
        Arrays.stream(Flavor.values()).map(Enum::name).toList(),
        "foo and FOO share one constant");
    assertEquals(1, Flavor.FOO.getNumber());
    assertEquals(Flavor.FOO, Flavor.forNumber(1));
  }
}
