package bijnagte.recordbuf.fixture.interoponeof;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.interoponeof.rec.Color;
import bijnagte.recordbuf.fixture.interoponeof.rec.InteropOneof;
import bijnagte.recordbuf.fixture.interoponeof.rec.Part;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.json.DynamicWkt;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.List;

/**
 * The golden cases of family {@code interoponeof}, testing oneof members through the Interop
 * converter. The {@link InteropOneof} message has one oneof, {@code pick_2way}, whose protobuf-java
 * case accessor is {@code getPick2WayCase()}. It has one member of each kind: {@code bytes}, a
 * natively mapped {@code Timestamp}, a dynamic {@code Struct}, an enum, a message and a scalar. A
 * case sets one member; {@code unset} leaves the oneof empty, and {@code timestamp-epoch} sets the
 * {@code Timestamp} member to its zero default, which a oneof member keeps because its presence is
 * explicit. Every case also has a proto3 JSON golden produced through the generated {@code Interop}
 * converter.
 */
public final class InteropOneofCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interoponeof";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "bytes", pick(blob(new byte[] {1, 2, 3})))),
        build(
            jacksonLossless(
                FAMILY, "timestamp", pick(at(Instant.ofEpochSecond(1_700_000_000L, 5))))),
        build(jacksonLossless(FAMILY, "timestamp-epoch", pick(at(Instant.EPOCH)))),
        build(jacksonLossless(FAMILY, "struct", pick(struct()))),
        build(jacksonLossless(FAMILY, "enum", pick(Color.COLOR_RED))),
        build(jacksonLossless(FAMILY, "message", pick(Part.builder().a(7).build()))),
        build(
            jacksonLossless(FAMILY, "scalar", pick(new InteropOneof.Pick2way.PickCountValue(42)))),
        build(jacksonLossless(FAMILY, "unset", InteropOneof.builder().build())));
  }

  private static InteropOneof pick(InteropOneof.Pick2way member) {
    return InteropOneof.builder().pick2way(member).build();
  }

  private static InteropOneof.Pick2way blob(byte[] bytes) {
    return new InteropOneof.Pick2way.PickBlobValue(Bytes.of(bytes));
  }

  private static InteropOneof.Pick2way at(Instant instant) {
    return new InteropOneof.Pick2way.PickAtValue(instant);
  }

  /** A {@code Struct} with a string, a number and a nested object member. */
  private static InteropOneof.Pick2way struct() {
    Struct struct =
        Struct.newBuilder()
            .putFields("name", Value.newBuilder().setStringValue("recordbuf").build())
            .putFields("count", Value.newBuilder().setNumberValue(2.5).build())
            .putFields(
                "nested",
                Value.newBuilder()
                    .setStructValue(
                        Struct.newBuilder()
                            .putFields("flag", Value.newBuilder().setBoolValue(true).build()))
                    .build())
            .build();
    return new InteropOneof.Pick2way.PickStructValue(DynamicWkt.struct(struct));
  }
}
