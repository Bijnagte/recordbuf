package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedInputStream;

/**
 * Owns the decode-side I/O scaffolding a generated codec used to spell by hand:
 * the {@link CodedInputStream} lifecycle. Generated codecs obtain their input
 * stream via {@link #start(byte[])} instead of re-declaring
 * {@code CodedInputStream.newInstance(...)} at every decode site.
 */
public final class Decoder {

    private final CodedInputStream in;

    private Decoder(CodedInputStream in) {
        this.in = in;
    }

    /** Open a decoder over the given wire bytes. */
    public static Decoder start(byte[] bytes) {
        return new Decoder(CodedInputStream.newInstance(bytes));
    }

    /** The {@link CodedInputStream} a codec drives its tag/value loop against. */
    public CodedInputStream in() {
        return in;
    }
}
