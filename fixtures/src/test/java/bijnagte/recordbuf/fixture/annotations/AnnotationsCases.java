package bijnagte.recordbuf.fixture.annotations;

import bijnagte.recordbuf.fixture.annotations.rec.Sample;
import bijnagte.recordbuf.fixture.annotations.rec.Sample.Payload;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden cases of family {@code annotations}, migrated from {@code AnnotationsTest}: a {@link
 * Sample} that exercises every element kind recordbuf documents or deprecates (a type-level
 * comment, a {@code @Deprecated} optional string and map component, a nested {@link Payload}, and
 * HTML content in a comment), which changes nothing on the wire. {@code populated} is the value
 * that test asserted on, with the optional string, the nested message and the map all set, and
 * {@code all-default} the one it also asserted on, which encodes to zero bytes.
 *
 * <p>{@code all-default} has no Avro golden: the absent optional string and the absent nested
 * message cannot be written to Avro. The proto has no protobuf-java {@code Interop}, so there is no
 * proto3 JSON golden. The Javadoc and {@code @Deprecated} emission itself is a property of the
 * generated source, not of its data, and no golden can pin it.
 */
// The record's builder methods for the @Deprecated components (note, tags) are themselves
// deprecated; the cases must call them.
@SuppressWarnings("deprecation")
public final class AnnotationsCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "annotations";

  private static final String ABSENT_NOT_IN_AVRO =
      "note (optional string) and payload (singular message) are absent (null), and the Avro schema"
          + " declares both non-nullable, so the converter cannot write the record to Avro (gaps"
          + " optional-avro-presence and avro-absent-singular-message)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(
            jacksonLossless(FAMILY, "all-default", Sample.builder().build())
                .notApplicable(avroJson, ABSENT_NOT_IN_AVRO)
                .notApplicable(avroBinary, ABSENT_NOT_IN_AVRO)));
  }

  /** Every kind of component set: the value {@code AnnotationsTest} asserted on. */
  public static Sample populated() {
    Map<String, Integer> tags = new LinkedHashMap<>();
    tags.put("k", 1);
    tags.put("j", -2);
    return Sample.builder()
        .id(7)
        .note("hello <tag> & friends")
        .payload(Payload.builder().body("body").build())
        .tags(tags)
        .build();
  }
}
