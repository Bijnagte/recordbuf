package bijnagte.recordbuf.plugin.types;

import bijnagte.recordbuf.codegen.source.TypeRef;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The canonical registry of the protobuf <em>well-known types</em> (WKTs) the generator turns into
 * native Java types — the seam that lets a {@code google.protobuf.*} field resolve to a plain Java
 * / {@link bijnagte.recordbuf.runtime} type instead of its protobuf record.
 *
 * <p>Each WKT is one of five classifications, each with its own native type and wire framing a
 * generated codec delegates to {@link bijnagte.recordbuf.runtime.wkt.Wkt}:
 *
 * <ul>
 *   <li><b>structured</b> &mdash; carries real fields and maps to a native time/holder type: {@code
 *       Timestamp} &rarr; {@link java.time.Instant}, {@code Duration} &rarr; {@link
 *       java.time.Duration}, {@code FieldMask} &rarr; {@code bijnagte.recordbuf.runtime.FieldMask},
 *       {@code Empty} &rarr; {@code bijnagte.recordbuf.runtime.Empty} (KTD3: a WKT that owns its
 *       fields and has no Avro analogue maps to a native / new-runtime type, and is
 *       non-null-with-a-zero default on the record).
 *   <li><b>wrapper</b> &mdash; the {@code *Value} scalar wrappers map to the boxed primitive they
 *       wrap (KTD3: the simplest possible wrapper IS the primitive). A wrapper is present iff the
 *       wrapper is present, so the record field is {@code @Nullable}.
 *   <li><b>bytes wrapper</b> &mdash; {@code BytesValue} maps to {@code bijnagte.recordbuf.runtime.
 *       Bytes} (the runtime already models {@code bytes} as {@code Bytes}), likewise
 *       present-iff-set.
 *   <li><b>dynamic</b> &mdash; {@code Struct}/{@code Value}/{@code ListValue}/{@code Any} carry
 *       arbitrary/unstructured shape; they carry <b>no canonical wire shape</b> (AE4). Per R11 the
 *       generator fails <em>loud</em> on these rather than coercing them (see {@link
 *       TypeResolver}).
 * </ul>
 */
public final class WellKnownTypes {

  private WellKnownTypes() {}

  /** The classification of a WKT entry &mdash; how its native type and wire framing differ. */
  public enum Kind {
    /**
     * {@code Timestamp}/{@code Duration}/{@code FieldMask}/{@code Empty}: a native / runtime type.
     */
    STRUCTURED,
    /** A {@code *Value} wrapper over a boxed scalar. */
    WRAPPER,
    /** {@code BytesValue}: the runtime {@code Bytes} holder. */
    BYTES_WRAPPER,
    /** {@code Struct}/{@code Value}/{@code ListValue}/{@code Any}: no canonical wire shape. */
    DYNAMIC_UNSUPPORTED
  }

  /**
   * A WKT entry: its canonical FQN, how it is handled, the native Java type it resolves to, and
   * (for a wrapper / bytes wrapper) {@link #wire} &mdash; the scalar it frames on the wire.
   */
  public record Entry(
      String fqn,
      Kind kind,
      @Nullable TypeRef javaType,
      @Nullable WireScalar wire,
      @Nullable String zeroDefault) {

    /** Whether a reference to this WKT in a field fails loud at generation (R11 dynamic). */
    public boolean isDynamic() {
      return kind == Kind.DYNAMIC_UNSUPPORTED;
    }

    /**
     * Whether the record's field for this WKT holds a non-null zero value when absent (a structured
     * / empty WKT), as opposed to a {@code @Nullable} presence value (a wrapper / bytes wrapper).
     */
    public boolean hasZeroDefault() {
      return kind == Kind.STRUCTURED;
    }

    /**
     * The non-null zero value expression used as this WKT's record-field default and decode
     * default. Uses the field's resolved (imported) simple type name. Null when the field is
     * {@code @Nullable} instead.
     */
    @Override
    public @Nullable String zeroDefault() {
      return zeroDefault;
    }
  }

  private static final String TS = "google.protobuf.Timestamp";
  private static final String DUR = "google.protobuf.Duration";
  private static final String MASK = "google.protobuf.FieldMask";
  private static final String EMPTY = "google.protobuf.Empty";

  private static final Entry TIMESTAMP =
      new Entry(TS, Kind.STRUCTURED, TypeRefs.INSTANT, null, "Instant.EPOCH");
  private static final Entry DURATION =
      new Entry(DUR, Kind.STRUCTURED, TypeRefs.DURATION, null, "Duration.ZERO");
  private static final Entry FIELD_MASK =
      new Entry(MASK, Kind.STRUCTURED, TypeRefs.FIELD_MASK, null, "FieldMask.empty()");
  private static final Entry EMPTY_ENTRY =
      new Entry(EMPTY, Kind.STRUCTURED, TypeRefs.EMPTY, null, "Empty.INSTANCE");

