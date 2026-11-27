package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;
import java.io.IOException;

/**
 * The tag / {@code skipField} decode primitives the per-message decode loop keeps — the skeleton of
 * {@code readTag}/{@code getTagFieldNumber}/ {@code skipField} — pulled here so generated codecs
 * lean on one shared decode primitive set rather than re-spelling the protobuf calls at every
 * {@code case} and {@code default} arm.
 */
public final class Wire {

  private Wire() {}

  /**
   * Read the next wire tag (0 at end-of-stream — the {@code isAtEnd} break). A tag whose wire type
   * is 6 or 7 is undefined, so it is rejected here, as protobuf-java does, instead of being read as
   * whatever type its field number declares.
   */
  public static int readTag(CodedInputStream in) throws IOException {
    int tag = in.readTag();
    int wireType = WireFormat.getTagWireType(tag);
    if (wireType > WireFormat.WIRETYPE_FIXED32) {
      throw new InvalidProtocolBufferException("Protocol message tag had invalid wire type.");
    }
    return tag;
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
