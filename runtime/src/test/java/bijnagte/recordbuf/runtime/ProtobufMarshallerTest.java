package bijnagte.recordbuf.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Mechanical-contract tests for the gRPC-agnostic core marshaller — the write/ read plumbing that a
 * record type's generated {@code toBytes}/ {@code fromBytes} hook feeds. The interop byte-equality
 * suite (fixtures) proves the *generated* codecs stay identical; this pins the marshaller itself.
 */
class ProtobufMarshallerTest {

  /** A tiny hand-written record-bus message: a single 32-bit value. */
  record Point(int x, int y) implements ProtobufMessage {
    @Override
    public byte[] toByteArray() {
      // Not real protobuf framing -- just a stable byte signature the decoder
      // round-trips, so the marshaller's plumbing can be tested in isolation.
      byte[] out = new byte[8];
      out[0] = (byte) (x >> 24);
      out[1] = (byte) (x >> 16);
      out[2] = (byte) (x >> 8);
      out[3] = (byte) x;
      out[4] = (byte) (y >> 24);
      out[5] = (byte) (y >> 16);
      out[6] = (byte) (y >> 8);
      out[7] = (byte) y;
      return out;
    }

    static Point fromBytes(byte[] in) {
      int x = (in[0] & 0xff) << 24 | (in[1] & 0xff) << 16 | (in[2] & 0xff) << 8 | (in[3] & 0xff);
      int y = (in[4] & 0xff) << 24 | (in[5] & 0xff) << 16 | (in[6] & 0xff) << 8 | (in[7] & 0xff);
      return new Point(x, y);
    }
  }

  /** A write through the marshaller then a read returns the same record value. */
  @Test
  void writeThenReadRoundTrips() throws Exception {
    Point value = new Point(0x01020304, 0x05060708);
    ProtobufMarshaller<Point> m = ProtobufMarshaller.of(Point::fromBytes);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    m.write(value, out);
    Point back = m.read(new ByteArrayInputStream(out.toByteArray()));

    assertEquals(value, back);
  }

  /** A marshaller's written bytes are identical to the record's own toBytes. */
  @Test
  void writtenBytesMatchMessageToByteArray() throws Exception {
    Point value = new Point(42, -7);
    ProtobufMarshaller<Point> m = ProtobufMarshaller.of(Point::fromBytes);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    m.write(value, out);

    assertArrayEquals(value.toByteArray(), out.toByteArray());
  }

  /** The read half is reached by the supplied decoder; it is non-null by contract. */
  @Test
  void readDecodesThroughSuppliedFunction() throws Exception {
    Point value = new Point(1, 2);
    ProtobufMarshaller<Point> m = ProtobufMarshaller.of(Point::fromBytes);

    Point back = m.read(new ByteArrayInputStream(value.toByteArray()));
    assertEquals(value, back);
  }

  /** A decoder that declares IOException, as a generated fromBytes does, is accepted. */
  @Test
  void decoderMayThrowIoException() {
    ProtobufMarshaller<Point> m =
        ProtobufMarshaller.of(bytes -> {
          throw new IOException("bad bytes");
        });

    IOException e =
        assertThrows(IOException.class, () -> m.read(new ByteArrayInputStream(new byte[0])));
    assertEquals("bad bytes", e.getMessage());
  }
}
