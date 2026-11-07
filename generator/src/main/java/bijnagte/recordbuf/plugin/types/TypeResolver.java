package bijnagte.recordbuf.plugin.types;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.writer.TypeImports;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a {@link Field} to its Java static type name (e.g. {@code
 * "java.util.List<java.lang.String>"})).
 *
 * <p>For nested types inside the same file, we return the bare short name so generated code stays
 * readable. Cross-file types are fully-qualified.
 */
public final class TypeResolver {

  private final FileTable table;
  private final TypeImports ci;

  public TypeResolver(FileTable table, TypeImports ci) {
    this.table = table;
    this.ci = ci;
  }

  /** The file's shared import collector. */
  public TypeImports imports() {
    return ci;
  }

  /**
   * Resolve the full Java type of a field. Repeated → {@code List<...>}. A presence-tracked field
   * ({@link Field#isNullable()}) boxes its scalar to the corresponding wrapper type; the caller
   * annotates it {@code @org.jspecify.annotations.Nullable}. It is never wrapped in {@code
   * java.util.Optional}.
   */
  public String resolve(Field f) {
    if (f.isRepeated()) return ci.use("java.util.List") + "<" + resolveElement(f) + ">";
    String base = baseFor(f);
    if (f.isNullable() && f.isScalar()) return ci.use(boxed(base));
    return base;
  }

  /**
   * As {@link #resolve(Field)} but prefixed with the {@code @Nullable} type-use annotation
   * (imported by the collector) for a presence-tracked component. Java forbids a type-use
   * annotation prefixed to a qualified type, so for a fully-qualified fallback (a short name that
   * collided) the annotation sits before the final identifier ({@code pkg.@Nullable T}); a bare
   * name is a plain prefix ({@code @Nullable T}).
   */
  public String resolveNullable(Field f) {
    String type = resolve(f);
    // The @Nullable type-use annotation, resolved (and imported) like any other
    // referenced type. A bare component type gets a plain prefix (@Nullable T);
    // a fully-qualified fallback (short name that collided) puts it directly
    // before the final identifier, as Java requires (pkg.@Nullable T).
    String ann = ci.use("org.jspecify.annotations.Nullable");
    String at = "@" + ann + " ";
    int dot = type.lastIndexOf('.');
    if (dot < 0) return at + type;
    return type.substring(0, dot) + "." + at + type.substring(dot + 1);
  }

  /**
   * The non-repeated, non-boxed base type: scalar primitive, reference type, or {@code Map<K,V>}.
   */
  private String baseFor(Field f) {
    return switch (f.kind()) {
      case SCALAR -> ci.use(scalarJava(f.typeUrl()));
      case MESSAGE, ENUM -> resolveType(f.typeUrl());
      case MAP -> {
        // Map<K,V> cannot hold primitives, so both bounds are the BOXED
        // java type of the map key/value (Map<String, Integer>), not the
        // primitive (Map<String, int>). mapKey()/mapValue() hold the proto
        // type name; mapComponentType boxes it.
        String k = mapComponentType(f.mapKey());
        String v = mapComponentType(f.mapValue());
        yield ci.use("java.util.Map") + "<" + k + ", " + v + ">";
      }
      default -> ci.use("java.lang.Object");
    };
  }

  /**
   * The boxed Java component type of a map key/value, from its proto type name (e.g. "int32" ->
   * "java.lang.Integer", "string"/"bool" -> the boxed ref).
   */
  public String mapComponentType(@Nullable String protoType) {
    if (protoType == null) return ci.use("java.lang.Object");
    return ci.use(boxed(scalarJava(protoType)));
  }

  /**
   * Box a scalar primitive to its wrapper type. Only called for presence-tracked (nullable)
   * scalars, where primitive-as-is is not representable.
   */
  private static String boxed(String primitive) {
    return switch (primitive) {
      case "int" -> "java.lang.Integer";
      case "long" -> "java.lang.Long";
      case "double" -> "java.lang.Double";
      case "float" -> "java.lang.Float";
      case "boolean" -> "java.lang.Boolean";
      default -> primitive; // byte[], java.lang.String, refs already boxed
    };
  }

