package bijnagte.recordbuf.fixture.avrostructured;

import bijnagte.recordbuf.fixture.avrostructured.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.json.DynamicWkt;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The golden cases of family {@code avrostructured}, testing repeated structured well-known types
 * through the Avro converter. The {@link Sample} message carries a repeated {@code Duration},
 * {@code Timestamp}, {@code FieldMask}, {@code Empty}, {@code Struct} and {@code Any}. Proto3 JSON
 * is not applicable: the file has no Interop converter.
 *
 * <p>{@code populated} holds no {@code Empty} element: the encoder drops a repeated element equal
 * to its default, and a repeated {@code Empty} is always the default (gap {@code
 * repeated-wkt-default-element}). {@code empty-elements} carries them and has no protobuf binary
 * golden.
 *
 * <p>The timestamps are whole microseconds, Avro's precision, so {@code populated} round-trips in
 * both Avro formats.
 */
public final class AvroStructuredCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avrostructured";

  private static final String EMPTY_ELEMENT_NOT_WRITTEN =
      "empties holds Empty elements, which the generated encoder does not write (gap"
          + " repeated-wkt-default-element: a repeated Empty is always the default, so protobuf-java"
          + " writes bytes the encoder does not)";

  private static final String EMPTY_MASK_ELEMENT_NOT_WRITTEN =
      "masks holds an empty FieldMask element, which the generated encoder does not write (gap"
          + " repeated-wkt-default-element: a repeated FieldMask element equal to the default is"
          + " dropped, while protobuf-java writes it)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", Sample.builder().build())),
        build(
            jacksonLossless(
                    FAMILY,
                    "empty-elements",
                    Sample.builder().empties(List.of(Empty.instance(), Empty.instance())).build())
                .notApplicable(protobufBinary, EMPTY_ELEMENT_NOT_WRITTEN)),
        build(
            jacksonLossless(
                    FAMILY,
                    "empty-mask-element",
                    Sample.builder().masks(List.of(FieldMask.empty(), FieldMask.of("a"))).build())
                .notApplicable(protobufBinary, EMPTY_MASK_ELEMENT_NOT_WRITTEN)));
  }

  /**
   * Every repeated structured type with two elements, each non-default: a negative duration, a
   * sub-second timestamp in whole microseconds, two masks, and a struct and an {@code Any} each.
   */
  public static Sample populated() {
    return Sample.builder()
        .durations(List.of(Duration.ofMillis(1_500), Duration.ofMillis(-1_500)))
        .timestamps(
            List.of(Instant.ofEpochSecond(1_700_000_000L, 123_456_000L), Instant.ofEpochSecond(1L)))
        .masks(List.of(FieldMask.of(List.of("a", "b.c")), FieldMask.of(List.of("x"))))
        .structs(
            List.of(
                DynamicWkt.struct(Struct.newBuilder().putFields("k", string("v")).build()),
                DynamicWkt.struct(Struct.newBuilder().putFields("n", number(1.5)).build())))
        .anys(
            List.of(
                DynamicWkt.any(Any.pack(StringValue.of("a"))),
                DynamicWkt.any(Any.pack(StringValue.of("b")))))
        .build();
  }

  private static Value string(String value) {
    return Value.newBuilder().setStringValue(value).build();
  }

  private static Value number(double value) {
    return Value.newBuilder().setNumberValue(value).build();
  }
}
