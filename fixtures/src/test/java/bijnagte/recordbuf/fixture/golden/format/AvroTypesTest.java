package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.map.BEntryAvro;
import bijnagte.recordbuf.fixture.map.SampleAvro;
import bijnagte.recordbuf.fixture.map.rec.Sample;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.junit.jupiter.api.Test;

/**
 * Tests of the map ordering in {@link AvroTypes}: the Avro golden formats encode the same text and
 * bytes for the same record value however its maps happen to be ordered, and members come out in
 * key order.
 */
class AvroTypesTest {

  private static final int MANY_KEYS = 40;

  private final AvroJsonFormat json = new AvroJsonFormat();
  private final AvroBinaryFormat binary = new AvroBinaryFormat();

  /** Inserts {@code MANY_KEYS} string keys in ascending order, or in descending order. */
  private static Map<String, Integer> stringKeyed(boolean descending) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < MANY_KEYS; i++) {
      indices.add(i);
    }
    if (descending) {
      Collections.reverse(indices);
    }
    Map<String, Integer> map = new LinkedHashMap<>();
    for (int i : indices) {
      map.put("key-" + i, i);
    }
    return map;
  }

  private static Map<Integer, String> intKeyed(boolean descending) {
    List<Integer> keys = new ArrayList<>();
    for (int i = -MANY_KEYS / 2; i < MANY_KEYS / 2; i++) {
      keys.add(i);
    }
    if (descending) {
      Collections.reverse(keys);
    }
    Map<Integer, String> map = new LinkedHashMap<>();
    for (int key : keys) {
      map.put(key, "v" + key);
    }
    return map;
  }

  private static Sample sample(boolean descending) {
    return Sample.builder()
        .a(stringKeyed(descending))
        .b(intKeyed(descending))
        .c(Map.of("on", true, "off", false))
        .build();
  }

  /** Maps given in opposite insertion orders, and across runs, give identical text and bytes. */
  @Test
  void differentlyOrderedMaps_encodeIdentically() throws Exception {
    Sample ascending = sample(false);
    Sample descending = sample(true);

    assertEquals(ascending, descending);
    assertEquals(json.serialize(ascending), json.serialize(descending));
    assertArrayEquals(binary.serialize(ascending), binary.serialize(descending));
    for (int run = 0; run < 5; run++) {
      assertEquals(json.serialize(ascending), json.serialize(sample(run % 2 == 0)));
      assertArrayEquals(binary.serialize(ascending), binary.serialize(sample(run % 2 == 0)));
    }
  }

  /** String-keyed maps and the entry arrays of an int-keyed map come out key-sorted in the text. */
  @Test
  void json_listsMapMembersInKeyOrder() throws Exception {
    Sample sample =
        Sample.builder()
            .a(Map.of("x", 1, "k", -2, "z", 7))
            .b(Map.of(2, "two", 1, "one", -3, "minus"))
            .c(Map.of("on", true, "off", false))
            .build();

    assertEquals(
        "{\"a\":{\"k\":-2,\"x\":1,\"z\":7},"
            + "\"b\":[{\"key\":-3,\"value\":\"minus\"},{\"key\":1,\"value\":\"one\"},"
            + "{\"key\":2,\"value\":\"two\"}],"
            + "\"c\":{\"off\":false,\"on\":true},\"unknownFields\":[]}",
        json.serialize(sample));
  }

  /** The binary golden equals what Avro writes for the same value built by hand in key order. */
  @Test
  void binary_equalsAvroEncodingOfTheKeySortedSpecificRecord() throws Exception {
    Sample sample =
        Sample.builder()
            .a(Map.of("x", 1, "k", -2, "z", 7))
            .b(Map.of(2, "two", 1, "one"))
            .c(Map.of("on", true, "off", false))
            .build();
    Map<CharSequence, Integer> sortedA = new LinkedHashMap<>();
    sortedA.put("k", -2);
    sortedA.put("x", 1);
    sortedA.put("z", 7);
    Map<CharSequence, Boolean> sortedC = new LinkedHashMap<>();
    sortedC.put("off", false);
    sortedC.put("on", true);
    SampleAvro expected = new SampleAvro();
    expected.setA(sortedA);
    expected.setB(List.of(new BEntryAvro(1, "one"), new BEntryAvro(2, "two")));
    expected.setC(sortedC);
    expected.setUnknownFields(List.of());
    ByteBuffer encoded = expected.toByteBuffer();
    byte[] expectedBytes = new byte[encoded.remaining()];
    encoded.get(expectedBytes);

    assertArrayEquals(expectedBytes, binary.serialize(sample));
  }

  private static final Schema NESTED =
      new Schema.Parser()
          .parse(
              """
              {"type":"record","name":"Outer","fields":[
                {"name":"m","type":{"type":"map","values":{"type":"map","values":"int"}}},
                {"name":"items","type":{"type":"array","items":{"type":"record","name":"Item",
                  "fields":[{"name":"u","type":["null",{"type":"map","values":"int"}]}]}}},
                {"name":"pairs","type":{"type":"array","items":{"type":"record","name":"Pair",
                  "fields":[{"name":"key","type":"int"},{"name":"value","type":"string"}]}}}
              ]}
              """);

  private static Map<CharSequence, Object> descendingUtf8(Object innerValue, String... keys) {
    Map<CharSequence, Object> map = new LinkedHashMap<>();
    for (String key : keys) {
      map.put(new Utf8(key), innerValue);
    }
    return map;
  }

  private static List<String> keyOrder(Map<?, ?> map) {
    return map.keySet().stream().map(Object::toString).toList();
  }

  /** Maps are ordered at every depth: map in map, map in a union in a record in an array. */
  @Test
  void withOrderedMaps_ordersNestedMapsWithoutTouchingTheArgument() {
    Map<CharSequence, Object> inner = descendingUtf8(1, "c", "b", "a");
    Map<CharSequence, Object> outer = descendingUtf8(inner, "z", "y");
    GenericRecord item = new GenericData.Record(NESTED.getField("items").schema().getElementType());
    item.put("u", descendingUtf8(2, "q", "p"));
    GenericRecord unionNull = new GenericData.Record(item.getSchema());
    unionNull.put("u", null);
    GenericRecord datum = new GenericData.Record(NESTED);
    datum.put("m", outer);
    datum.put("items", List.of(item, unionNull));
    datum.put("pairs", List.of());

    GenericRecord ordered = (GenericRecord) AvroTypes.withOrderedMaps(NESTED, datum);

    Map<?, ?> orderedOuter = (Map<?, ?>) ordered.get("m");
    assertEquals(List.of("y", "z"), keyOrder(orderedOuter));
    assertEquals(
        List.of("a", "b", "c"), keyOrder((Map<?, ?>) orderedOuter.values().iterator().next()));
    List<?> orderedItems = (List<?>) ordered.get("items");
    assertEquals(
        List.of("p", "q"), keyOrder((Map<?, ?>) ((GenericRecord) orderedItems.get(0)).get("u")));
    assertEquals(null, ((GenericRecord) orderedItems.get(1)).get("u"));

    assertEquals(List.of("z", "y"), keyOrder(outer));
    assertEquals(List.of("c", "b", "a"), keyOrder(inner));
  }

  /** An array of records is sorted only when it is the map-entry array of its field. */
  @Test
  void withOrderedMaps_leavesOtherKeyValueArraysInOrder() {
    Schema pair = NESTED.getField("pairs").schema().getElementType();
    GenericRecord second = new GenericData.Record(pair);
    second.put("key", 2);
    second.put("value", "two");
    GenericRecord first = new GenericData.Record(pair);
    first.put("key", 1);
    first.put("value", "one");
    GenericRecord datum = new GenericData.Record(NESTED);
    datum.put("m", Map.of());
    datum.put("items", List.of());
    datum.put("pairs", List.of(second, first));

    GenericRecord ordered = (GenericRecord) AvroTypes.withOrderedMaps(NESTED, datum);

    List<?> pairs = (List<?>) ordered.get("pairs");
    assertEquals(2, ((GenericRecord) pairs.get(0)).get("key"));
    assertEquals(1, ((GenericRecord) pairs.get(1)).get("key"));
  }
}
