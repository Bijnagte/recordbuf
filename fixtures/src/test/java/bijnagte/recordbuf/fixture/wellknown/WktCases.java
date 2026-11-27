package bijnagte.recordbuf.fixture.wellknown;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.wellknown.rec.Sample;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The golden cases of family {@code wellknown}, migrated from {@code WktInteropTest}: the {@link
 * Sample} with every supported well-known type, the structured ones ({@code Timestamp}, {@code
 * Duration}, {@code FieldMask}, {@code Empty}), the scalar wrappers and the bytes wrapper, and a
 * repeated {@code Timestamp}. {@code populated} is the value that test built for its round-trip and
 * its byte comparison, and {@code all-default} the one it decoded from zero bytes (wrappers {@code
 * null}, structured types at their zero). The other cases are the values its direct per-type wire
 * checks used, now carried by the record: {@code timestamp-nanos}, {@code duration-fraction},
 * {@code scalar-wrappers} and {@code bytes-wrapper}. {@code wrappers-at-default} is the presence
 * boundary the test could not reach: each wrapper set to its type's default, which is a present,
 * empty wrapper message and so not the {@code null} of {@code all-default}. {@code
 * default-timestamp-element} keeps a default element in the repeated {@code Timestamp}, which the
 * encoder writes as protobuf-java does.
 *
 * <p>{@code timestamp-nanos} is lossy in both Avro formats by design: Avro's timestamp is whole
 * microseconds, so those goldens deserialize to a record without the nanoseconds.
 *
 * <p>The proto has no protobuf-java {@code Interop}, so these records have no proto3 JSON golden;
 * the well-known types render natively in the Jackson and Avro goldens.
 */
public final class WktCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "wellknown";

  @Override
  public List<GoldenCase> cases() {
    // Avro stores a Timestamp as long microseconds since the epoch, so the 123 nanoseconds of
    // timestamp-nanos do not survive an Avro round trip.
    Sample atWholeMicros = Sample.builder().at(Instant.ofEpochSecond(1_700_000_000L)).build();
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", Sample.builder().build())),
        build(
            jacksonLossless(
                    FAMILY,
                    "timestamp-nanos",
                    Sample.builder().at(Instant.ofEpochSecond(1_700_000_000L, 123)).build())
                .expectedAfterTrip(avroJson, atWholeMicros)
                .expectedAfterTrip(avroBinary, atWholeMicros)),
        build(
            jacksonLossless(
                FAMILY,
                "duration-fraction",
                Sample.builder().span(Duration.ofMillis(3_600_500)).build())),
        build(
            jacksonLossless(
                FAMILY,
                "scalar-wrappers",
                Sample.builder().i32(42).i64(7L).dbl(3.5d).flt(1.5f).flag(true).text("x").build())),
        build(
            jacksonLossless(
                FAMILY,
                "bytes-wrapper",
                Sample.builder().blob(Bytes.of(new byte[] {9, 8, 7})).build())),
        build(
            jacksonLossless(
                FAMILY,
                "wrappers-at-default",
                Sample.builder()
                    .i32(0)
                    .i64(0L)
                    .dbl(0.0d)
                    .flt(0.0f)
                    .flag(false)
                    .text("")
                    .blob(Bytes.of(new byte[0]))
                    .build())),
        build(
            jacksonLossless(
                FAMILY,
                "default-timestamp-element",
                Sample.builder().whens(List.of(Instant.EPOCH, Instant.ofEpochSecond(1))).build())));
  }

  /** Every well-known field populated: the value {@code WktInteropTest} asserted on. */
  public static Sample populated() {
    return Sample.builder()
        .at(Instant.ofEpochSecond(1_700_000_000L))
        .span(Duration.ofHours(1))
        .mask(FieldMask.of(List.of("a.b", "c")))
        .i32(42)
        .i64(7L)
        .dbl(3.14d)
        .flt(2.5f)
        .flag(true)
        .text("hi")
        .blob(Bytes.of(new byte[] {1, 2, 3}))
        .whens(List.of(Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)))
        .build();
  }
}
