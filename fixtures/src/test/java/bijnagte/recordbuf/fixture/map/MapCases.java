package bijnagte.recordbuf.fixture.map;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.map.rec.Sample;
import bijnagte.recordbuf.fixture.mapkeys.rec.Keys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden cases of family {@code map}, migrated from {@code MapTest}: {@link Sample}, a {@code
 * map<string,int32>}, a {@code map<int32,string>} and a {@code map<string,bool>}, plus {@link
 * Keys}, one map for every other legal key type. {@code populated} and {@code all-empty} are the
 * two values {@code MapTest} asserted on. {@code default-entries} writes an entry whose key and
 * value are both the type's default, which protobuf-java writes as a zero-length entry rather than
 * dropping. The {@link Keys} cases round-trip each key type, at ordinary and extreme values.
 *
 * <p>Every map is built as a {@link LinkedHashMap} in insertion order, so the map entry order on
 * the wire is the insertion order, as protobuf-java writes it. The JSON formats do not keep that
 * order: their goldens list the keys sorted.
 */
public final class MapCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "map";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-empty", Sample.builder().build())),
        build(jacksonLossless(FAMILY, "default-entries", defaultEntries())),
        build(jacksonLossless(FAMILY, "all-key-types", allKeyTypes())),
        build(jacksonLossless(FAMILY, "extreme-keys", extremeKeys())));
  }

  /** Every map non-empty, with unsorted keys: the value {@code MapTest} asserted on. */
  public static Sample populated() {
    return Sample.builder()
        .a(ordered("x", 1, "k", -2, "z", 7))
        .b(ordered(1, "one", 2, "two"))
        .c(ordered("on", true, "off", false))
        .build();
  }

  /** One entry per map, each holding the default key and the default value. */
  public static Sample defaultEntries() {
    return Sample.builder().a(ordered("", 0)).b(ordered(0, "")).c(ordered("", false)).build();
  }

  /** Two entries for every key type of {@link Keys}, the larger key first. */
  public static Keys allKeyTypes() {
    return Keys.builder()
        .i64(ordered(20L, "twenty", -10L, "minus ten"))
        .u32(ordered(30, "thirty", 3, "three"))
        .u64(ordered(40L, "forty", 4L, "four"))
        .s32(ordered(50, "fifty", -5, "minus five"))
        .s64(ordered(60L, "sixty", -6L, "minus six"))
        .f32(ordered(70, "seventy", 7, "seven"))
        .f64(ordered(80L, "eighty", 8L, "eight"))
        .sf32(ordered(90, "ninety", -9, "minus nine"))
        .sf64(ordered(100L, "hundred", -1L, "minus one"))
        .flag(ordered(true, "yes", false, "no"))
        .build();
  }

  /**
   * The smallest and largest key of each signed key type, and the largest and zero keys of the
   * unsigned and fixed-width ones, kept within the signed range of their Java carrier.
   */
  public static Keys extremeKeys() {
    return Keys.builder()
        .i64(ordered(Long.MAX_VALUE, "max", Long.MIN_VALUE, "min"))
        .u32(ordered(Integer.MAX_VALUE, "max", 0, "zero"))
        .u64(ordered(Long.MAX_VALUE, "max", 0L, "zero"))
        .s32(ordered(Integer.MAX_VALUE, "max", Integer.MIN_VALUE, "min"))
        .s64(ordered(Long.MAX_VALUE, "max", Long.MIN_VALUE, "min"))
        .f32(ordered(Integer.MAX_VALUE, "max", 0, "zero"))
        .f64(ordered(Long.MAX_VALUE, "max", 0L, "zero"))
        .sf32(ordered(Integer.MAX_VALUE, "max", Integer.MIN_VALUE, "min"))
        .sf64(ordered(Long.MAX_VALUE, "max", Long.MIN_VALUE, "min"))
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

  private static <K, V> Map<K, V> ordered(K key1, V value1, K key2, V value2, K key3, V value3) {
    Map<K, V> map = ordered(key1, value1, key2, value2);
    map.put(key3, value3);
    return map;
  }
}
