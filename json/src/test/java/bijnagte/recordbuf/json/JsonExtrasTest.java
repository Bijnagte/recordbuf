package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.runtime.UnknownField;
import bijnagte.recordbuf.runtime.UnknownFields;
import bijnagte.recordbuf.runtime.WireType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * U6 of the 2026-11-20 Jackson plan: the retained unknown JSON properties fold into the binary
 * unknown-field carrier on encode.
 */
final class JsonExtrasTest {

  @Test
  void nullOrEmptyExtrasFoldToTheCarrierUntouched() {
    UnknownFields carrier = UnknownFields.of(List.of());
    assertSame(carrier, JsonExtras.fold(carrier, null));
    assertSame(carrier, JsonExtras.fold(carrier, Map.of()));
  }

  @Test
  void foldAddsOneLengthDelimitedEntryPerRetainedProperty() {
    UnknownFields folded =
        JsonExtras.fold(UnknownFields.of(List.of()), Map.of("answer", TextNode.valueOf("42")));
    assertEquals(1, folded.fields().size());
    UnknownField entry = folded.fields().get(0);
    assertEquals(WireType.LENGTH_DELIMITED, entry.wireType());
    assertEquals(JsonExtras.fieldNumber("answer"), entry.fieldNumber());
    // the text node's JSON form is "42" — four bytes: varint length 0x04, then the content.
    assertArrayEquals(new byte[] {4, '"', '4', '2', '"'}, entry.value());
  }

  @Test
  void lengthDelimitedFramesLongerContentWithAMultiByteVarint() {
    byte[] content = new byte[130];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) i;
    }
    byte[] framed = JsonExtras.lengthDelimited(content);
    // 130 = 0x82 first byte (continuation bit set), 0x01 second.
    assertEquals(132, framed.length);
    assertEquals((byte) 0x82, framed[0]);
    assertEquals((byte) 0x01, framed[1]);
    assertArrayEquals(content, Arrays.copyOfRange(framed, 2, framed.length));
  }

  @Test
  void reFoldingTheSamePropertiesIsIdempotent() {
    Map<String, JsonNode> extras = Map.of("a", TextNode.valueOf("1"), "b", TextNode.valueOf("2"));
    UnknownFields once = JsonExtras.fold(UnknownFields.of(List.of()), extras);
    UnknownFields twice = JsonExtras.fold(once, extras);
    assertEquals(once, twice);
    assertEquals(2, twice.fields().size());
  }

  @Test
  void fieldNumberIsDeterministicAndInThePositive31BitRange() {
    assertEquals(JsonExtras.fieldNumber("answer"), JsonExtras.fieldNumber("answer"));
    int number = JsonExtras.fieldNumber("answer");
    assertTrue(number < (1 << 29), "field numbers stay in the proto3 29-bit range");
    assertTrue(
        JsonExtras.fieldNumber("answer") != JsonExtras.fieldNumber("answe"),
        "distinct names fold distinct");
  }
}
