package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.source.TypeRef;

/**
 * Shared {@link TypeRef} constants for the fully-qualified names the source mappers emit.
 *
 * <p>Centralised in the generator module so the recordbuf-runtime, well-known-type, and Avro-bridge
 * FQNs — plus the common {@code java.*}/{@code com.google.protobuf} types several mappers reference
 * — are declared once and are never leaked into the generic {@link bijnagte.recordbuf.codegen}
 * module, which owns only the language-level boxed primitives on {@code TypeRef} itself.
 *
 * <p>Each constant is a shared, immutable {@link TypeRef.Named}; because the two-phase collector is
 * idempotent, reusing a constant across mappers collapses to a single import per type.
 */
final class TypeRefs {
  private TypeRefs() {}

  // --- recordbuf runtime helpers (bijnagte.recordbuf.runtime.*) ---
  static final TypeRef ENCODER = TypeRef.of("bijnagte.recordbuf.runtime.Encoder");
  static final TypeRef DECODER = TypeRef.of("bijnagte.recordbuf.runtime.Decoder");
  static final TypeRef UNKNOWN_FIELDS = TypeRef.of("bijnagte.recordbuf.runtime.UnknownFields");
  static final TypeRef CODEC_DEFAULTS = TypeRef.of("bijnagte.recordbuf.runtime.CodecDefaults");
  static final TypeRef WIRE = TypeRef.of("bijnagte.recordbuf.runtime.Wire");
  static final TypeRef BYTES = TypeRef.of("bijnagte.recordbuf.runtime.Bytes");
  static final TypeRef PROTOBUF_MESSAGE = TypeRef.of("bijnagte.recordbuf.runtime.ProtobufMessage");
  static final TypeRef UNWRAPABLE = TypeRef.of("bijnagte.recordbuf.runtime.Unwrapable");

  // --- well-known-type codec helper (bijnagte.recordbuf.runtime.wkt.Wkt) ---
  static final TypeRef WKT = TypeRef.of("bijnagte.recordbuf.runtime.wkt.Wkt");

  // --- Avro interop bridge (bijnagte.recordbuf.avro.*) ---
  static final TypeRef WKT_AVRO_BRIDGE = TypeRef.of("bijnagte.recordbuf.avro.WktAvroBridge");

  // --- common Java / protobuf types ---
  static final TypeRef.Named LIST = TypeRef.named("java.util", "List");
  static final TypeRef.Named ARRAY_LIST = TypeRef.named("java.util", "ArrayList");
  static final TypeRef.Named MAP = TypeRef.named("java.util", "Map");
  static final TypeRef.Named HASH_MAP = TypeRef.named("java.util", "HashMap");
  static final TypeRef BYTE_BUFFER = TypeRef.named("java.nio", "ByteBuffer");
  static final TypeRef ARRAYS = TypeRef.of("java.util.Arrays");
  static final TypeRef BYTE_STRING = TypeRef.of("com.google.protobuf.ByteString");
}
