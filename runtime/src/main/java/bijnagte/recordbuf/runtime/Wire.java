package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/**
 * The tag / {@code skipField} decode primitives the per-message decode loop
 * keeps — the skeleton of {@code readTag}/{@code getTagFieldNumber}/
 * {@code skipField} — pulled here so generated codecs lean on one shared decode
 * primitive set rather than re-spelling the protobuf calls at every {@code case}
 * and {@code default} arm.
 */
public final class Wire {

    private Wire() {}

    /** Read the next wire tag (0 at end-of-stream — the {@code isAtEnd} break). */
    public static int readTag(CodedInputStream in) throws IOException {
        return in.readTag();
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
     * Capture the unknown field whose tag {@code tag} was just read from {@code in}
     * into a record-local {@link UnknownFields.Builder}, for the decode loop's
     * {@code default} arm. The raw value bytes are read on demand and stored, so
     * the field re-emits byte-identically on encode.
     *
     * @param in    the decode stream, positioned just past the field's tag
     * @param target the carrier builder the field is appended to
     * @param tag    the raw tag varint, whose low bits give the wire type
     * @return the same builder, for chaining
     * @throws IOException if the stream ends before the value is fully read
     */
    public static UnknownFields.Builder capture(CodedInputStream in, UnknownFields.Builder target, int tag) throws IOException {
        return target.add(in, tag);
    }

    /**
     * Re-emit a record-local unknown-field carrier onto an encode stream, in wire
     * order. The encode loop's trailing arm — a decode that saw no unknown fields
     * captures the shared {@link UnknownFields#empty()} and this writes nothing,
     * keeping ordinary encodes byte-identical to without the carrier.
     *
     * @param out the encode stream the known fields were written to
     * @param unknown the record's captured carrier
     * @throws IOException if the stream flush/write fails
     */
    public static void writeUnknown(CodedOutputStream out, UnknownFields unknown) throws IOException {
        unknown.writeTo(out);
    }
}
