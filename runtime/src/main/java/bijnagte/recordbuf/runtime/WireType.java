package bijnagte.recordbuf.runtime;

/**
 * The four protobuf wire types a field's value occupies on the wire, in the 0-3 range carried by
 * the low three bits of a tag. The fourth (legacy {@code START_GROUP}/{@code END_GROUP}), never
 * produced by proto3, is carried here so a captured value that is not itself a group can be typed
 * honestly; group capture is out of scope (see {@link UnknownField}).
 *
 * <p>{@code wireType} is the on-wire code ({@code tag & 0x7}); {@code fieldNumber} is {@code tag
 * >>> 3}. This mirrors {@code com.google.protobuf.WireFormat}'s tag packing so a captured field
 * re-emits the exact same tag.
 */
public enum WireType {

  /** Varint value: int32, uint32, int64, uint64, sint{32,64}, bool, enum. */
  I32(0),

  /** 8 fixed bytes: uint64, double, fixed64, sfixed64. */
  I64(1),

  /** Length-prefixed bytes: string, bytes, embedded messages, packed repeated. */
  LENGTH_DELIMITED(2),

  /** Legacy embedded-message group opening tag (proto3 never emits this). */
  START_GROUP(3),

  /** 4 fixed bytes: uint32, float, fixed32, sfixed32. */
  FIXED32(5),

  /** Legacy embedded-message group closing tag (proto3 never emits this). */
  END_GROUP(4);

  private final int code;

  WireType(int code) {
    this.code = code;
  }

  /** The on-wire low bits of a tag for this type. */
  public int code() {
    return code;
  }

  /** The wire type encoded in a tag's low three bits. */
  public static WireType fromCode(int code) {
    return switch (code) {
      case 0 -> I32;
      case 1 -> I64;
      case 2 -> LENGTH_DELIMITED;
      case 3 -> START_GROUP;
      case 4 -> END_GROUP;
      case 5 -> FIXED32;
      default -> throw new IllegalArgumentException("unknown wire type code: " + code);
    };
  }

  /** The wire type packed in a tag, {@code tag & 0x7}. */
  public static WireType fromTag(int tag) {
    return fromCode(tag & 0x7);
  }

  /** Re-pack a field number and this wire type into a tag, {@code (n << 3) | code}. */
  public int tagFor(int fieldNumber) {
    return (fieldNumber << 3) | code;
  }
}
