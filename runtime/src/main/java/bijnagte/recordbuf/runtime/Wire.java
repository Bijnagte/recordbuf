package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.util.List;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * The tag / {@code skipField} decode primitives the per-message decode loop keeps — the skeleton of
 * {@code readTag}/{@code getTagFieldNumber}/ {@code skipField} — pulled here so generated codecs
 * lean on one shared decode primitive set rather than re-spelling the protobuf calls at every
 * {@code case} and {@code default} arm.
 */
public final class Wire {

  private Wire() {}

  /**
   * Read the next wire tag (0 at end-of-stream — the {@code isAtEnd} break). The tag is read
   * strictly: a tag varint longer than five bytes, or one whose value does not fit 32 bits, is
   * rejected instead of being cut to its low 32 bits (as {@code CodedInputStream.readTag} does,
   * which would let a six-byte tag spell a valid field). A tag whose wire type is 6 or 7 is
   * undefined and rejected too, as is field number 0.
   */
  public static int readTag(CodedInputStream in) throws IOException {
    if (in.isAtEnd()) {
      return 0;
    }
    int tag = 0;
    int shift = 0;
    while (true) {
      byte b = in.readRawByte();
      if (shift == 28 && (b & 0xF0) != 0) {
        // The fifth byte may only carry the top four bits, with no continuation.
        throw new InvalidProtocolBufferException("Protocol message tag varint is out of range.");
      }
      tag |= (b & 0x7F) << shift;
      if (b >= 0) {
        break;
      }
      shift += 7;
    }
    if (WireFormat.getTagFieldNumber(tag) == 0) {
      throw new InvalidProtocolBufferException("Protocol message contained an invalid tag (zero).");
    }
    int wireType = WireFormat.getTagWireType(tag);
    if (wireType > WireFormat.WIRETYPE_FIXED32) {
      throw new InvalidProtocolBufferException("Protocol message tag had invalid wire type.");
    }
    return tag;
  }

  /**
   * The enum a decoded number names, for a singular field: when no constant has the number it is
   * kept in {@code unknown} (so it re-emits) and the field keeps its {@code current} value.
   *
   * @param unknown the decode loop's unknown-field accumulator
   * @param field the field number
   * @param number the decoded enum number
   * @param lookup the enum's exact lookup, {@code null} for an unknown number
   * @param current the value the field holds so far
   * @return the constant, or {@code current} when the number is unknown
   */
  public static <E> @Nullable E enumOrUnknown(
      UnknownFields.Builder unknown,
      int field,
      int number,
      IntFunction<@Nullable E> lookup,
      @Nullable E current) {
    E constant = lookup.apply(number);
    if (constant == null) {
      unknown.addVarint(field, number);
      return current;
    }
    return constant;
  }

  /**
   * Adds the enum a decoded number names to a repeated field's list; an unknown number is kept in
   * {@code unknown} instead.
   *
   * @param unknown the decode loop's unknown-field accumulator
   * @param field the field number
   * @param number the decoded enum number
   * @param lookup the enum's exact lookup, {@code null} for an unknown number
   * @param into the field's list
   */
  public static <E> void addEnum(
      UnknownFields.Builder unknown,
      int field,
      int number,
      IntFunction<@Nullable E> lookup,
      List<E> into) {
    E constant = lookup.apply(number);
    if (constant == null) {
      unknown.addVarint(field, number);
    } else {
      into.add(constant);
    }
  }

  /**
   * The two byte arrays one after the other. Protobuf defines merging two serialized messages as
   * parsing their concatenation, which is how a generated codec merges a message field that occurs
   * again on the wire.
   *
   * @param first the bytes read first
   * @param second the bytes read after them
   * @return a new array holding both
   */
  public static byte[] concat(byte[] first, byte[] second) {
    byte[] all = new byte[first.length + second.length];
    System.arraycopy(first, 0, all, 0, first.length);
    System.arraycopy(second, 0, all, first.length, second.length);
    return all;
  }

  /** The wire type of a wire tag: 2 is a length-delimited (packed) group. */
  public static int tagWireType(int tag) {
    return WireFormat.getTagWireType(tag);
  }

  /** The field number encoded in a wire tag. */
  public static int tagFieldNumber(int tag) {
    return WireFormat.getTagFieldNumber(tag);
  }

  /** Skip the field a named tag describes — the decode loop's {@code default} arm. */
  public static void skip(CodedInputStream in, int tag) throws IOException {
    in.skipField(tag);
  }

  /**
   * Capture the unknown field whose tag {@code tag} was just read from {@code in} into a
   * record-local {@link UnknownFields.Builder}, for the decode loop's {@code default} arm. The raw
   * value bytes are read on demand and stored, so the field re-emits byte-identically on encode.
   *
   * @param in the decode stream, positioned just past the field's tag
   * @param target the carrier builder the field is appended to
   * @param tag the raw tag varint, whose low bits give the wire type
   * @return the same builder, for chaining
   * @throws IOException if the stream ends before the value is fully read
   */
  public static UnknownFields.Builder capture(
      CodedInputStream in, UnknownFields.Builder target, int tag) throws IOException {
    return target.add(in, tag);
  }

  /**
   * Re-emit a record-local unknown-field carrier onto an encode stream, in wire order. The encode
   * loop's trailing arm — a decode that saw no unknown fields captures the shared {@link
   * UnknownFields#empty()} and this writes nothing, keeping ordinary encodes byte-identical to
   * without the carrier.
   *
   * @param out the encode stream the known fields were written to
   * @param unknown the record's captured carrier
   * @throws IOException if the stream flush/write fails
   */
  public static void writeUnknown(CodedOutputStream out, UnknownFields unknown) throws IOException {
    unknown.writeTo(out);
  }

  /**
   * Compute the wire size of a length-delimited field: tag + length-prefix + nested body. Shared by
   * generated codecs' {@code getSerializedSize} so the helper isn't re-emitted per codec.
   *
   * @param fieldNum the field number
   * @param nestedSize the size in bytes of the nested body
   * @return the total encoded size of the field
   */
  public static int lenDelimSize(int fieldNum, int nestedSize) {
    return CodedOutputStream.computeTagSize(fieldNum)
        + CodedOutputStream.computeInt32Size(1, nestedSize)
        - 1
        + nestedSize;
  }
}
