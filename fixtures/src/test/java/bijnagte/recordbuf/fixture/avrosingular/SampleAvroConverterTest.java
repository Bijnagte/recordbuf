package bijnagte.recordbuf.fixture.avrosingular;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.avrosingular.rec.Sample;
import bijnagte.recordbuf.fixture.avrosingular.rec.SampleAvroConverter;
import bijnagte.recordbuf.runtime.Bytes;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code SampleAvroConverter} for the {@code avrosingular} probe, driven directly
 * with Avro records whose singular {@code BytesValue} and {@code Duration} hold values the golden
 * matrix does not reach: a {@code ByteBuffer} whose position is past zero, and a negative duration
 * with a fraction.
 */
class SampleAvroConverterTest {

  @Test
  void toRecord_readsBytesFromThePositionAndKeepsTheNegativeDuration() {
    // The backing array's first byte lies before the position, so only {1, 2, 3} is the value.
    ByteBuffer blob = ByteBuffer.wrap(new byte[] {9, 1, 2, 3}).position(1);
    SampleAvro avro = new SampleAvro();
    avro.setBlob(blob);
    avro.setSpan(new bijnagte.recordbuf.avro.wkt.Duration(-2L, 500_000_000));

    Sample record = SampleAvroConverter.toRecord(avro);

    assertArrayEquals(new byte[] {1, 2, 3}, record.blob().toByteArray());
    assertEquals(Duration.ofMillis(-1_500), record.span());
  }

  @Test
  void fromRecord_writesTheFloorPairAndTheBytesOfTheValue() {
    Sample record =
        Sample.builder()
            .span(Duration.ofMillis(-1_500))
            .blob(Bytes.of(new byte[] {1, 2, 3}))
            .build();

    SampleAvro avro = SampleAvroConverter.fromRecord(record);

    // -1.5s as seconds plus a non-negative fraction: -2 seconds and +0.5 seconds.
    assertEquals(-2L, avro.getSpan().getSeconds());
    assertEquals(500_000_000, avro.getSpan().getNanos());
    assertArrayEquals(new byte[] {1, 2, 3}, remaining(avro.getBlob()));
  }

  /** The bytes between a buffer's position and its limit, leaving the buffer's position as is. */
  private static byte[] remaining(ByteBuffer buffer) {
    byte[] out = new byte[buffer.remaining()];
    buffer.duplicate().get(out);
    return out;
  }
}
