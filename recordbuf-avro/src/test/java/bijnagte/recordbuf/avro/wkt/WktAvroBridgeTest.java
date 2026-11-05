package bijnagte.recordbuf.avro.wkt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
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
    assertEquals(Type.LONG, s.getType());
    assertEquals("timestamp-micros", s.getLogicalType().getName());
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
    assertEquals(Type.RECORD, s.getType());
    assertEquals(Type.LONG, s.getField("seconds").schema().getType());
    assertEquals(Type.INT, s.getField("nanos").schema().getType());
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
    assertEquals(Type.UNION, s.getType());
    assertEquals(Type.NULL, s.getTypes().get(0).getType());
    assertEquals(Type.STRING, s.getTypes().get(1).getType());
  }

  @Test
  void fieldMaskEmptyAndNullRoundTrip() {
    assertNull(WktAvroBridge.fieldMaskToAvro(java.util.List.of()));
    assertNull(WktAvroBridge.fieldMaskToAvro(null));
    assertTrue(WktAvroBridge.fieldMaskFromAvro(null).isEmpty());
    assertTrue(WktAvroBridge.fieldMaskFromAvro("").isEmpty());
  }

  @Test
  void nullValueIsNullableEnumUnion() {
    Schema s = frag(WktAvroBridge.NULL_VALUE);
    assertEquals(Type.UNION, s.getType());
    assertEquals(Type.NULL, s.getTypes().get(0).getType());
    assertEquals(Type.ENUM, s.getTypes().get(1).getType());
    assertEquals(3, s.getTypes().get(1).getEnumSymbols().size());
  }

  @Test
  void emptyAndWrapperFragments() {
    assertEquals(Type.RECORD, frag(WktAvroBridge.EMPTY).getType());
    Schema i32 = frag(WktAvroBridge.INT32_VALUE);
    assertEquals(Type.UNION, i32.getType());
    assertEquals(Type.INT, i32.getTypes().get(1).getType());
    Schema d = frag(WktAvroBridge.DOUBLE_VALUE);
    assertEquals(Type.DOUBLE, d.getTypes().get(1).getType());
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
}
