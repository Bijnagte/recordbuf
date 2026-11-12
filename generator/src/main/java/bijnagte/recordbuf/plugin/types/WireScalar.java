package bijnagte.recordbuf.plugin.types;

/**
 * Wire-level primitive type for encoding — used by the codec generator to emit protobuf wire
 * primitives regardless of the Java boxed/primitive mapping. Distinguishes int from long, and
 * signed vs unsigned.
 */
public enum WireScalar {
  INT32,
  UINT32,
  SINT_32, /* zigzag */
  FIXED32, /* fixed-width */
  INT64,
  UINT64,
  SINT_64, /* zigzag */
  FIXED64, /* fixed-width */
  FLOAT,
  DOUBLE,
  BOOL,
  STRING,
  BYTES;

  /**
   * Resolve a bare proto scalar type name to its {@link WireScalar}. Used for both regular fields
   * and map key/value members, which store the proto type name.
   *
   * @param protoType the proto3/2 scalar type name (e.g. {@code "int32"}, {@code "string"})
   * @return the corresponding wire scalar
   * @throws IllegalStateException if {@code protoType} is {@code null} or unrecognised
   */
  public static WireScalar forType(String protoType) {
    if (protoType == null) throw new IllegalStateException("map component has no proto type");
    return switch (protoType) {
      case "int32" -> INT32;
      case "uint32" -> UINT32;
      case "sint32" -> SINT_32;
      case "fixed32" -> FIXED32;
      case "sfixed32" -> FIXED32;
      case "int64" -> INT64;
      case "uint64" -> UINT64;
      case "sint64" -> SINT_64;
      case "fixed64" -> FIXED64;
      case "float" -> FLOAT;
      case "double" -> DOUBLE;
      case "bool" -> BOOL;
      case "string" -> STRING;
      case "bytes" -> BYTES;
      default -> throw new IllegalStateException("unknown scalar type " + protoType);
    };
  }
}
