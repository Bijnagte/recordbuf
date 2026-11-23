package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.fixed.rec.Sample;
import bijnagte.recordbuf.fixture.jacksonformat.rec.Probe;
import bijnagte.recordbuf.runtime.UnknownFields;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON is not held to the proto3 JSON mapping, but a record round-tripped through it must come back
 * at full Java resolution: every bit of every scalar (the unsigned types included, held in their
 * signed Java carriers), the sign of zero, the non-finite values, and temporals at nanosecond
 * resolution across their whole Java range in both renderings.
 */
class JacksonResolutionTest {

  private static final JsonMapper M = new JsonMapper();

  private static <T> T roundTrip(T value, Class<T> type) {
    return M.readValue(M.writeValueAsString(value), type);
  }

  private static Sample scalars(int i32, long i64, double dbl, float flt) {
    return Sample.builder()
        .a(i32)
        .b(i32)
        .c(i64)
        .d(i64)
        .e(i32)
        .f(i64)
        .g(i32)
        .h(i64)
        .i(dbl)
        .j(flt)
        .k(i32)
        .l(i64)
        .build();
  }

  @Test
  void integerExtremesKeepEveryBit() {
    // -1 is the uint32 / uint64 / fixed maximum in its signed Java carrier.
    for (int i32 : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0}) {
      for (long i64 : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1L, (1L << 53) + 1}) {
        Sample s = scalars(i32, i64, 0, 0);
        assertEquals(s, roundTrip(s, Sample.class));
      }
    }
  }

  @Test
  void finiteFloatingPointKeepsEveryBit() {
    double[] doubles = {
      0.1 + 0.2, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MIN_NORMAL, -0.0, 1e-300, Math.PI
    };
    float[] floats = {
      0.1f, Float.MIN_VALUE, Float.MAX_VALUE, -0.0f, Math.nextUp(16_777_216f), (float) Math.E
    };
    for (double dbl : doubles) {
      for (float flt : floats) {
        Sample s = scalars(0, 0, dbl, flt);
        // Record equality compares double/float components bit-wise (Double.compare), so -0.0
        // must come back as -0.0.
        assertEquals(s, roundTrip(s, Sample.class), dbl + " / " + flt);
      }
    }
  }

  @Test
  void nonFiniteFloatingPointRoundTrips() {
    for (double dbl :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      Sample s = scalars(0, 0, dbl, (float) dbl);
      assertEquals(s, roundTrip(s, Sample.class), Double.toString(dbl));
    }
  }

  @Test
  void temporalsKeepNanosecondsInBothRenderings() {
    // at: ISO string; span: the canonical {seconds, nanos} object; whens: ISO strings.
    Probe p =
        new Probe(
            Instant.ofEpochSecond(-62_135_596_800L, 1),
            Duration.ofSeconds(-1, 999_999_999),
            List.of(Instant.MIN, Instant.MAX, Instant.ofEpochSecond(0, 1)),
            null,
            UnknownFields.empty());

    assertEquals(p, roundTrip(p, Probe.class));
  }

  @Test
  void isoDurationsKeepTheirFullRange() {
    for (Duration span :
        List.of(
            Duration.ofSeconds(Long.MIN_VALUE),
            Duration.ofSeconds(Long.MAX_VALUE, 999_999_999),
            Duration.ofNanos(-1))) {
      var sample = bijnagte.recordbuf.fixture.wellknown.rec.Sample.builder().span(span).build();

      assertEquals(
          sample, roundTrip(sample, bijnagte.recordbuf.fixture.wellknown.rec.Sample.class));
    }
  }

  /** A missing or null scalar is its proto3 default, as on the wire. */
  @Test
  void missingAndNullPrimitivesReadAsTheirDefaults() {
    Sample defaults = Sample.builder().build();

    assertEquals(defaults, M.readValue("{}", Sample.class));
    assertEquals(defaults, M.readValue("{\"c\":null,\"i\":null,\"j\":null}", Sample.class));
  }

  /** The canonical {seconds, nanos} Duration renders at any length, not just under ~292 years. */
  @Test
  void canonicalDurationsKeepTheirFullRange() {
    for (Duration span :
        List.of(
            Duration.ofSeconds(Long.MIN_VALUE),
            Duration.ofSeconds(Long.MAX_VALUE, 999_999_999),
            Duration.ofDays(365 * 1000L),
            Duration.ofMillis(-1500))) {
      Probe p = new Probe(Instant.EPOCH, span, List.of(), null, UnknownFields.empty());

      assertEquals(p, roundTrip(p, Probe.class), span.toString());
    }
  }
}
