package bijnagte.recordbuf.fixture.oneofwkt;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.oneofwkt.rec.Sample;
import bijnagte.recordbuf.fixture.oneofwkt.rec.Sample.V;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The golden cases of family {@code oneofwkt}, migrated from {@code OneofWktTest}: a {@link Sample}
 * whose oneof {@code v} holds a well-known type (Timestamp, Int32Value wrapper, Value, Duration,
 * Empty, FieldMask, StringValue) or bytes. A set member is always on the wire, zero values
 * included. One case per value that class asserted on, either in its byte and decode checks against
 * protobuf-java or in its Avro round trip: the Timestamp at nanosecond, microsecond and
 * before-the-epoch precision, the wrapper at zero and negative, empty and non-empty bytes, a string
 * and an object {@code Value}, a negative-fraction and a maximum Duration, {@code Empty}, a
 * non-empty and an empty FieldMask, an empty StringValue, and the oneof unset.
 *
 * <p>Avro stores a Timestamp as whole microseconds, flooring any finer part, so {@code at-nanos}
 * and {@code at-before-epoch-nanos} declare both Avro formats to deserialize to the floored record
 * (the case {@code aTimestampMemberFloorsToTheMicrosecondInAvro} asserted). The proto has no
 * protobuf-java {@code Interop}, so there is no proto3 JSON golden.
 */
public final class OneofWktCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "oneofwkt";

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  @Override
  public List<GoldenCase> cases() {
    Sample atWholeMicros = at(Instant.ofEpochSecond(1_763_614_896L, 0));
    Sample beforeEpochFloored = at(Instant.ofEpochSecond(-1, 999_999_000));
    return List.of(
        build(
            jacksonLossless(FAMILY, "at-nanos", at(Instant.ofEpochSecond(1_763_614_896L, 789)))
                .expectedAfterTrip(avroJson, atWholeMicros)
                .expectedAfterTrip(avroBinary, atWholeMicros)),
        build(
            jacksonLossless(
                FAMILY, "at-micros", at(Instant.ofEpochSecond(1_763_614_896L, 789_000)))),
        build(jacksonLossless(FAMILY, "at-before-epoch-micros", beforeEpochFloored)),
        build(
            jacksonLossless(
                    FAMILY, "at-before-epoch-nanos", at(Instant.ofEpochSecond(-1, 999_999_999)))
                .expectedAfterTrip(avroJson, beforeEpochFloored)
                .expectedAfterTrip(avroBinary, beforeEpochFloored)),
        build(jacksonLossless(FAMILY, "at-epoch", at(Instant.EPOCH))),
        build(jacksonLossless(FAMILY, "count-zero", sample(new V.CountValue(0)))),
        build(jacksonLossless(FAMILY, "count-negative", sample(new V.CountValue(-7)))),
        build(jacksonLossless(FAMILY, "blob-empty", sample(new V.BlobValue(Bytes.empty())))),
        build(
            jacksonLossless(
                FAMILY,
                "blob-bytes",
                sample(new V.BlobValue(Bytes.of(new byte[] {1, (byte) 0xff}))))),
        build(
            jacksonLossless(
                FAMILY, "value-string", sample(new V.ValueValue(NODES.stringNode("s"))))),
        build(
            jacksonLossless(
                FAMILY,
                "value-object",
                sample(new V.ValueValue(NODES.objectNode().put("k", "v"))))),
        // A proto Duration carries same-signed nanos: -1.5s is {-1, -500000000}.
        build(
            jacksonLossless(
                FAMILY,
                "span-negative-fraction",
                sample(new V.SpanValue(Duration.ofMillis(-1500))))),
        build(
            jacksonLossless(
                FAMILY,
                "span-max-seconds",
                sample(new V.SpanValue(Duration.ofSeconds(Long.MAX_VALUE, 999_999_999))))),
        build(jacksonLossless(FAMILY, "nothing", sample(new V.NothingValue(Empty.INSTANCE)))),
        build(
            jacksonLossless(
                FAMILY, "mask-paths", sample(new V.MaskValue(FieldMask.of("a.b", "c"))))),
        build(jacksonLossless(FAMILY, "mask-empty", sample(new V.MaskValue(FieldMask.empty())))),
        build(jacksonLossless(FAMILY, "text-empty", sample(new V.TextValue("")))),
        build(jacksonLossless(FAMILY, "unset", Sample.builder().build())));
  }

  private static Sample at(Instant instant) {
    return sample(new V.AtValue(instant));
  }

  private static Sample sample(V member) {
    return Sample.builder().v(member).build();
  }
}
