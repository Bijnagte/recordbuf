package bijnagte.recordbuf.fixture.repeated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.repeated.rec.Sample.SampleCodec;
import bijnagte.recordbuf.fixture.repeated.rec.SampleAvroConverter;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated codec against the protobuf-java reference for the
 * repeated length-delimited scalars: {@code repeated string} and {@code repeated bytes} are never
 * packed, so each element (empty ones included) is its own tagged field. The Avro twin converts
 * them per element.
 */
class RepeatedTest {

  private static final List<String> A = List.of("one", "", "dré", "x".repeat(200));
  private static final List<byte[]> B =
      List.of(new byte[] {1, 2, 3}, new byte[0], new byte[] {(byte) 0xff, 0, (byte) 0x80});

  private static bijnagte.recordbuf.fixture.repeated.rec.Sample sample() {
    return bijnagte.recordbuf.fixture.repeated.rec.Sample.builder()
        .a(A)
        .b(B.stream().map(Bytes::of).toList())
        .build();
  }

  private static Sample reference() {
    return Sample.newBuilder()
        .addAllA(A)
        .addAllB(B.stream().map(ByteString::copyFrom).toList())
        .build();
  }

  @Test
  void byteEqual() throws Exception {
    assertArrayEquals(reference().toByteArray(), SampleCodec.encode(sample()));
    assertArrayEquals(
        new byte[0],
        SampleCodec.encode(bijnagte.recordbuf.fixture.repeated.rec.Sample.builder().build()),
        "empty repeated fields are absent from the wire");
  }

  @Test
  void decodesTheReferenceBytes() throws Exception {
    assertEquals(sample(), SampleCodec.decode(reference().toByteArray()));
  }

  @Test
  void referenceDecodesOurBytes() throws Exception {
    assertEquals(reference(), Sample.parseFrom(SampleCodec.encode(sample())));
  }

  @Test
  void roundTripsThroughTheAvroConverter() {
    var original = sample();

    var avro = SampleAvroConverter.fromRecord(original);

    assertEquals(original, SampleAvroConverter.toRecord(avro));
  }
}
