package bijnagte.recordbuf.runtime.wkt;

import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Decoder;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.Encoder;
import bijnagte.recordbuf.runtime.FieldMask;
import bijnagte.recordbuf.runtime.Wire;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The canonical WKT inner-frame encode/decode a generated codec delegates to. A WKT field is, on
 * the wire, an ordinary length-delimited nested message; this helper owns the per-WKT framing so a
 * generated codec writes one line ({@code out.writeByteArray(num, Wkt.timestamp(v.ts()))}) instead
 * of re-spelling the nested tag/length framing.
 *
 * <p>Each {@code wrap*}/{@code wrapScalar} produces the inner message content (the bytes that go
 * <em> inside</em> the length-delimited frame the codec writes); each {@code parse*} recovers the
 * native value from that inner content. The generated codec still drives the outer tag + outer
 * frame, so the WKT stays byte-identical to protobuf-java's encoding of the same reference.
 *
 * <p>Structured WKTs ({@code Timestamp}/{@code Duration}) normalize to {@link Instant} / {@link
 * java.time.Duration}; a {@code FieldMask} normalizes to {@link FieldMask}; an {@code Empty} is the
 * singleton {@link Empty#INSTANCE}; and each scalar wrapper ({@code *Value}) maps to the primitive
 * it wraps, written on the wire exactly as that wrapper is written.
 */
public final class Wkt {

  private Wkt() {}

  /**
   * The wire-scalar kind a scalar wrapper ({@code *Value}) encodes its value as, inside its frame.
   * Mirrors the proto primitive whose wrapper it is, collapsed to the distinct wire encodings.
   */
  public enum Wrap {
    INT32,
    UINT32,
    SINT_32,
    FIXED32,
    INT64,
    UINT64,
    SINT_64,
    FIXED64,
    FLOAT,
    DOUBLE,
    BOOL,
    STRING,
    BYTES
  }

  // ---- Timestamp --------------------------------------------------------------

  /**
   * Encode a {@code google.protobuf.Timestamp} value ({@link Instant}) to its inner-frame content.
   */
  @SuppressWarnings("deprecation")
  public static byte[] timestamp(Instant v) throws IOException {
    Encoder e = Encoder.start();
    CodedOutputStream out = e.out();
    long seconds = v.getEpochSecond();
    int nanos = v.getNano();
    if (seconds != 0L) out.writeInt64(1, seconds);
    if (nanos != 0) out.writeInt32(2, nanos);
    return e.finish();
  }

  /**
   * Decode a {@code google.protobuf.Timestamp} value ({@link Instant}) from its inner-frame
   * content.
   */
  public static Instant parseTimestamp(byte[] content) {
    long seconds = 0;
    int nanos = 0;
    CodedInputStream in = Decoder.start(content).in();
    try {
      while (!in.isAtEnd()) {
        int tag = Wire.readTag(in);
        if (tag == 0) break;
        switch (Wire.tagFieldNumber(tag)) {
          case 1 -> seconds = in.readInt64();
          case 2 -> nanos = in.readInt32();
          default -> Wire.skip(in, tag);
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
    return Instant.ofEpochSecond(seconds, (long) nanos);
  }

  // ---- Duration ---------------------------------------------------------------

  /**
   * Encode a {@code google.protobuf.Duration} value ({@link java.time.Duration}) to its frame. The
   * proto shape requires {@code seconds} and {@code nanos} to share a sign ({@code -1.5s} is {@code
   * {-1, -500000000}}), while {@link Duration} holds the floor pair with non-negative nanos ({@code
   * {-2, 500000000}}): a negative duration with a fraction is shifted one second toward zero.
   */
  public static byte[] duration(Duration v) throws IOException {
    long seconds = v.getSeconds();
    int nanos = v.getNano();
    if (seconds < 0 && nanos > 0) {
      seconds += 1;
      nanos -= 1_000_000_000;
    }
    return secondsNanosFrame(seconds, nanos);
  }

  /**
   * Decode a {@code google.protobuf.Duration} value ({@link Duration}) from its inner-frame
   * content.
   */
  public static Duration parseDuration(byte[] content) {
    long[] pair = readSecondsNanos(content);
    return Duration.ofSeconds(pair[0], pair[1]);
  }

  // ---- FieldMask --------------------------------------------------------------

  /** Encode a {@code google.protobuf.FieldMask} ({@link FieldMask}) to its inner-frame content. */
  @SuppressWarnings("deprecation")
  public static byte[] fieldMask(FieldMask v) throws IOException {
    Encoder e = Encoder.start();
    CodedOutputStream out = e.out();
    for (String path : v.paths()) {
      if (!path.isEmpty()) out.writeString(1, path);
    }
    return e.finish();
  }

  /**
   * Decode a {@code google.protobuf.FieldMask} ({@link FieldMask}) from its inner-frame content.
   */
  public static FieldMask parseFieldMask(byte[] content) {
    List<String> paths = new ArrayList<>();
    CodedInputStream in = Decoder.start(content).in();
    try {
      while (!in.isAtEnd()) {
        int tag = Wire.readTag(in);
        if (tag == 0) break;
        switch (Wire.tagFieldNumber(tag)) {
          case 1 -> paths.add(in.readString());
          default -> Wire.skip(in, tag);
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
    return FieldMask.of(paths);
  }

  // ---- Empty ------------------------------------------------------------------

  /**
   * Encode a {@code google.protobuf.Empty} ({@link Empty}) to its inner-frame content — always 0
   * bytes.
   */
  public static byte[] empty(Empty v) {
    return new byte[0];
  }

  /** Decode a {@code google.protobuf.Empty} ({@link Empty}) from its inner-frame content. */
  public static Empty parseEmpty(byte[] content) {
    return Empty.INSTANCE;
  }

  // ---- scalar wrappers (*Value) ----------------------------------------------

  /**
   * Encode a scalar wrapper's value to its inner-frame content for the wrapper whose wire scalar is
   * {@code wire}. The boxed value is written to field 1 of the inner frame, default-omitted when it
   * equals the proto3 default (so a present-but-default wrapper encodes to an empty frame, as
   * protobuf-java does).
   */
  @SuppressWarnings("deprecation")
  public static byte[] wrap(Wrap wire, Object value) throws IOException {
    Encoder e = Encoder.start();
    CodedOutputStream out = e.out();
    switch (wire) {
      case INT32, UINT32 -> {
        int v = (int) value;
        if (v != 0) out.writeInt32(1, v);
      }
      case SINT_32 -> {
        int v = (int) value;
        if (v != 0) out.writeSInt32(1, v);
      }
      case FIXED32 -> {
        int v = (int) value;
        if (v != 0) out.writeFixed32(1, v);
      }
      case INT64, UINT64 -> {
        long v = (long) value;
        if (v != 0L) out.writeInt64(1, v);
      }
      case SINT_64 -> {
        long v = (long) value;
        if (v != 0L) out.writeSInt64(1, v);
      }
      case FIXED64 -> {
        long v = (long) value;
        if (v != 0L) out.writeFixed64(1, v);
      }
      case FLOAT -> {
        float v = (float) value;
        if (v != 0f) out.writeFloat(1, v);
      }
      case DOUBLE -> {
        double v = (double) value;
        if (v != 0d) out.writeDouble(1, v);
      }
      case BOOL -> {
        boolean v = (boolean) value;
        if (v) out.writeBool(1, true);
      }
      case STRING -> {
        String v = (String) value;
        if (!v.isEmpty()) out.writeString(1, v);
      }
      case BYTES -> {
        Bytes v = (Bytes) value;
        if (!v.isEmpty()) out.writeByteArray(1, v.toByteArray());
      }
    }
    return e.finish();
  }

  /**
   * Decode the value a scalar wrapper held from its inner-frame content for the wrapper whose wire
   * scalar is {@code wire}. Returns the boxed value it wraps (or its proto3 default — {@code
   * 0}/{@code false}/{@code ""}/empty {@code Bytes} — when the value field is absent, matching
   * protobuf-java's default for a present-but-empty wrapper).
   */
  public static Object parse(Wrap wire, byte[] content) {
    final CodedInputStream in = Decoder.start(content).in();
    try {
      while (!in.isAtEnd()) {
        int tag = Wire.readTag(in);
        if (tag == 0) break;
        if (Wire.tagFieldNumber(tag) == 1) {
          return switch (wire) {
            case INT32, UINT32, SINT_32, FIXED32 -> in.readInt32();
            case INT64, UINT64, SINT_64, FIXED64 -> in.readInt64();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BOOL -> in.readBool();
            case STRING -> in.readString();
            case BYTES -> Bytes.of(in.readByteArray());
          };
        }
        Wire.skip(in, tag);
      }
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
    return defaultValue(wire);
  }

  private static Object defaultValue(Wrap wire) {
    return switch (wire) {
      case INT32, UINT32, SINT_32, FIXED32 -> 0;
      case INT64, UINT64, SINT_64, FIXED64 -> 0L;
      case FLOAT -> 0f;
      case DOUBLE -> 0d;
      case BOOL -> false;
      case STRING -> "";
      case BYTES -> Bytes.empty();
      default -> throw new IllegalStateException("unrecognized WKT wrapper wire: " + wire);
    };
  }

  // ---- shared seconds/nanos framing ------------------------------------------

  @SuppressWarnings("deprecation")
  private static byte[] secondsNanosFrame(long seconds, int nanos) throws IOException {
    Encoder e = Encoder.start();
    CodedOutputStream out = e.out();
    if (seconds != 0L) out.writeInt64(1, seconds);
    if (nanos != 0) out.writeInt32(2, nanos);
    return e.finish();
  }

  private static long[] readSecondsNanos(byte[] content) {
    long[] pair = new long[2];
    CodedInputStream in = Decoder.start(content).in();
    try {
      while (!in.isAtEnd()) {
        int tag = Wire.readTag(in);
        if (tag == 0) break;
        switch (Wire.tagFieldNumber(tag)) {
          case 1 -> pair[0] = in.readInt64();
          case 2 -> pair[1] = in.readInt32();
          default -> Wire.skip(in, tag);
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
    return pair;
  }

  // ---- size computation (no allocation) ---------------------------------------

  /** Compute the serialized inner-frame size of a {@code Timestamp} without encoding. */
  public static int timestampSize(Instant v) {
    long seconds = v.getEpochSecond();
    int nanos = v.getNano();
    int size = 0;
    if (seconds != 0L) size += CodedOutputStream.computeInt64Size(1, seconds);
    if (nanos != 0) size += CodedOutputStream.computeInt32Size(2, nanos);
    return size;
  }

  /** Compute the serialized inner-frame size of a {@code Duration} without encoding. */
  public static int durationSize(Duration v) {
    long seconds = v.getSeconds();
    int nanos = v.getNano();
    if (seconds < 0 && nanos > 0) {
      seconds += 1;
      nanos -= 1_000_000_000;
    }
    int size = 0;
    if (seconds != 0L) size += CodedOutputStream.computeInt64Size(1, seconds);
    if (nanos != 0) size += CodedOutputStream.computeInt32Size(2, nanos);
    return size;
  }

  /** Compute the serialized inner-frame size of a {@code FieldMask} without encoding. */
  public static int fieldMaskSize(FieldMask v) {
    int size = 0;
    for (String path : v.paths()) {
      if (!path.isEmpty()) size += CodedOutputStream.computeStringSize(1, path);
    }
    return size;
  }

  /** Compute the serialized inner-frame size of an {@code Empty} — always 0. */
  public static int emptySize(Empty v) {
    return 0;
  }

  /** Compute the serialized inner-frame size of a scalar wrapper without encoding. */
  public static int wrapSize(Wrap wire, Object value) {
    return switch (wire) {
      case INT32, UINT32 -> {
        int v = (int) value;
        yield v != 0 ? CodedOutputStream.computeInt32Size(1, v) : 0;
      }
      case SINT_32 -> {
        int v = (int) value;
        yield v != 0 ? CodedOutputStream.computeSInt32Size(1, v) : 0;
      }
      case FIXED32 -> {
        int v = (int) value;
        yield v != 0 ? CodedOutputStream.computeFixed32Size(1, v) : 0;
      }
      case INT64, UINT64 -> {
        long v = (long) value;
        yield v != 0L ? CodedOutputStream.computeInt64Size(1, v) : 0;
      }
      case SINT_64 -> {
        long v = (long) value;
        yield v != 0L ? CodedOutputStream.computeSInt64Size(1, v) : 0;
      }
      case FIXED64 -> {
        long v = (long) value;
        yield v != 0L ? CodedOutputStream.computeFixed64Size(1, v) : 0;
      }
      case FLOAT -> {
        float v = (float) value;
        yield v != 0f ? CodedOutputStream.computeFloatSize(1, v) : 0;
      }
      case DOUBLE -> {
        double v = (double) value;
        yield v != 0d ? CodedOutputStream.computeDoubleSize(1, v) : 0;
      }
      case BOOL -> {
        boolean v = (boolean) value;
        yield v ? CodedOutputStream.computeBoolSize(1, v) : 0;
      }
      case STRING -> {
        String v = (String) value;
        yield !v.isEmpty() ? CodedOutputStream.computeStringSize(1, v) : 0;
      }
      case BYTES -> {
        Bytes v = (Bytes) value;
        yield !v.isEmpty() ? CodedOutputStream.computeByteArraySize(1, v.toByteArray()) : 0;
      }
    };
  }

  // ---- direct-stream write (no intermediate byte[]) ----------------------------

  /**
   * Write a Timestamp WKT directly to {@code out} at {@code fieldNum} without allocating a frame.
   */
  @SuppressWarnings("deprecation")
  public static void writeTimestamp(CodedOutputStream out, int fieldNum, Instant v)
      throws IOException {
    long seconds = v.getEpochSecond();
    int nanos = v.getNano();
    int innerSize = 0;
    if (seconds != 0L) innerSize += CodedOutputStream.computeInt64Size(1, seconds);
    if (nanos != 0) innerSize += CodedOutputStream.computeInt64Size(2, nanos);
    out.writeRawVarint32((fieldNum << 3) | 2);
    out.writeRawVarint32(innerSize);
    if (seconds != 0L) {
      out.writeRawVarint32(8);
      out.writeRawVarint64(seconds);
    }
    if (nanos != 0) {
      out.writeRawVarint32(16);
      out.writeRawVarint64(nanos);
    }
  }

  /**
   * Write a Duration WKT directly to {@code out} at {@code fieldNum} without allocating a frame.
   */
  @SuppressWarnings("deprecation")
  public static void writeDuration(CodedOutputStream out, int fieldNum, Duration v)
      throws IOException {
    long seconds = v.getSeconds();
    int nanos = v.getNano();
    if (seconds < 0 && nanos > 0) {
      seconds += 1;
      nanos -= 1_000_000_000;
    }
    int innerSize = 0;
    if (seconds != 0L) innerSize += CodedOutputStream.computeInt64Size(1, seconds);
    if (nanos != 0) innerSize += CodedOutputStream.computeInt64Size(2, nanos);
    out.writeRawVarint32((fieldNum << 3) | 2);
    out.writeRawVarint32(innerSize);
    if (seconds != 0L) {
      out.writeRawVarint32(8);
      out.writeRawVarint64(seconds);
    }
    if (nanos != 0) {
      out.writeRawVarint32(16);
      out.writeRawVarint64(nanos);
    }
  }

  /**
   * Write a FieldMask WKT directly to {@code out} at {@code fieldNum} without allocating a frame.
   */
  @SuppressWarnings("deprecation")
  public static void writeFieldMask(CodedOutputStream out, int fieldNum, FieldMask v)
      throws IOException {
    int innerSize = 0;
    for (String path : v.paths()) {
      if (!path.isEmpty()) innerSize += CodedOutputStream.computeStringSize(1, path);
    }
    out.writeRawVarint32((fieldNum << 3) | 2);
    out.writeRawVarint32(innerSize);
    for (String path : v.paths()) {
      if (!path.isEmpty()) out.writeString(1, path);
    }
  }

  /** Write an Empty WKT directly to {@code out} at {@code fieldNum} — a zero-length frame. */
  @SuppressWarnings("deprecation")
  public static void writeEmpty(CodedOutputStream out, int fieldNum, Empty v) throws IOException {
    out.writeRawVarint32((fieldNum << 3) | 2);
    out.writeRawVarint32(0);
  }

  /**
   * Write a scalar wrapper WKT directly to {@code out} at {@code fieldNum} without allocating a
   * frame.
   */
  @SuppressWarnings("deprecation")
  public static void writeWrap(CodedOutputStream out, int fieldNum, Wrap wire, Object value)
      throws IOException {
    int innerSize = wrapSize(wire, value);
    out.writeRawVarint32((fieldNum << 3) | 2);
    out.writeRawVarint32(innerSize);
    switch (wire) {
      case INT32, UINT32 -> {
        int v = (int) value;
        if (v != 0) out.writeInt32(1, v);
      }
      case SINT_32 -> {
        int v = (int) value;
        if (v != 0) out.writeSInt32(1, v);
      }
      case FIXED32 -> {
        int v = (int) value;
        if (v != 0) out.writeFixed32(1, v);
      }
      case INT64, UINT64 -> {
        long v = (long) value;
        if (v != 0L) out.writeInt64(1, v);
      }
      case SINT_64 -> {
        long v = (long) value;
        if (v != 0L) out.writeSInt64(1, v);
      }
      case FIXED64 -> {
        long v = (long) value;
        if (v != 0L) out.writeFixed64(1, v);
      }
      case FLOAT -> {
        float v = (float) value;
        if (v != 0f) out.writeFloat(1, v);
      }
      case DOUBLE -> {
        double v = (double) value;
        if (v != 0d) out.writeDouble(1, v);
      }
      case BOOL -> {
        boolean v = (boolean) value;
        if (v) out.writeBool(1, true);
      }
      case STRING -> {
        String v = (String) value;
        if (!v.isEmpty()) out.writeString(1, v);
      }
      case BYTES -> {
        Bytes v = (Bytes) value;
        if (!v.isEmpty()) out.writeByteArray(1, v.toByteArray());
      }
    }
  }
}
