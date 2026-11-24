package bijnagte.recordbuf.fixture.mapvalues;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues;
import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues.Color;
import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues.Nested;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden cases of family {@code mapvalues}, testing map values that are bytes, a message or an
 * enum. {@link MapValues} has one map for each, plus a map with an {@code int32} key and a message
 * value. {@code populated} fills every map. {@code default-values} writes entries whose value is
 * the type's default (empty bytes, the default message, the zero enum), which protobuf-java writes
 * as an entry with an empty or zero value rather than dropping. {@code all-empty} sets no map.
 * Every case round-trips through the protobuf binary and Jackson formats; the Interop and Avro
 * formats do not apply, because this probe has neither.
 *
 * <p>Every map is built as a {@link LinkedHashMap} in insertion order, so the map entry order on
 * the wire is the insertion order, as protobuf-java writes it. The Jackson golden lists the keys
 * sorted.
 */
public final class MapValuesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "mapvalues";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "default-values", defaultValues())),
        build(jacksonLossless(FAMILY, "all-empty", MapValues.builder().build())));
  }

  /** Every map holds two entries, the larger key first. */
  public static MapValues populated() {
    return MapValues.builder()
        .mapStringBytes(ordered("y", Bytes.of(new byte[] {1, 2, 3}), "x", Bytes.of(new byte[] {9})))
        .mapStringNested(
            ordered(
                "y",
                Nested.builder().a(7).label("seven").build(),
                "x",
                Nested.builder().a(-1).build()))
        .mapStringColor(ordered("y", Color.BLUE, "x", Color.GREEN))
        .mapIntNested(
            ordered(20, Nested.builder().label("twenty").build(), 3, Nested.builder().a(3).build()))
        .build();
  }

  /** One entry per map, each holding the default value. */
  public static MapValues defaultValues() {
    return MapValues.builder()
        .mapStringBytes(ordered("k", Bytes.empty()))
        .mapStringNested(ordered("k", Nested.builder().build()))
        .mapStringColor(ordered("k", Color.RED))
        .mapIntNested(ordered(0, Nested.builder().build()))
        .build();
  }

  private static <K, V> Map<K, V> ordered(K key1, V value1) {
    Map<K, V> map = new LinkedHashMap<>();
    map.put(key1, value1);
    return map;
  }

  private static <K, V> Map<K, V> ordered(K key1, V value1, K key2, V value2) {
    Map<K, V> map = ordered(key1, value1);
    map.put(key2, value2);
    return map;
  }
}
