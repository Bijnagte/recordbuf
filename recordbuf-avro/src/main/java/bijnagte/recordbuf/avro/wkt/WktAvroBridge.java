package bijnagte.recordbuf.avro.wkt;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.SchemaParser;
import org.jspecify.annotations.Nullable;

/**
 * The canonical Avro <em>bridge</em> for the recordbuf well-known types: a registry of the WKT
 * type-URLs the bridge recognizes, each mapped to an Avro {@link Schema} fragment (native logical
 * types per KTD4) and a value conversion the generated converter delegates to. Every generated WKT
 * converter reuses this bridge rather than re-open-coding the mapping (R8) — the bridge is the only
 * place that knows about WKTs, so the record layer stays WKT-agnostic (R10).
 *
 * <p>Mappings (KTD4 / R9):
 *
 * <ul>
 *   <li>{@code Timestamp} → {@code {"type":"long","logicalType":"timestamp-micros"}}; the value is
 *       epoch microseconds {@code seconds*1_000_000 + nanos/1000} (lossy to the microsecond).
 *   <li>{@code Duration} → the structured record {@code {seconds: long, nanos: int}} ({@link
 *       DurationValue}), NOT the micros-only logical {@code duration} — full nanos preserved (AE2).
 *   <li>{@code FieldMask} → {@code ["null","string"]} (null ⇒ empty mask; string ⇒ the paths).
 *   <li>{@code NullValue} → {@code ["null","enum"]} with the proto3 {@code NULL_VALUE/TRUE/FALSE}.
 *   <li>{@code Empty} → an empty record {@code {}}.
 *   <li>The scalar wrappers ({@code *Value}) → {@code ["null", <primitive>]}.
 * </ul>
 *
 * <p>The dynamic WKTs ({@code Struct}, {@code Value}, {@code ListValue}, {@code Any}) are
 * recognized as <em>not-yet-supported</em> ({@link #isUnrecognizedDynamic(String)}) so the
 * generator fails loud (R11/R17) rather than silently coercing them.
 */
public final class WktAvroBridge {

  // The well-known-types type-URLs (without the leading "/") the bridge recognizes for v1.
  public static final String TIMESTAMP = "google.protobuf.Timestamp";
  public static final String DURATION = "google.protobuf.Duration";
  public static final String FIELD_MASK = "google.protobuf.FieldMask";
  public static final String NULL_VALUE = "google.protobuf.NullValue";
  public static final String EMPTY = "google.protobuf.Empty";
  public static final String INT32_VALUE = "google.protobuf.Int32Value";
  public static final String INT64_VALUE = "google.protobuf.Int64Value";
  public static final String UINT32_VALUE = "google.protobuf.UInt32Value";
  public static final String UINT64_VALUE = "google.protobuf.UInt64Value";
  public static final String FLOAT_VALUE = "google.protobuf.FloatValue";
  public static final String DOUBLE_VALUE = "google.protobuf.DoubleValue";
  public static final String BOOL_VALUE = "google.protobuf.BoolValue";
  public static final String STRING_VALUE = "google.protobuf.StringValue";
  public static final String BYTES_VALUE = "google.protobuf.BytesValue";

  // The dynamic WKTs out of scope for v1 (R11): a field carrying one is a generation error.
  private static final Set<String> DYNAMIC_WKTS =
      Set.of(
          "google.protobuf.Struct",
          "google.protobuf.Value",
          "google.protobuf.ListValue",
          "google.protobuf.Any");

  private WktAvroBridge() {}

  // --- recognition ---

  /**
   * Whether the WKT type-URL ({@code google.protobuf.X}) is a WKT the bridge recognizes (its schema
   * fragment and any conversion exist). A recognized-but-dynamic WKT ({@code Struct}/...) is NOT
   * here — it is reported via {@link #isUnrecognizedDynamic(String)}.
   */
  public static boolean isRecognized(@Nullable String typeUrl) {
    return typeUrl != null && FRAGMENTS.containsKey(normalize(typeUrl));
  }

  /** Whether the WKT type-URL is a v1-out-of-scope dynamic WKT (R11): a generation error. */
  public static boolean isUnrecognizedDynamic(@Nullable String typeUrl) {
    return typeUrl != null && DYNAMIC_WKTS.contains(normalize(typeUrl));
  }

