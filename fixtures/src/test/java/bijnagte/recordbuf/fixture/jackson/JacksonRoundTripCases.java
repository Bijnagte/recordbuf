package bijnagte.recordbuf.fixture.jackson;

import bijnagte.recordbuf.fixture.bytes.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.fixture.jackson.full.rec.Inner;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The golden cases of family {@code jackson-roundtrip}, migrated from {@code JacksonRoundTripTest}:
 * the {@code Full} record (ISO temporals, the canonical knob, base64 bytes, dynamic WKT nodes, the
 * nested message, the proto3 camelCase names), the same record with a retained unknown JSON
 * property, and the main-source-set {@code bytes} and {@code wellknown} samples rendered through
 * Jackson.
 *
 * <p>The masked projection of {@code Full} ({@code full-masked.jackson.json}) is not a case: it is
 * a one-way view that {@code JsonFieldMaskFilter} cuts out of the mapper's tree, so the mapper
 * never writes it from any record. {@code JacksonRoundTripTest} still checks it against that
 * golden.
 */
public final class JacksonRoundTripCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "jackson-roundtrip";

  private static final String EPOCH_ELEMENT_NOT_WRITTEN =
      "the record holds a repeated Timestamp element equal to the default (Instant.EPOCH), which the"
          + " generated encoder does not write while protobuf-java does (gap"
          + " repeated-wkt-default-element), so its protobuf binary golden would not be"
          + " protobuf-java's bytes";

  private static final JsonMapper NODES = new JsonMapper();

  /** The timestamp every sample here uses: a whole number of milliseconds. */
  public static final Instant AT = Instant.ofEpochSecond(1_763_614_896L, 789_000_000);

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "full", full())),
        fullWithExtrasCase(),
        build(jacksonLossless(FAMILY, "bytes-sample", bytesSample())),
        build(
            jacksonLossless(FAMILY, "wellknown-sample", wellKnownSample())
                .notApplicable(protobufBinary, EPOCH_ELEMENT_NOT_WRITTEN)),
        build(jacksonLossless(FAMILY, "wellknown-sparse", wellKnownSparse())));
  }

  /**
   * The retained unknown JSON property is JSON-only: the Avro schema has no place for it, so both
   * Avro goldens deserialize to the record without it.
   */
  private GoldenCase fullWithExtrasCase() {
    return build(
        jacksonLossless(FAMILY, "full-with-extras", fullWithExtras())
            .expectedAfterTrip(avroJson, full())
            .expectedAfterTrip(avroBinary, full()));
  }

  /** The {@code Full} record with every field populated. */
  public static Full full() {
    return Full.builder()
        .name("n")
        .count(1_234_567_890_123L)
        .flag(true)
        .ratio(0.25)
        .blob(Bytes.of(new byte[] {1, 2, 3, (byte) 0xff}))
        .nickname("nick")
        .inner(Inner.builder().label("in").build())
        .value(NODES.readTree("{\"k\":[1.5,\"x\",null,true]}"))
        .doc((ObjectNode) NODES.readTree("{\"a\":{\"b\":\"c\"}}"))
        .items((ArrayNode) NODES.readTree("[2.25,\"y\"]"))
        .values(List.of(NODES.readTree("\"s\""), NODES.readTree("{\"z\":false}")))
        .at(AT)
        .span(Duration.ofSeconds(3661, 500_000_000))
        .canonicalSpan(Duration.ofSeconds(90, 5))
        .chunks(List.of(Bytes.of(new byte[] {0}), Bytes.empty()))
        .build();
  }

  /** {@link #full()} plus the retained unknown JSON property {@code futureField}. */
  public static Full fullWithExtras() {
    return full().toBuilder()
        .jsonExtras(Map.of("futureField", NODES.readTree("{\"x\":[1,\"two\"]}")))
        .build();
  }

  /** A {@code bytes} sample with an invalid-UTF-8 value and an empty one. */
  public static Sample bytesSample() {
    return Sample.builder().a(Bytes.of(new byte[] {(byte) 0xc3, 0x28})).b(Bytes.empty()).build();
  }

  /** A {@code wellknown} sample with every field set, among them a negative duration. */
  public static bijnagte.recordbuf.fixture.wellknown.rec.Sample wellKnownSample() {
    return bijnagte.recordbuf.fixture.wellknown.rec.Sample.builder()
        .at(AT)
        .span(Duration.ofMillis(-1500))
        .mask(FieldMask.of("user.display_name", "id"))
        .i32(7)
        .i64(-8L)
        .dbl(1.5)
        .flt(2.5f)
        .flag(false)
        .text("t")
        .blob(Bytes.of(new byte[] {9}))
        .empty(Empty.INSTANCE)
        .whens(List.of(Instant.EPOCH, AT))
        .build();
  }

  /** A {@code wellknown} sample with only {@code at} set: absent values are omitted. */
  public static bijnagte.recordbuf.fixture.wellknown.rec.Sample wellKnownSparse() {
    return bijnagte.recordbuf.fixture.wellknown.rec.Sample.builder().at(AT).build();
  }
}
