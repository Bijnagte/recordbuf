package bijnagte.recordbuf.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct mechanical-contract tests for the extracted framing helpers — the oracle these helpers
 * keep byte-stable independent of the generated codec. The interop byte-equality suite (fixtures)
 * proves the *generated* codecs stay identical; this pin is the helper layer itself.
 */
class RuntimeFramingTest {

  /**
   * Encode scaffolding: a field written through {@link Encoder} is read back through {@link
   * Decoder} byte-for-byte, with the tag/skip primitives intact.
   */
  @Test
  void encodeDecodeRoundTripsRepresentativeValue() throws Exception {
    Encoder enc = Encoder.start();
    enc.out().writeInt32(1, 42);
    byte[] encoded = enc.finish();

    CodedInputStream in = Decoder.start(encoded).in();
    int tag = Wire.readTag(in);
    assertEquals(1, Wire.tagFieldNumber(tag));
    assertEquals(42, in.readInt32());
    assertTrue(in.isAtEnd());
  }

  /**
   * Packed buffer/drain: a populated no-tag sequence frames and drains to the exact values; an
   * empty sequence frames a 0-length group that drains nothing.
   */
  @Test
  void packedBufferFrameAndDrainRoundTrips() throws Exception {
    Encoder body = Encoder.start();
    var o = body.out();
    for (int x : new int[] {1, 2, 3}) o.writeInt32NoTag(x);
    byte[] framed = frame(7, body.finish());

    CodedInputStream in = Decoder.start(framed).in();
    Wire.readTag(in);
    int lim = in.pushLimit(in.readRawVarint32());
    List<Integer> got = new ArrayList<>();
    while (in.getBytesUntilLimit() > 0) got.add(in.readInt32());
    in.popLimit(lim);
    assertEquals(List.of(1, 2, 3), got);

    // An empty sequence drains to nothing.
    byte[] emptyFramed = frame(7, Encoder.start().finish());
    CodedInputStream e = Decoder.start(emptyFramed).in();
    Wire.readTag(e);
    int elim = e.pushLimit(e.readRawVarint32());
    int drained = 0;
    while (e.getBytesUntilLimit() > 0) {
      e.readInt32();
      drained++;
    }
    e.popLimit(elim);
    assertEquals(0, drained);
  }

  /** Frame a tight value body as one length-delimited group (tag + raw bytes). */
  private static byte[] frame(int num, byte[] body) throws IOException {
    Encoder enc = Encoder.start();
    enc.out().writeByteArray(num, body);
    return enc.finish();
  }

  /**
   * The per-wire default-value table returns the same literal the inline switch emitted per wire
   * scalar.
   */
  @Test
  void codecDefaultsMatchLiteralTable() {
    assertEquals(0, CodecDefaults.int32());
    assertEquals(0L, CodecDefaults.int64());
    assertEquals(0.0f, CodecDefaults.float_());
    assertEquals(0.0, CodecDefaults.double_());
    assertFalse(CodecDefaults.bool());
    assertEquals("", CodecDefaults.string());
    assertArrayEquals(new byte[0], CodecDefaults.bytes().toByteArray());
  }
}
