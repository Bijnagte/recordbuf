package bijnagte.recordbuf.fixture.interopwkt;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.interopwkt.rec.InteropWkt;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The golden cases of family {@code interopwkt}, testing natively mapped well-known type fields
 * through the Interop converter. The {@link InteropWkt} message carries each scalar wrapper and
 * {@code BytesValue}, singular and repeated, and {@code Duration}, {@code Timestamp}, {@code
 * FieldMask} and {@code Empty}, singular and repeated. Every case also has a proto3 JSON golden
 * produced through the generated {@code Interop} converter.
 */
public final class InteropWktCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopwkt";

  private static final String DEFAULT_ELEMENT_NOT_WRITTEN =
      "the record drops a repeated element equal to the default (Duration.ZERO, Empty.INSTANCE),"
          + " which protobuf-java writes, so its protobuf binary golden would not be protobuf-java's"
          + " bytes (gap repeated-wkt-default-element)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", InteropWkt.builder().build())),
        build(
            jacksonLossless(
                FAMILY,
                "zero-wrappers",
                InteropWkt.builder().boolWrapper(false).int32Wrapper(0).stringWrapper("").build())),
        // Its proto3.json golden was written from protobuf-java's own builder: the seeder seeds
        // from
        // the record's bytes, which drop the default elements (gap repeated-wkt-default-element).
        build(
            jacksonLossless(
                    FAMILY,
                    "repeated-defaults",
                    InteropWkt.builder()
                        .repeatedDuration(List.of(Duration.ZERO, Duration.ofSeconds(1)))
                        .repeatedEmpty(List.of(Empty.INSTANCE, Empty.INSTANCE))
                        .build())
                .notApplicable(protobufBinary, DEFAULT_ELEMENT_NOT_WRITTEN)));
  }

  /**
   * Every natively mapped WKT set: the wrappers at non-default values, a negative duration, a
   * timestamp with nanos, a two-path field mask, and two non-default elements in each repeated
   * structured or wrapper list. The repeated {@code Empty} list stays empty, because an {@code
   * Empty} element is always the default.
   */
  public static InteropWkt populated() {
    return InteropWkt.builder()
        .boolWrapper(true)
        .int32Wrapper(-42)
        .uint32Wrapper(2_000_000_000)
        .int64Wrapper(-9_000_000_000L)
        .uint64Wrapper(9_000_000_000L)
        .floatWrapper(1.5f)
        .doubleWrapper(-2.25d)
        .stringWrapper("wrapped")
        .bytesWrapper(Bytes.of(new byte[] {1, 2}))
        .duration(Duration.ofMillis(-1500))
        .timestamp(Instant.ofEpochSecond(1_700_000_000L, 123_000_000))
        .fieldMask(FieldMask.of("b.c", "a"))
        .empty(Empty.INSTANCE)
        .repeatedBoolWrapper(List.of(true, true))
        .repeatedInt32Wrapper(List.of(-1, 7))
        .repeatedUint32Wrapper(List.of(1, 2_000_000_000))
        .repeatedInt64Wrapper(List.of(-5L, 8L))
        .repeatedUint64Wrapper(List.of(3L, 9_000_000_000L))
        .repeatedFloatWrapper(List.of(0.5f, -2.5f))
        .repeatedDoubleWrapper(List.of(1.25d, -4.0d))
        .repeatedStringWrapper(List.of("a", "bc"))
        .repeatedBytesWrapper(
            List.of(Bytes.of(new byte[] {9}), Bytes.of(new byte[] {0x7F, (byte) 0x80})))
        .repeatedDuration(List.of(Duration.ofSeconds(2), Duration.ofMillis(-500)))
        .repeatedTimestamp(
            List.of(Instant.ofEpochSecond(1), Instant.ofEpochSecond(-1, 500_000_000)))
        .repeatedFieldMask(List.of(FieldMask.of("x"), FieldMask.of("c.d", "e")))
        .build();
  }
}
