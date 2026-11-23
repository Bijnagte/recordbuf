package bijnagte.recordbuf.fixture.conformance;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Builders for arbitrary protobuf wire bytes, ported from the helpers at the top of the official
 * conformance suite ({@code binary_json_conformance_suite.cc} and {@code binary_wireformat.h} at
 * protobuf {@code v36.2}): {@code varint}, {@code longvarint}, {@code delim}, {@code u32}, {@code
 * u64}, {@code flt}, {@code dbl}, {@code zz32}, {@code zz64}, {@code tag}, {@code len}.
 *
 * <p>They build payloads byte by byte, rather than through a message API, so a scenario can carry
 * input a serializer never writes: over-long varints, out-of-range values, unordered map entries.
 * An {@code int} argument to {@link #varint(long)} is sign-extended, as the C++ helper's {@code
 * static_cast<uint64_t>} does; pass a {@code long} to zero-extend an unsigned 32-bit value.
 */
final class WireBytes {

  /** Wire type of a varint-encoded field. */
  static final int WIRETYPE_VARINT = 0;

  /** Wire type of a little-endian 8-byte field. */
  static final int WIRETYPE_FIXED64 = 1;

  /** Wire type of a length-prefixed field. */
  static final int WIRETYPE_LENGTH_DELIMITED = 2;

  /** Wire type of a little-endian 4-byte field. */
  static final int WIRETYPE_FIXED32 = 5;

  private static final int VARINT_PAYLOAD_BITS = 7;
  private static final int VARINT_CONTINUATION = 0x80;
  private static final int VARINT_PAYLOAD_MASK = 0x7f;
  private static final long UNSIGNED_INT_MASK = 0xFFFFFFFFL;
  private static final int MAX_VARINT_BYTES = 10;

  private WireBytes() {}

  /** Returns {@code x} as a minimal varint; the 64-bit pattern of {@code x} is encoded. */
  static byte[] varint(long x) {
    return longvarint(x, 0);
  }

  /**
   * Returns {@code x} as a valid varint that is {@code extra} bytes longer than it needs to be:
   * every byte but the last carries the continuation bit, and the padding is zero payload.
   *
   * @throws IllegalArgumentException if the result would be longer than ten bytes
   */
  static byte[] longvarint(long x, int extra) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    long rest = x;
    while ((rest & ~((long) VARINT_PAYLOAD_MASK)) != 0) {
      out.write((int) (rest & VARINT_PAYLOAD_MASK) | VARINT_CONTINUATION);
      rest >>>= VARINT_PAYLOAD_BITS;
    }
    int last = (int) rest;
    if (extra == 0) {
      out.write(last);
    } else {
      out.write(last | VARINT_CONTINUATION);
      for (int i = 1; i < extra; i++) {
        out.write(VARINT_CONTINUATION);
      }
      out.write(0);
    }
    if (out.size() > MAX_VARINT_BYTES) {
      throw new IllegalArgumentException("a varint longer than 10 bytes is not valid");
    }
    return out.toByteArray();
  }

  /** Returns {@code content} behind its length varint. */
  static byte[] delim(byte[] content) {
    return cat(varint(content.length), content);
  }

  /** Returns the UTF-8 bytes of {@code text} behind their length varint. */
  static byte[] delim(String text) {
    return delim(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Returns {@code x} as 4 little-endian bytes. */
  static byte[] u32(int x) {
    return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(x).array();
  }

  /** Returns {@code x} as 8 little-endian bytes. */
  static byte[] u64(long x) {
    return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(x).array();
  }

  /** Returns {@code f} as 4 little-endian IEEE-754 bytes. */
  static byte[] flt(float f) {
    return u32(Float.floatToRawIntBits(f));
  }

  /** Returns {@code d} as 8 little-endian IEEE-754 bytes. */
  static byte[] dbl(double d) {
    return u64(Double.doubleToRawLongBits(d));
  }

  /** Returns {@code x} zig-zag encoded as a 32-bit value, then as a varint. */
  static byte[] zz32(int x) {
    return varint(((x << 1) ^ (x >> (Integer.SIZE - 1))) & UNSIGNED_INT_MASK);
  }

  /** Returns {@code x} zig-zag encoded as a 64-bit value, then as a varint. */
  static byte[] zz64(long x) {
    return varint((x << 1) ^ (x >> (Long.SIZE - 1)));
  }

  /** Returns the tag varint of field {@code fieldNumber} with {@code wireType}. */
  static byte[] tag(int fieldNumber, int wireType) {
    return varint(((long) fieldNumber << 3) | wireType);
  }

  /** Returns the whole length-delimited field: tag, length and {@code content}. */
  static byte[] len(int fieldNumber, byte[] content) {
    return cat(tag(fieldNumber, WIRETYPE_LENGTH_DELIMITED), delim(content));
  }

  /** Returns the concatenation of {@code parts}. */
  static byte[] cat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
