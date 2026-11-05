package bijnagte.recordbuf.avro.wkt;

/**
 * The structured {@code {seconds: long, nanos: int}} representation of a proto {@code Duration}
 * (KTD4, R9): a 2-field value that is the <em>same shape</em> as the record's nested {@code
 * Duration} fields ({@code seconds}, {@code nanos}), so the {@code record ↔ Avro} conversion
 * preserves full nanos — unlike Avro's micros-only logical {@code duration}, which would lose
 * sub-second precision past one second (AE2: a Duration whose nanos exceed 1 s round-trips
 * exactly).
 */
public final class DurationValue {
  private final long seconds;
  private final int nanos;

  public DurationValue(long seconds, int nanos) {
    this.seconds = seconds;
    this.nanos = nanos;
  }

  public long seconds() {
    return seconds;
  }

  public int nanos() {
    return nanos;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof DurationValue d && seconds == d.seconds && nanos == d.nanos;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(seconds) * 31 + Integer.hashCode(nanos);
  }

  @Override
  public String toString() {
    return "DurationValue(seconds=" + seconds + ", nanos=" + nanos + ")";
  }
}
