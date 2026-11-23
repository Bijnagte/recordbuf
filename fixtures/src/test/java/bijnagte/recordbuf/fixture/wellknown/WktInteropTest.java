package bijnagte.recordbuf.fixture.wellknown;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.FieldMask;
import bijnagte.recordbuf.runtime.wkt.Wkt;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code wellknown} golden family (see {@link WktCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not. The protobuf binary format carries the byte comparison against protobuf-java and the decode
 * of protobuf-java's bytes that this class used to assert by hand.
 *
 * <p>The per-type checks below stay structural: they drive the runtime {@link Wkt} wire helpers
 * directly and compare each helper's bytes with protobuf-java's for the bare well-known message,
 * which is no record and so has no golden shape. {@link java.time.Duration} is referred to by
 * qualified name, and protobuf-java's {@code Duration} and {@code FieldMask} by qualified name, to
 * dodge the simple-name collision with the runtime and java.time types.
 */
class WktInteropTest {

  private static final WktCases SOURCE = new WktCases();

  @TestFactory
  Stream<DynamicNode> wellknownCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  // Direct per-WKT wire checks against the protobuf-java reference.

  @Test
  void timestampMatchesProtobufJava() throws Exception {
    Timestamp ref = Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123).build();
    assertArrayEquals(ref.toByteArray(), Wkt.timestamp(Instant.ofEpochSecond(1_700_000_000L, 123)));
    assertEquals(
        Instant.ofEpochSecond(1_700_000_000L, 123),
        Wkt.parseTimestamp(ref.toByteArray()),
        "round-trips");
  }

  @Test
  void durationMatchesProtobufJava() throws Exception {
    var ref =
        com.google.protobuf.Duration.newBuilder().setSeconds(3600).setNanos(500_000_000).build();
    assertArrayEquals(ref.toByteArray(), Wkt.duration(java.time.Duration.ofMillis(3_600_500)));
  }

  @Test
  void fieldMaskMatchesProtobufJava() throws Exception {
    var ref = com.google.protobuf.FieldMask.newBuilder().addPaths("a.b").addPaths("c").build();
    assertArrayEquals(ref.toByteArray(), Wkt.fieldMask(FieldMask.of(List.of("a.b", "c"))));
  }

  @Test
  void scalarWrappersMatchProtobufJava() throws Exception {
    assertArrayEquals(Int32Value.of(42).toByteArray(), Wkt.wrap(Wkt.Wrap.INT32, 42));
    assertArrayEquals(Int64Value.of(7L).toByteArray(), Wkt.wrap(Wkt.Wrap.INT64, 7L));
    assertArrayEquals(DoubleValue.of(3.5d).toByteArray(), Wkt.wrap(Wkt.Wrap.DOUBLE, 3.5d));
    assertArrayEquals(FloatValue.of(1.5f).toByteArray(), Wkt.wrap(Wkt.Wrap.FLOAT, 1.5f));
    assertArrayEquals(BoolValue.of(true).toByteArray(), Wkt.wrap(Wkt.Wrap.BOOL, true));
    assertArrayEquals(StringValue.of("x").toByteArray(), Wkt.wrap(Wkt.Wrap.STRING, "x"));
  }

  @Test
  void bytesWrapperMatchesProtobufJava() throws Exception {
    BytesValue ref = BytesValue.of(ByteString.copyFrom(new byte[] {9, 8, 7}));
    assertArrayEquals(ref.toByteArray(), Wkt.wrap(Wkt.Wrap.BYTES, Bytes.of(new byte[] {9, 8, 7})));
  }
}
