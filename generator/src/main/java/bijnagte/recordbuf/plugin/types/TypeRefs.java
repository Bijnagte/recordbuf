package bijnagte.recordbuf.plugin.types;

import bijnagte.recordbuf.codegen.source.TypeRef;

/**
 * Shared {@link TypeRef} constants for the fully-qualified names the source mappers (and the model
 * loader, for field types) emit.
 *
 * <p>Centralised in the generator module's {@code types} package so the recordbuf-runtime,
 * well-known-type, and Avro-bridge FQNs — plus the common {@code java.*}/{@code
 * com.google.protobuf} types several mappers reference — are declared once and are never leaked
 * into the generic {@link bijnagte.recordbuf.codegen} module, which owns only the language-level
 * boxed primitives on {@code TypeRef} itself.
 *
 * <p>Each constant is a shared, immutable {@link TypeRef.Named}; because the two-phase collector is
 * idempotent, reusing a constant across mappers collapses to a single import per type.
 */
public final class TypeRefs {
  private TypeRefs() {}

  // --- recordbuf runtime helpers (bijnagte.recordbuf.runtime.*) ---
  public static final TypeRef ENCODER = TypeRef.of("bijnagte.recordbuf.runtime.Encoder");
  public static final TypeRef DECODER = TypeRef.of("bijnagte.recordbuf.runtime.Decoder");
  public static final TypeRef UNKNOWN_FIELDS =
      TypeRef.of("bijnagte.recordbuf.runtime.UnknownFields");
  public static final TypeRef CODEC_DEFAULTS =
      TypeRef.of("bijnagte.recordbuf.runtime.CodecDefaults");
  public static final TypeRef WIRE = TypeRef.of("bijnagte.recordbuf.runtime.Wire");
  public static final TypeRef BYTES = TypeRef.of("bijnagte.recordbuf.runtime.Bytes");
  public static final TypeRef PROTOBUF_MESSAGE =
      TypeRef.of("bijnagte.recordbuf.runtime.ProtobufMessage");
  public static final TypeRef UNWRAPABLE = TypeRef.of("bijnagte.recordbuf.runtime.Unwrapable");

  // --- well-known-type codec helper (bijnagte.recordbuf.runtime.wkt.Wkt) ---
  public static final TypeRef WKT = TypeRef.of("bijnagte.recordbuf.runtime.wkt.Wkt");

  // --- well-known-type native Java types (the types WellKnownTypes entries resolve to) ---
  public static final TypeRef INSTANT = TypeRef.named("java.time", "Instant");
  public static final TypeRef DURATION = TypeRef.named("java.time", "Duration");
  public static final TypeRef FIELD_MASK = TypeRef.of("bijnagte.recordbuf.runtime.FieldMask");
  public static final TypeRef EMPTY = TypeRef.of("bijnagte.recordbuf.runtime.Empty");

  // --- Jackson databind node types (the Jackson JSON surface for dynamic WKT fields) ---
  public static final TypeRef OBJECT_NODE =
      TypeRef.named("com.fasterxml.jackson.databind.node", "ObjectNode");
  public static final TypeRef ARRAY_NODE =
      TypeRef.named("com.fasterxml.jackson.databind.node", "ArrayNode");
  public static final TypeRef JSON_NODE =
      TypeRef.named("com.fasterxml.jackson.databind", "JsonNode");
  public static final TypeRef NULL_NODE =
      TypeRef.named("com.fasterxml.jackson.databind.node", "NullNode");

  // --- Avro interop bridge (bijnagte.recordbuf.avro.*) ---
  public static final TypeRef WKT_AVRO_BRIDGE =
      TypeRef.of("bijnagte.recordbuf.avro.wkt.WktAvroBridge");

  // --- common Java / protobuf types ---
  public static final TypeRef.Named LIST = TypeRef.named("java.util", "List");
  public static final TypeRef.Named ARRAY_LIST = TypeRef.named("java.util", "ArrayList");
  public static final TypeRef.Named MAP = TypeRef.named("java.util", "Map");
  public static final TypeRef.Named HASH_MAP = TypeRef.named("java.util", "HashMap");
  public static final TypeRef BYTE_BUFFER = TypeRef.named("java.nio", "ByteBuffer");
  public static final TypeRef ARRAYS = TypeRef.of("java.util.Arrays");
  public static final TypeRef BYTE_STRING = TypeRef.of("com.google.protobuf.ByteString");

  /**
   * The boxed reference-type form of a scalar {@link TypeRef} for contexts that need a reference
   * type — most importantly a oneof value record, whose {@code value()} accessor must covariantly
   * override {@code Unwrapable<Object>#value()} (a primitive return would not). Primitives map to
   * their {@code java.lang} boxed siblings; every other type passes through unchanged.
   */
  public static TypeRef boxed(TypeRef t) {
    return switch (t) {
      case TypeRef.Primitive p ->
          switch (p) {
            case INT -> TypeRef.named("java.lang", "Integer");
            case LONG -> TypeRef.named("java.lang", "Long");
            case FLOAT -> TypeRef.named("java.lang", "Float");
            case DOUBLE -> TypeRef.named("java.lang", "Double");
            case BOOLEAN -> TypeRef.named("java.lang", "Boolean");
            case SHORT -> TypeRef.named("java.lang", "Short");
            case BYTE -> TypeRef.named("java.lang", "Byte");
            case CHAR -> TypeRef.named("java.lang", "Character");
            case VOID -> TypeRef.named("java.lang", "Void");
          };
      default -> t;
    };
  }

  /** The runtime wire-type enum (the trailer's wireType member). */
  public static final TypeRef WIRE_TYPE = TypeRef.of("bijnagte.recordbuf.runtime.WireType");

  /** The runtime unknown-field carrier entry (number, wireType, value). */
  public static final TypeRef UNKNOWN_FIELD = TypeRef.of("bijnagte.recordbuf.runtime.UnknownField");

  /** The runtime date carrier. */
  public static final TypeRef DATE = TypeRef.of("bijnagte.recordbuf.runtime.Date");
}