  /** For repeated fields, just the element type — used when generating loops. */
  public String resolveElement(Field f) {
    return switch (f.kind()) {
      // A List<...> cannot hold a primitive (List<int> is not valid Java),
      // so a repeated scalar's element type is its boxed wrapper; primitive
      // scalars become Integer/Long/Float/Double/Boolean while byte[] and
      // reference types already pass through boxed() unchanged.
      case SCALAR -> ci.use(boxed(scalarJava(f.typeUrl())));
      case MESSAGE, ENUM -> resolveType(f.typeUrl());
      case MAP -> throw new IllegalStateException("map fields are not repeated in proto3");
      default -> ci.use("java.lang.Object");
    };
  }

  private String resolveType(String protoFqn) {
    if (protoFqn == null) return ci.use("java.lang.Object");
    String javaFqn = table.javaFqn(protoFqn);
    if (javaFqn == null) return ci.use(lastSegment(protoFqn));
    // The collector applies the same-package (import-free) rule and collision
    // fallback for us, so cross-file types become short + imported and collided
    // types fall back to their fully-qualified name.
    return ci.use(javaFqn);
  }

  private static String lastSegment(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  // --- scalar lookup ---
  //
  // Field.typeUrl() for a SCALAR field stores the proto3/2 type name as a
  // string (e.g. "int32", "string", "bytes"); see ModelLoader.

  static String scalarJava(String protoType) {
    return switch (protoType) {
      case "double" -> "double";
      case "float" -> "float";
      case "int32", "sint32", "sfixed32", "fixed32" -> "int";
      case "uint32" -> "int"; // v1: revisit; lossy for 0x8000_0000..
      case "int64", "sint64", "sfixed64", "fixed64" -> "long";
      case "uint64" -> "long"; // v1: revisit; sign issue
      case "bool" -> "boolean";
      case "string" -> "java.lang.String";
      case "bytes" -> "bijnagte.recordbuf.runtime.Bytes";
      default -> "java.lang.Object";
    };
  }

  /**
   * Wire-level primitive type for encoding — used by the CodecGenerator. Distinguishes int from
   * long, and signed vs unsigned.
   */
  public enum WireScalar {
    INT32,
    UINT32,
    SINT_32 /* zigzag */,
    FIXED32 /* fixed-width */,
    INT64,
    UINT64,
    SINT_64 /* zigzag */,
    FIXED64 /* fixed-width */,
    FLOAT,
    DOUBLE,
    BOOL,
    STRING,
    BYTES
  }

  public WireScalar wireScalar(Field f) {
    return wireScalarForType(f.typeUrl());
  }

  /**
   * Wire-level primitive type for a bare proto scalar type name (used for both fields and map
   * key/value members, which store the proto type name).
   */
  public WireScalar wireScalarForType(@Nullable String protoType) {
    // A map key/value is always a populated scalar, so a null here is a
    // model bug; reject up front rather than letting it slip to the default.
    if (protoType == null) throw new IllegalStateException("map component has no proto type");
    return switch (protoType) {
      case "int32" -> WireScalar.INT32;
      case "uint32" -> WireScalar.UINT32;
      case "sint32" -> WireScalar.SINT_32;
      case "fixed32" -> WireScalar.FIXED32;
      case "sfixed32" -> WireScalar.FIXED32;
      case "int64" -> WireScalar.INT64;
      case "uint64" -> WireScalar.UINT64;
      case "sint64" -> WireScalar.SINT_64;
      case "fixed64" -> WireScalar.FIXED64;
      case "sfixed64" -> WireScalar.FIXED64;
      case "float" -> WireScalar.FLOAT;
      case "double" -> WireScalar.DOUBLE;
      case "bool" -> WireScalar.BOOL;
      case "string" -> WireScalar.STRING;
      case "bytes" -> WireScalar.BYTES;
      default -> throw new IllegalStateException("unknown scalar type " + protoType);
    };
  }
}
