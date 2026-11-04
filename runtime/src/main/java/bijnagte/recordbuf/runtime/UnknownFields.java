package bijnagte.recordbuf.runtime;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The always-on, record-local carrier of the fields a decode saw on the wire that its schema does
 * not model. It rides on every generated record as a component; messages that see nothing unknown
 * hold the shared {@link #empty()}, so the common case pays nothing.
 *
 * <p>Mirroring protobuf-java's unknown-field semantics, it keeps captured fields in wire order,
 * preserves repeats (each occurrence of a field number is a separate {@link UnknownField}), and is
 * immutable once built. A decoded record re-emits its unknown fields on encode, so a message read
 * through an older schema survives a round-trip byte-for-byte. Nested records carry their own
 * independently: a nested message's unknown fields are captured and re-emitted by that nested
 * record's own codec, invisible to the outer codec.
 */
public final class UnknownFields {

  /**
   * The shared empty carrier. The component default for every record and the value of any decode
   * that saw nothing unknown; identity-equal so no per-record allocation is needed when there is
   * nothing to carry.
   */
  public static final UnknownFields EMPTY = new UnknownFields(List.of());

  private final List<UnknownField> fields;

  private UnknownFields(List<UnknownField> fields) {
    this.fields = fields;
  }

  /** The shared empty carrier ({@link #EMPTY}). */
  public static UnknownFields empty() {
    return EMPTY;
  }

  /**
   * @return {@code true} when this carrier holds nothing to re-emit.
   */
  public boolean isEmpty() {
    return fields.isEmpty();
  }

  /**
   * A fresh accumulator for a decode loop. Generated codecs obtain one via {@link Builder} before
   * the {@code while (!isAtEnd())} loop and hand its {@link Builder#build() result} to the record's
   * constructor as the {@code unknownFields} component.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** The captured fields in wire order, most-recently-seen last. */
  public List<UnknownField> fields() {
    return fields;
  }

  /**
   * Value equality by captured content, per the carrier's purpose: two decoded records that saw the
   * same unknown fields compare equal regardless of the fresh per-decode carrier instances. A
   * carrier that saw nothing is equal only to {@link #empty()}.
   */
  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) return true;
    if (!(o instanceof UnknownFields that)) return false;
    return fields.equals(that.fields);
  }

  @Override
  public int hashCode() {
    return fields.hashCode();
  }

  @Override
  public java.lang.String toString() {
    return "UnknownFields" + fields;
  }

  /**
   * Re-emit every captured field's bytes onto {@code out}, in wire order, as each field's tag then
   * its exact post-tag value.
   */
  void writeTo(CodedOutputStream out) throws IOException {
    for (UnknownField f : fields) {
      out.writeTag(f.fieldNumber(), f.wireType().code());
      out.writeRawBytes(f.value());
    }
  }

  /**
   * A mutable accumulator the generated decode loop captures into, one {@link UnknownField} per
   * unknown wire value, in wire order, then {@link #build()}'s into an immutable {@link
   * UnknownFields}.
   */
  public static final class Builder {

    /**
     * Captured so far; {@code null} until the first unknown field is seen, so the common all-known
     * decode loop allocates no list. This accumulator is never stored as a record component (its
     * {@link #build()} result is the stable value a record holds), so the escape is local and fine.
     */
    @Nullable private List<UnknownField> fields;

    private Builder() {
      this.fields = null;
    }

    /**
     * Capture the unknown field whose tag {@code tag} was just read from {@code in} into this
     * accumulator. The value's raw bytes are read on demand from the stream and stored; a {@code
     * I32} varint yields its varint bytes, an {@code I64} its 8 raw bytes, and a {@code
     * LENGTH_DELIMITED} its length prefix plus content — for a byte-identical re-emit on encode.
     *
     * @param in the decode stream, positioned just past the field's tag
     * @param tag the raw tag varint, whose low bits give the wire type
     * @return this builder, for chaining
     * @throws IOException if the stream ends before the value is fully read
     */
    public Builder add(CodedInputStream in, int tag) throws IOException {
      WireType wireType = WireType.fromTag(tag);
      byte[] value;
      switch (wireType) {
        case I32 -> value = varint64Bytes(in.readRawVarint64());
        case I64 -> value = in.readRawBytes(8);
        case LENGTH_DELIMITED -> {
          int len = in.readRawVarint32();
          byte[] content = in.readRawBytes(len);
          value = concat(varint32Bytes(len), content);
        }
        default ->
            throw new IllegalStateException(
                "cannot capture unknown wire type "
                    + wireType
                    + " (group fields are not supported; proto3 never emits one)");
      }
      int fieldNumber = tag >>> 3;
      if (fields == null) {
        fields = new ArrayList<>(2);
      }
      fields.add(new UnknownField(fieldNumber, wireType, value));
      return this;
    }

    /**
     * Finish capturing: an empty accumulator yields the shared {@link #empty()} instead of
     * allocating a fresh empty carrier.
     */
    public UnknownFields build() {
      if (fields == null || fields.isEmpty()) {
        return EMPTY;
      }
      return new UnknownFields(List.copyOf(fields));
    }

    private static byte[] concat(byte[] prefix, byte[] content) {
      byte[] out = new byte[prefix.length + content.length];
      System.arraycopy(prefix, 0, out, 0, prefix.length);
      System.arraycopy(content, 0, out, prefix.length, content.length);
      return out;
    }

    private static byte[] varint32Bytes(int v) {
      return varint64Bytes(v & 0xFFFFFFFFL);
    }

    private static byte[] varint64Bytes(long v) {
      int[] buf = new int[10];
      int n = 0;
      long v0 = v;
      while (true) {
        if ((v0 & ~0x7FL) == 0) {
          buf[n++] = (int) v0;
          break;
        }
        buf[n++] = (int) ((v0 & 0x7F) | 0x80);
        v0 >>>= 7;
      }
      byte[] out = new byte[n];
      for (int i = 0; i < n; i++) {
        out[i] = (byte) buf[i];
      }
      return out;
    }
  }
}
