package bijnagte.recordbuf.fixture.wellknown;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import bijnagte.recordbuf.fixture.wellknown.rec.Sample.SampleCodec;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
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
import org.junit.jupiter.api.Test;

/**
 * Cross-framework interop for the supported well-known types (KTD3). Proves the runtime {@link Wkt}
 * wire helpers and the generated {@code Sample} record's codec are byte-identical to the
 * protobuf-java 4.36.2 reference -- the strongest guarantee a WKT frame is a nested message whose
 * {@code field 1} is the WKT's canonical inner, not a hand-rolled shape.
 *
 * <p>The record {@code Sample} lives in the {@code .rec} sibling package (fully-qualified to dodge
 * the same-simple-name collision with the protobuf-java reference {@code Sample}); the reference is
 * referred to by its natural-package simple name. Protobuf-java's {@code Duration} / {@code
 * FieldMask} are referred to by fully-qualified name, and {@code java.time.Duration} by alias, to
 * dodge the simple-name collision with the runtime / java.time types.
 */
class WktInteropTest {

  private final java.time.Duration hour = java.time.Duration.ofHours(1);
  private final Instant t1 = Instant.ofEpochSecond(1_700_000_000L);

  private bijnagte.recordbuf.fixture.wellknown.rec.Sample.Builder sample() {
    return bijnagte.recordbuf.fixture.wellknown.rec.Sample.builder()
        .at(t1)
        .span(hour)
        .mask(FieldMask.of(List.of("a.b", "c")))
        .i32(42)
        .i64(7L)
        .dbl(3.14d)
        .flt(2.5f)
        .flag(true)
        .text("hi")
        .blob(Bytes.of(new byte[] {1, 2, 3}))
        .whens(List.of(Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)));
  }

  private byte[] refBytes() {
    return Sample.newBuilder()
        .setAt(
            Timestamp.newBuilder().setSeconds(t1.getEpochSecond()).setNanos(t1.getNano()).build())
        .setSpan(
            com.google.protobuf.Duration.newBuilder()
                .setSeconds(hour.getSeconds())
                .setNanos(hour.getNano())
                .build())
        .setMask(com.google.protobuf.FieldMask.newBuilder().addPaths("a.b").addPaths("c").build())
        .setI32(Int32Value.of(42))
        .setI64(Int64Value.of(7L))
        .setDbl(DoubleValue.of(3.14d))
        .setFlt(FloatValue.of(2.5f))
        .setFlag(BoolValue.of(true))
        .setText(StringValue.of("hi"))
        .setBlob(BytesValue.of(ByteString.copyFrom(new byte[] {1, 2, 3})))
        .addWhens(Timestamp.newBuilder().setSeconds(1).build())
        .addWhens(Timestamp.newBuilder().setSeconds(2).build())
        .build()
        .toByteArray();
  }

  /**
   * Byte-for-byte: record encode == protobuf-java toByteArray for a fully-populated WKT message.
   */
  @Test
  void byteEqual() throws Exception {
    assertArrayEquals(
        refBytes(),
        SampleCodec.encode(sample().build()),
        "every WKT frame must be byte-identical to the protobuf-java reference");
  }

  /**
   * Protobuf-java bytes decode into the record, with each WKT component recovered as its native
   * type.
   */
  @Test
  void interopDecodesProtobuf() throws Exception {
    bijnagte.recordbuf.fixture.wellknown.rec.Sample r = SampleCodec.decode(refBytes());
    assertEquals(t1, r.at());
    assertEquals(hour, r.span());
    assertEquals(List.of("a.b", "c"), r.mask().paths());
    assertEquals(42, r.i32());
    assertEquals(7L, r.i64());
    assertEquals(3.14d, r.dbl());
    assertEquals(2.5f, r.flt());
    assertEquals(true, r.flag());
    assertEquals("hi", r.text());
    assertArrayEquals(new byte[] {1, 2, 3}, r.blob().toByteArray());
    assertEquals(List.of(Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)), r.whens());
    assertEquals(Empty.INSTANCE, r.empty());
  }

  /** Invariant: decode(encode(x)) == x (presence preserved, empty defaults recovered). */
  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.wellknown.rec.Sample x = sample().build();
    assertEquals(x, SampleCodec.decode(SampleCodec.encode(x)));
  }

  /**
   * A wrapper that is absent on the record decodes to null; structured WKTs keep their zero
   * default.
   */
  @Test
  void absentWrapperIsNull() throws Exception {
    bijnagte.recordbuf.fixture.wellknown.rec.Sample r = SampleCodec.decode(new byte[0]);
    assertNull(r.i32());
    assertNull(r.blob());
    assertEquals(Instant.EPOCH, r.at());
    assertEquals(java.time.Duration.ZERO, r.span());
    assertEquals(FieldMask.empty(), r.mask());
    assertEquals(Empty.INSTANCE, r.empty());
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
