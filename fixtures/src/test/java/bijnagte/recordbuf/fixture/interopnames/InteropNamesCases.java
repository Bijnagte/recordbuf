package bijnagte.recordbuf.fixture.interopnames;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.interopnames.rec.InteropNames;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden cases of family {@code interopnames}, testing protobuf-java accessor name generation
 * for edge-case field names. The {@link InteropNames} message carries fields with names that
 * trigger every branch of protoc's Java camel-case rule: underscores, digits, uppercase letters,
 * and combinations thereof. A populated case sets each field to a distinct non-zero value, and a
 * default case sets them all to their defaults. Both must round-trip identically through protobuf
 * binary and proto3 JSON via the generated Interop converter.
 */
public final class InteropNamesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopnames";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", InteropNames.builder().build())));
  }

  /** A non-default, non-empty value for every field. */
  public static InteropNames populated() {
    return InteropNames.builder()
        .fieldname1(11)
        .fieldName2(22)
        .fieldName4(-33)
        .field0name5(44)
        .field0Name6(-55)
        .fieldName7(66)
        .fieldName9(-77)
        .fieldName15(88)
        .fieldName16(-99)
        .fieldName17(101)
        .list2Values(List.of(1, 2, 3))
        .map3Entries(entries())
        .build();
  }

  private static Map<String, Integer> entries() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("a", 10);
    m.put("b", 20);
    return m;
  }
}
