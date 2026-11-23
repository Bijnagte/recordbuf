package bijnagte.recordbuf.runtime.wkt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The WKT inner-frame codec: round-trip + byte-stability for the structured ({@code Timestamp},
 * {@code Duration}), the normalized {@code FieldMask}, the {@code Empty} marker, and each scalar
 * wrapper ({@code *Value}).
 */
class WktTest {

  @Test
  void timestampRoundTripsAcrossWholeAndSubSecond() throws Exception {
    Instant v = Instant.ofEpochSecond(1_700_000_000L, 500_000_000L);
    Instant back = Wkt.parseTimestamp(Wkt.timestamp(v));
    assertEquals(v, back);
  }

  @Test
  void timestampRoundTripsSubSecond() throws Exception {
    // the wire (seconds + int32 nanos) preserves sub-second; (avro's timestamp-micros is the lossy
    // path, not the wire codec).
    Instant sub = Instant.ofEpochSecond(0, 500_000_000);
    Instant back = Wkt.parseTimestamp(Wkt.timestamp(sub));
    assertEquals(sub, back);
  }

  @Test
  void timestampZeroEncodesToEmptyFrame() throws Exception {
    assertArrayEquals(new byte[0], Wkt.timestamp(Instant.EPOCH));
  }

  @Test
  void durationRoundTripsSubSecondAndBeyond() throws Exception {
    for (Duration d :
        List.of(
            Duration.ofSeconds(17),
            Duration.ofMillis(250),
            Duration.ZERO,
            Duration.ofMillis(-1500),
            Duration.ofNanos(-1))) {
      assertEquals(d, Wkt.parseDuration(Wkt.duration(d)));
    }
  }

  @Test
  void negativeDurationEncodesSameSignedSecondsAndNanos() throws Exception {
    // proto: seconds and nanos share a sign; -1.5s is {-1, -500000000}, byte-equal to
    // protobuf-java.
    assertArrayEquals(
        com.google.protobuf.Duration.newBuilder()
            .setSeconds(-1)
            .setNanos(-500_000_000)
            .build()
            .toByteArray(),
        Wkt.duration(Duration.ofMillis(-1500)));
    assertArrayEquals(
        com.google.protobuf.Duration.newBuilder().setNanos(-1).build().toByteArray(),
        Wkt.duration(Duration.ofNanos(-1)));
  }

  @Test
  void fieldMaskNormalizesAndRoundTrips() throws Exception {
    FieldMask m = FieldMask.of(List.of(".A.B", "C.c", "c.c", "...", "", "d"));
    assertEquals(List.of("a.b", "c.c", "d"), m.paths());
    assertEquals(m, Wkt.parseFieldMask(Wkt.fieldMask(m)));
  }

  @Test
  void fieldMaskEmptyIsStable() throws Exception {
    FieldMask empty = FieldMask.empty();
    assertArrayEquals(new byte[0], Wkt.fieldMask(empty));
    assertEquals(empty, Wkt.parseFieldMask(new byte[0]));
  }

  @Test
  void scalarWrappersRoundTrip() throws Exception {
    assertEquals(42, Wkt.parse(Wkt.Wrap.INT32, Wkt.wrap(Wkt.Wrap.INT32, 42)));
    assertEquals(1L, Wkt.parse(Wkt.Wrap.UINT64, Wkt.wrap(Wkt.Wrap.UINT64, 1L)));
    assertEquals(3.5f, Wkt.parse(Wkt.Wrap.FLOAT, Wkt.wrap(Wkt.Wrap.FLOAT, 3.5f)));
    assertEquals(2.5d, Wkt.parse(Wkt.Wrap.DOUBLE, Wkt.wrap(Wkt.Wrap.DOUBLE, 2.5d)));
    assertEquals(true, Wkt.parse(Wkt.Wrap.BOOL, Wkt.wrap(Wkt.Wrap.BOOL, true)));
    assertEquals("hi", Wkt.parse(Wkt.Wrap.STRING, Wkt.wrap(Wkt.Wrap.STRING, "hi")));
    Bytes b = Bytes.of(new byte[] {1, -1, 0});
    assertEquals(b, Wkt.parse(Wkt.Wrap.BYTES, Wkt.wrap(Wkt.Wrap.BYTES, b)));
  }

  @Test
  void scalarWrapperPresentButDefaultDecodesToZero() {
    // A present-but-empty wrapper frame decodes to the proto3 default, not null.
    assertEquals(0, Wkt.parse(Wkt.Wrap.INT32, new byte[0]));
    assertEquals(false, Wkt.parse(Wkt.Wrap.BOOL, new byte[0]));
    assertEquals("", Wkt.parse(Wkt.Wrap.STRING, new byte[0]));
    assertEquals(Bytes.empty(), Wkt.parse(Wkt.Wrap.BYTES, new byte[0]));
  }

  @Test
  void distinctValuesAreDistinct() throws Exception {
    assertNotEquals(Wkt.wrap(Wkt.Wrap.INT32, 1), Wkt.wrap(Wkt.Wrap.INT32, 2));
  }
}