  private static String normalize(String typeUrl) {
    return typeUrl.startsWith("/") ? typeUrl.substring(1) : typeUrl;
  }

  // --- schema fragments (native Avro logical types; KTD4) ---

  private static final Map<String, Schema> FRAGMENTS = fragmentMap();

  private static Map<String, Schema> fragmentMap() {
    Map<String, Schema> m = new LinkedHashMap<>();
    m.put(
        TIMESTAMP,
        SchemaParser.parseSingle("{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}"));
    m.put(
        DURATION,
        SchemaParser.parseSingle(
            "{\"type\":\"record\",\"name\":\"Duration\",\"fields\":["
                + "{\"name\":\"seconds\",\"type\":\"long\"},"
                + "{\"name\":\"nanos\",\"type\":\"int\"}]}"));
    m.put(FIELD_MASK, SchemaParser.parseSingle("[\"null\",\"string\"]"));
    m.put(
        NULL_VALUE,
        SchemaParser.parseSingle(
            "[\"null\",{\"type\":\"enum\",\"name\":\"NullValue\",\"symbols\":[\"NULL_VALUE\",\"TRUE\",\"FALSE\"]}]"));
    m.put(
        EMPTY, SchemaParser.parseSingle("{\"type\":\"record\",\"name\":\"Empty\",\"fields\":[]}"));
    m.put(INT32_VALUE, SchemaParser.parseSingle("[\"null\",\"int\"]"));
    m.put(INT64_VALUE, SchemaParser.parseSingle("[\"null\",\"long\"]"));
    m.put(UINT32_VALUE, SchemaParser.parseSingle("[\"null\",\"int\"]"));
    m.put(UINT64_VALUE, SchemaParser.parseSingle("[\"null\",\"long\"]"));
    m.put(FLOAT_VALUE, SchemaParser.parseSingle("[\"null\",\"float\"]"));
    m.put(DOUBLE_VALUE, SchemaParser.parseSingle("[\"null\",\"double\"]"));
    m.put(BOOL_VALUE, SchemaParser.parseSingle("[\"null\",\"boolean\"]"));
    m.put(STRING_VALUE, SchemaParser.parseSingle("[\"null\",\"string\"]"));
    m.put(BYTES_VALUE, SchemaParser.parseSingle("[\"null\",\"bytes\"]"));
    return Map.copyOf(m);
  }

  /** The Avro schema fragment for a recognized WKT type-URL; {@code null} when unrecognized. */
  @Nullable
  public static Schema fragment(@Nullable String typeUrl) {
    return typeUrl == null ? null : FRAGMENTS.get(normalize(typeUrl));
  }

  // --- value conversions (record fields of the WKT <-> the Avro datum) ---

  // Timestamp: proto fields (seconds, nanos) <-> epoch micros (lossy to the microsecond; AE1).
  public static long timestampToMicros(long seconds, int nanos) {
    return seconds * 1_000_000L + nanos / 1000;
  }

  /** {@code micros} → {@code {seconds, nanos}} (the sub-second remainder as {@code 0..999999}). */
  public static long[] timestampFromMicros(long micros) {
    long secs = Math.floorDiv(micros, 1_000_000L);
    int nanos = (int) (Math.floorMod(micros, 1_000_000L) * 1000L);
    return new long[] {secs, nanos};
  }

  // Duration: the structured pair is the same shape on both sides (AE2: lossless, >1s nanos OK).
  public static DurationValue durationToValue(long seconds, int nanos) {
    return new DurationValue(seconds, nanos);
  }

  public static long[] durationFromValue(@Nullable DurationValue v) {
    if (v == null) return new long[] {0L, 0L};
    return new long[] {v.seconds(), v.nanos()};
  }

  // FieldMask: paths <-> [null, string]; null == empty mask.
  @Nullable
  public static String fieldMaskToAvro(@Nullable List<String> paths) {
    return (paths == null || paths.isEmpty()) ? null : String.join(",", paths);
  }

  public static List<String> fieldMaskFromAvro(@Nullable String avro) {
    return (avro == null || avro.isBlank()) ? List.of() : Arrays.asList(avro.split(","));
  }
}
