package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedInputStream;
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
}
