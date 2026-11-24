package bijnagte.recordbuf.fixture.avromapvalues;

import bijnagte.recordbuf.fixture.avromapvalues.rec.MapValues;
import bijnagte.recordbuf.fixture.avromapvalues.rec.MapValues.Color;
import bijnagte.recordbuf.fixture.avromapvalues.rec.MapValues.Nested;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden cases of family {@code avromapvalues}, testing map values that are bytes, a message or
 * an enum through the Avro converter. {@link MapValues} has one map for each, plus a map with an
 * {@code int32} key and a message value (an Avro pair-entry array). {@code populated} fills every
 * map, with a message value that is the default instance and an enum value that is the zero
 * constant. {@code all-empty} sets no map. Proto3 JSON is not applicable: the file has no Interop
 * converter.
 *
 * <p>Every map is built as a {@link LinkedHashMap} in insertion order.
 */
public final class AvroMapValuesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avromapvalues";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-empty", MapValues.builder().build())));
  }

  /** Every map holds two entries, the larger key first. */
  public static MapValues populated() {
    return MapValues.builder()
        .mapStringBytes(ordered("y", Bytes.of(new byte[] {1, 2, 3}), "x", Bytes.of(new byte[] {9})))
        .mapStringNested(
            ordered(
                "y", Nested.builder().a(7).label("seven").build(), "x", Nested.builder().build()))
        .mapStringColor(ordered("y", Color.BLUE, "x", Color.RED))
        .mapIntNested(
            ordered(20, Nested.builder().label("twenty").build(), 3, Nested.builder().a(3).build()))
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
