package bijnagte.recordbuf.runtime.wkt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The protobuf-java conversions for the natively mapped well-known types: each direction, the
 * protobuf sign rule for {@code Duration}, the unsigned bit pattern of the {@code UInt*Value}
 * wrappers, and the null-in/null-out rule for every wrapper and {@code BytesValue}.
 */
class ProtobufJavaWktTest {

  @Test
  void timestampRoundTripsAndMatchesProtobufFields() {
    Instant v = Instant.ofEpochSecond(1_700_000_000L, 123);
    Timestamp proto = ProtobufJavaWkt.fromInstant(v);
    assertEquals(1_700_000_000L, proto.getSeconds());
    assertEquals(123, proto.getNanos());
    assertEquals(v, ProtobufJavaWkt.toInstant(proto));
  }

  @Test
  void preEpochTimestampUsesProtobufCanonicalFields() {
    // Instant(-1s + 500ns) is the floor pair {-1, 500}; protobuf's canonical nanos are
    // non-negative, so the protobuf fields are exactly {-1, 500}.
    Instant v = Instant.ofEpochSecond(-1L, 500L);
    Timestamp proto = ProtobufJavaWkt.fromInstant(v);
    assertEquals(-1L, proto.getSeconds());
    assertEquals(500, proto.getNanos());
    assertEquals(v, ProtobufJavaWkt.toInstant(proto));
  }

  @Test
  void negativeDurationUsesProtobufSignRule() {
    com.google.protobuf.Duration proto = ProtobufJavaWkt.fromDuration(Duration.ofMillis(-1500));
    assertEquals(-1L, proto.getSeconds());
    assertEquals(-500_000_000, proto.getNanos());
    assertEquals(Duration.ofMillis(-1500), ProtobufJavaWkt.toDuration(proto));
  }

  @Test
  void durationRoundTripsWithoutOverflow() {
    for (Duration d :
        List.of(
            Duration.ZERO,
            Duration.ofSeconds(17, 250_000_000),
            Duration.ofNanos(-1),
            Duration.ofSeconds(Long.MAX_VALUE / 2),
            Duration.ofSeconds(Long.MIN_VALUE / 2, 1))) {
      assertEquals(d, ProtobufJavaWkt.toDuration(ProtobufJavaWkt.fromDuration(d)));
    }
  }

  @Test
  void fieldMaskRoundTripsPathsInOrder() {
    FieldMask mask = FieldMask.of("a.b", "c");
    com.google.protobuf.FieldMask proto = ProtobufJavaWkt.fromFieldMask(mask);
    assertEquals(List.of("a.b", "c"), proto.getPathsList());
    assertEquals(mask, ProtobufJavaWkt.toFieldMask(proto));
  }

  @Test
  void emptyRoundTrips() {
    assertEquals(
        Empty.INSTANCE, ProtobufJavaWkt.toEmpty(ProtobufJavaWkt.fromEmpty(Empty.INSTANCE)));
    assertEquals(
        com.google.protobuf.Empty.getDefaultInstance(), ProtobufJavaWkt.fromEmpty(Empty.INSTANCE));
  }

  @Test
  void boolValueRoundTrips() {
    assertEquals(true, ProtobufJavaWkt.toBoolean(ProtobufJavaWkt.boolValue(true)));
    assertEquals(false, ProtobufJavaWkt.toBoolean(ProtobufJavaWkt.boolValue(false)));
  }

  @Test
  void int32ValueRoundTrips() {
    assertEquals(
        Integer.MIN_VALUE, ProtobufJavaWkt.toInt32(ProtobufJavaWkt.int32Value(Integer.MIN_VALUE)));
    assertEquals(0, ProtobufJavaWkt.toInt32(ProtobufJavaWkt.int32Value(0)));
  }

  @Test
  void uint32ValueCarriesSignedBitPattern() {
    UInt32Value max = Objects.requireNonNull(ProtobufJavaWkt.uint32Value(-1));
    assertEquals(-1, max.getValue());
    assertEquals(4_294_967_295L, Integer.toUnsignedLong(max.getValue()));
    assertEquals(-1, ProtobufJavaWkt.toUint32(max));
    assertEquals(
        Integer.MIN_VALUE,
        ProtobufJavaWkt.toUint32(ProtobufJavaWkt.uint32Value(Integer.MIN_VALUE)));
  }

  @Test
  void int64ValueRoundTrips() {
    assertEquals(
        Long.MAX_VALUE, ProtobufJavaWkt.toInt64(ProtobufJavaWkt.int64Value(Long.MAX_VALUE)));
  }

  @Test
  void uint64ValueCarriesSignedBitPattern() {
    UInt64Value max = Objects.requireNonNull(ProtobufJavaWkt.uint64Value(-1L));
    assertEquals(-1L, max.getValue());
    assertEquals(-1L, ProtobufJavaWkt.toUint64(max));
  }

  @Test
  void floatValueRoundTrips() {
    assertEquals(Float.valueOf(3.5f), ProtobufJavaWkt.toFloat(ProtobufJavaWkt.floatValue(3.5f)));
  }

  @Test
  void doubleValueRoundTrips() {
    assertEquals(
        Double.valueOf(-2.5d), ProtobufJavaWkt.toDouble(ProtobufJavaWkt.doubleValue(-2.5d)));
  }

  @Test
  void stringValueRoundTrips() {
    assertEquals("hi", ProtobufJavaWkt.toStringValue(ProtobufJavaWkt.stringValue("hi")));
    assertEquals("", ProtobufJavaWkt.toStringValue(ProtobufJavaWkt.stringValue("")));
  }

  @Test
  void bytesValueEmptyAndNonEmptyRoundTrip() {
    Bytes empty = Bytes.empty();
    Bytes payload = Bytes.of(new byte[] {1, -1, 0});
    assertEquals(empty, ProtobufJavaWkt.toBytes(ProtobufJavaWkt.bytesValue(empty)));
    assertEquals(payload, ProtobufJavaWkt.toBytes(ProtobufJavaWkt.bytesValue(payload)));
    assertEquals(
        ByteString.copyFrom(new byte[] {1, -1, 0}),
        Objects.requireNonNull(ProtobufJavaWkt.bytesValue(payload)).getValue());
  }

  @Test
  void nullMapsToNullForEveryWrapperAndBytesValue() {
    assertNull(ProtobufJavaWkt.toBoolean(null));
    assertNull(ProtobufJavaWkt.boolValue(null));
    assertNull(ProtobufJavaWkt.toInt32(null));
    assertNull(ProtobufJavaWkt.int32Value(null));
    assertNull(ProtobufJavaWkt.toUint32(null));
    assertNull(ProtobufJavaWkt.uint32Value(null));
    assertNull(ProtobufJavaWkt.toInt64(null));
    assertNull(ProtobufJavaWkt.int64Value(null));
    assertNull(ProtobufJavaWkt.toUint64(null));
    assertNull(ProtobufJavaWkt.uint64Value(null));
    assertNull(ProtobufJavaWkt.toFloat(null));
    assertNull(ProtobufJavaWkt.floatValue(null));
    assertNull(ProtobufJavaWkt.toDouble(null));
    assertNull(ProtobufJavaWkt.doubleValue(null));
    assertNull(ProtobufJavaWkt.toStringValue(null));
    assertNull(ProtobufJavaWkt.stringValue(null));
    assertNull(ProtobufJavaWkt.toBytes(null));
    assertNull(ProtobufJavaWkt.bytesValue(null));
  }
}