  private static final Entry BOOL_VALUE =
      entry(Kind.WRAPPER, "BoolValue", TypeRef.BOOLEAN, WireScalar.BOOL);
  private static final Entry INT32_VALUE =
      entry(Kind.WRAPPER, "Int32Value", TypeRef.INTEGER, WireScalar.INT32);
  private static final Entry UINT32_VALUE =
      entry(Kind.WRAPPER, "UInt32Value", TypeRef.INTEGER, WireScalar.UINT32);
  private static final Entry SINT32_VALUE =
      entry(Kind.WRAPPER, "SInt32Value", TypeRef.INTEGER, WireScalar.SINT_32);
  private static final Entry FIXED32_VALUE =
      entry(Kind.WRAPPER, "Fixed32Value", TypeRef.INTEGER, WireScalar.FIXED32);
  private static final Entry SFIXED32_VALUE =
      entry(Kind.WRAPPER, "SFixed32Value", TypeRef.INTEGER, WireScalar.FIXED32);
  private static final Entry INT64_VALUE =
      entry(Kind.WRAPPER, "Int64Value", TypeRef.LONG, WireScalar.INT64);
  private static final Entry UINT64_VALUE =
      entry(Kind.WRAPPER, "UInt64Value", TypeRef.LONG, WireScalar.UINT64);
  private static final Entry SINT64_VALUE =
      entry(Kind.WRAPPER, "SInt64Value", TypeRef.LONG, WireScalar.SINT_64);
  private static final Entry FIXED64_VALUE =
      entry(Kind.WRAPPER, "Fixed64Value", TypeRef.LONG, WireScalar.FIXED64);
  private static final Entry SFIXED64_VALUE =
      entry(Kind.WRAPPER, "SFixed64Value", TypeRef.LONG, WireScalar.FIXED64);
  private static final Entry FLOAT_VALUE =
      entry(Kind.WRAPPER, "FloatValue", TypeRef.FLOAT, WireScalar.FLOAT);
  private static final Entry DOUBLE_VALUE =
      entry(Kind.WRAPPER, "DoubleValue", TypeRef.DOUBLE, WireScalar.DOUBLE);
  private static final Entry STRING_VALUE =
      entry(Kind.WRAPPER, "StringValue", TypeRef.STRING, WireScalar.STRING);
  private static final Entry BYTES_VALUE =
      new Entry(
          "google.protobuf.BytesValue", Kind.BYTES_WRAPPER, TypeRefs.BYTES, WireScalar.BYTES, null);

  private static final Entry STRUCT = dynamic("Struct");
  private static final Entry VALUE = dynamic("Value");
  private static final Entry LIST_VALUE = dynamic("ListValue");
  private static final Entry ANY = dynamic("Any");
  private static final Entry NULL_VALUE = dynamic("NullValue");

  private static Entry entry(Kind kind, String shortName, TypeRef javaType, WireScalar wire) {
    return new Entry("google.protobuf." + shortName, kind, javaType, wire, null);
  }

  private static Entry dynamic(String shortName) {
    return new Entry("google.protobuf." + shortName, Kind.DYNAMIC_UNSUPPORTED, null, null, null);
  }

  /**
   * Resolve a (possibly slashed) WKT type-URL to its registry entry. Returns {@code null} when the
   * value is not a well-known type.
   */
  @Nullable
  public static Entry resolve(String typeUrl) {
    if (typeUrl == null) return null;
    return REGISTRY.get(typeUrl.startsWith("/") ? typeUrl.substring(1) : typeUrl);
  }

  private static final Map<String, Entry> REGISTRY = buildRegistry();

  private static Map<String, Entry> buildRegistry() {
    Entry[] all = {
      TIMESTAMP,
      DURATION,
      FIELD_MASK,
      EMPTY_ENTRY,
      BOOL_VALUE,
      INT32_VALUE,
      UINT32_VALUE,
      SINT32_VALUE,
      FIXED32_VALUE,
      SFIXED32_VALUE,
      INT64_VALUE,
      UINT64_VALUE,
      SINT64_VALUE,
      FIXED64_VALUE,
      SFIXED64_VALUE,
      FLOAT_VALUE,
      DOUBLE_VALUE,
      STRING_VALUE,
      BYTES_VALUE,
      STRUCT,
      VALUE,
      LIST_VALUE,
      ANY,
      NULL_VALUE
    };
    Map<String, Entry> m = new HashMap<>();
    for (Entry e : all) m.put(e.fqn(), e);
    return Collections.unmodifiableMap(m);
  }
}
