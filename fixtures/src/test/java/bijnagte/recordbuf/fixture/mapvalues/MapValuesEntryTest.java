package bijnagte.recordbuf.fixture.mapvalues;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues;
import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues.Color;
import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues.Nested;
import bijnagte.recordbuf.runtime.Bytes;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Map entries with a missing key or value, from hand-built bytes: the entry codec decodes a missing
 * value to the type's default and a missing key to the key type's default. protobuf-java decodes
 * the same bytes to the same maps, which is asserted alongside.
 */
class MapValuesEntryTest {

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }

  @Test
  void entryWithoutValue_decodesToTheDefaultValue() throws IOException {
    // Field 1..4, each an entry that holds only the key ("k", or 5 for the int32 key).
    byte[] wire =
        bytes(
            0x0A, 0x03, 0x0A, 0x01, 0x6B, // map_string_bytes {"k"}
            0x12, 0x03, 0x0A, 0x01, 0x6B, // map_string_nested {"k"}
            0x1A, 0x03, 0x0A, 0x01, 0x6B, // map_string_color {"k"}
            0x22, 0x02, 0x08, 0x05); // map_int_nested {5}

    MapValues decoded = MapValues.fromBytes(wire);

    assertEquals(Map.of("k", Bytes.empty()), decoded.mapStringBytes());
    assertEquals(Map.of("k", Nested.builder().build()), decoded.mapStringNested());
    assertEquals(Map.of("k", Color.RED), decoded.mapStringColor());
    assertEquals(Map.of(5, Nested.builder().build()), decoded.mapIntNested());

    var reference = bijnagte.recordbuf.fixture.mapvalues.MapValues.parseFrom(wire);
    assertEquals(1, reference.getMapStringBytesMap().size());
    assertEquals(0, reference.getMapStringBytesMap().get("k").size());
    assertEquals(
        bijnagte.recordbuf.fixture.mapvalues.MapValues.Nested.getDefaultInstance(),
        reference.getMapStringNestedMap().get("k"));
    assertEquals(
        bijnagte.recordbuf.fixture.mapvalues.MapValues.Color.RED,
        reference.getMapStringColorMap().get("k"));
    assertEquals(
        bijnagte.recordbuf.fixture.mapvalues.MapValues.Nested.getDefaultInstance(),
        reference.getMapIntNestedMap().get(5));
  }

  @Test
  void entryWithoutKey_decodesToTheDefaultKey() throws IOException {
    // Field 1..4, each an entry that holds only the value.
    byte[] wire =
        bytes(
            0x0A, 0x04, 0x12, 0x02, 0x01, 0x02, // map_string_bytes {"" -> 01 02}
            0x12, 0x04, 0x12, 0x02, 0x08, 0x03, // map_string_nested {"" -> a=3}
            0x1A, 0x02, 0x10, 0x02, // map_string_color {"" -> BLUE}
            0x22, 0x04, 0x12, 0x02, 0x08, 0x03); // map_int_nested {0 -> a=3}

    MapValues decoded = MapValues.fromBytes(wire);

    assertEquals(Map.of("", Bytes.of(new byte[] {1, 2})), decoded.mapStringBytes());
    assertEquals(Map.of("", Nested.builder().a(3).build()), decoded.mapStringNested());
    assertEquals(Map.of("", Color.BLUE), decoded.mapStringColor());
    assertEquals(Map.of(0, Nested.builder().a(3).build()), decoded.mapIntNested());

    var reference = bijnagte.recordbuf.fixture.mapvalues.MapValues.parseFrom(wire);
    assertEquals(2, reference.getMapStringBytesMap().get("").size());
    assertEquals(3, reference.getMapStringNestedMap().get("").getA());
    assertEquals(
        bijnagte.recordbuf.fixture.mapvalues.MapValues.Color.BLUE,
        reference.getMapStringColorMap().get(""));
    assertEquals(3, reference.getMapIntNestedMap().get(0).getA());
  }
}
