package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;

/**
 * Owns the encode-side I/O scaffolding a generated codec used to spell by hand:
 * a fresh {@link CodedOutputStream} over an in-memory buffer and its
 * {@code flush()}/{@code toByteArray()} teardown. Generated codecs call
 * {@link #start()}, write into {@link #out()}, and finish with {@link #finish()}
 * instead of re-declaring the stream lifecycle at every encode site.
 */
public final class Encoder {

    private final ByteArrayOutputStream buffer;
    private final CodedOutputStream out;

    private Encoder(ByteArrayOutputStream buffer, CodedOutputStream out) {
        this.buffer = buffer;
        this.out = out;
    }

    /** Open a fresh encoder over a new in-memory buffer. */
    public static Encoder start() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        return new Encoder(buffer, CodedOutputStream.newInstance(buffer));
    }

    /** The {@link CodedOutputStream} a codec writes fields into. */
    public CodedOutputStream out() {
        return out;
    }

    /** Flush the stream and return the encoded bytes. */
    public byte[] finish() throws java.io.IOException {
        out.flush();
        return buffer.toByteArray();
    }
}
