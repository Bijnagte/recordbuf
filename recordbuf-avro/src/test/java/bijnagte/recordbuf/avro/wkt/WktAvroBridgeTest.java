package bijnagte.recordbuf.avro.wkt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

/**
 * U3: the WKT bridge — schema fragments (native logical types per KTD4) + value conversions,
 * including the Timestamp micros boundary (AE1) and the structured-Duration >1 s round-trip (AE2).
 */
class WktAvroBridgeTest {

  private static Schema frag(String fqn) {
    Schema s = WktAvroBridge.fragment(fqn);
    if (s == null) throw new AssertionError(fqn + " should have a fragment");
    return s;
  }

  @Test
  void timestampIsTimestampMicrosLogicalType() {
    Schema s = frag(WktAvroBridge.TIMESTAMP);
    assertEquals(Schema.Type.LONG, s.getType());
    assertEquals("timestamp-micros", s.getLogicalType().getName());
  }

  /** R11: the dynamic WKTs are explicitly out of v1 scope and are flagged unrecognized. */
  @Test
  void dynamicWktsAreFlaggedUnrecognizedDynamic() {
    assertTrue(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Struct"));
    assertTrue(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Value"));
    assertTrue(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Any"));
    assertTrue(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.ListValue"));
    // A plain WKT that IS mapped (Timestamp) is recognized, NOT unrecognized-dynamic.
    assertTrue(WktAvroBridge.isRecognized("google.protobuf.Timestamp"));
    assertFalse(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Timestamp"));
  }

  /** AE1: a Timestamp round-trips through epoch micros across whole-second + sub-second. */
  @Test
  void timestampRoundTripsMicrosAcrossSecondsAndSubsecond() {
    long micros = WktAvroBridge.timestampToMicros(1, 500_000_000); // 1.5 s = 1_500_000 micros
    assertEquals(1_500_000L, micros);
    long[] back = WktAvroBridge.timestampFromMicros(micros);
    assertEquals(1L, back[0]);
    assertEquals(500_000_000L, back[1]);
  }

  @Test
  void timestampRoundTripsSubsecondOnly() {
    long[] back = WktAvroBridge.timestampFromMicros(WktAvroBridge.timestampToMicros(0, 250_000));
    assertEquals(0L, back[0]);
    assertEquals(250_000L, back[1]);
  }

  /** AE1 noted: sub-microsecond nanos are lost (timestamp-micros is microsecond-granular). */
  @Test
  void timestampMicrosLossyPastMicrosecond() {
    assertEquals(0L, WktAvroBridge.timestampToMicros(0, 500));
  }

  @Test
  void durationIsStructuredRecordWithSecondsAndNanos() {
    Schema s = frag(WktAvroBridge.DURATION);
    assertEquals(Schema.Type.RECORD, s.getType());
    assertEquals(Schema.Type.LONG, s.getField("seconds").schema().getType());
    assertEquals(Schema.Type.INT, s.getField("nanos").schema().getType());
  }

  /** AE2: a Duration whose nanos exceed 1 s survive the structured pair exactly. */
  @Test
  void durationRoundTripsNanosBeyondOneSecond() {
    long[] back = WktAvroBridge.durationFromValue(WktAvroBridge.durationToValue(3, 1_500_000_000));
    assertEquals(3L, back[0]);
    assertEquals(1_500_000_000L, back[1]);
  }

  @Test
  void fieldMaskIsNullableStringUnion() {
    Schema s = frag(WktAvroBridge.FIELD_MASK);
    assertEquals(Schema.Type.UNION, s.getType());
    assertEquals(Schema.Type.NULL, s.getTypes().get(0).getType());
    assertEquals(Schema.Type.STRING, s.getTypes().get(1).getType());
  }

  @Test
  void fieldMaskEmptyAndNullRoundTrip() {
    assertNull(WktAvroBridge.fieldMaskToAvro(List.of()));
    assertNull(WktAvroBridge.fieldMaskToAvro(null));
    assertTrue(WktAvroBridge.fieldMaskFromAvro(null).isEmpty());
    assertTrue(WktAvroBridge.fieldMaskFromAvro("").isEmpty());
  }

  @Test
  void nullValueIsNullableEnumUnion() {
    Schema s = frag(WktAvroBridge.NULL_VALUE);
    assertEquals(Schema.Type.UNION, s.getType());
    assertEquals(Schema.Type.NULL, s.getTypes().get(0).getType());
    assertEquals(Schema.Type.ENUM, s.getTypes().get(1).getType());
    assertEquals(3, s.getTypes().get(1).getEnumSymbols().size());
  }

  @Test
  void emptyAndWrapperFragments() {
    assertEquals(Schema.Type.RECORD, frag(WktAvroBridge.EMPTY).getType());
    Schema i32 = frag(WktAvroBridge.INT32_VALUE);
    assertEquals(Schema.Type.UNION, i32.getType());
    assertEquals(Schema.Type.INT, i32.getTypes().get(1).getType());
    Schema d = frag(WktAvroBridge.DOUBLE_VALUE);
    assertEquals(Schema.Type.DOUBLE, d.getTypes().get(1).getType());
  }

  @Test
  void recognizedVsUnrecognized() {
    assertTrue(WktAvroBridge.isRecognized(WktAvroBridge.TIMESTAMP));
    assertTrue(WktAvroBridge.isRecognized("/google.protobuf.Duration")); // leading slash tolerated
    assertFalse(WktAvroBridge.isRecognized("google.protobuf.DurationX"));
    assertFalse(WktAvroBridge.isRecognized("com.example.NotAWkt"));
    assertTrue(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Struct"));
    assertTrue(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Any"));
    assertFalse(WktAvroBridge.isUnrecognizedDynamic("google.protobuf.Timestamp"));
  }

  @Test
  void instantMicrosRoundTripAtMicrosecondResolution() {
    for (Instant i :
        List.of(
            Instant.EPOCH,
            Instant.ofEpochSecond(1_763_614_896L, 789_123_000),
            Instant.ofEpochSecond(-1, 999_999_000))) {
      assertEquals(i, WktAvroBridge.instantFromMicros(WktAvroBridge.instantToMicros(i)));
    }
    // Below the microsecond the conversion floors, before and after the epoch.
    assertEquals(
        Instant.ofEpochSecond(-1, 999_999_000),
        WktAvroBridge.instantFromMicros(
            WktAvroBridge.instantToMicros(Instant.ofEpochSecond(-1, 999_999_999))));
  }

  @Test
  void bytesCopiesTheRemainingViewWithoutMovingIt() {
    ByteBuffer view = ByteBuffer.wrap(new byte[] {9, 1, 2, 3, 9}, 1, 3).slice();
    view.position(1);

    assertArrayEquals(new byte[] {2, 3}, WktAvroBridge.bytes(view));
    assertEquals(1, view.position());
  }
}
