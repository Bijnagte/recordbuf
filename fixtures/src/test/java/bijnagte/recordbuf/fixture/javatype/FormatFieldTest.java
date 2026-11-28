package bijnagte.recordbuf.fixture.javatype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.javatype.rec.FormatFields;
import bijnagte.recordbuf.fixture.javatype.rec.FormatFields.FormatFieldsCodec;
import bijnagte.recordbuf.fixture.javatype.rec.RepeatedFormatFields;
import bijnagte.recordbuf.fixture.javatype.rec.RepeatedFormatFields.RepeatedFormatFieldsCodec;
import com.github.f4b6a3.ulid.Ulid;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Structural checks for the {@code javatype} family (deliberately not goldens): the record
 * components are the Java types the options name, absent fields decode to null (and the plain
 * string to its default), an unparseable wire string fails loud, and the oneof's format member
 * unwraps to its named type.
 */
class FormatFieldTest {

  private static final FormatCases CASES = new FormatCases();

  // Every case is checked forward and reverse in each format it applies to; this is what
  // covers the family's avro / Interop / Jackson rec output.
  @TestFactory
  Stream<DynamicNode> formatCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), CASES.formats()).tests(CASES.cases());
  }

  @Test
  void components_are_the_java_types_named_by_the_options() throws IOException {
    FormatFields fields =
        FormatFields.builder()
            .uid(UUID.fromString("9b2f3c1e-4d5a-7f60-8e9b-1c2d3e4f5a6b"))
            .ref(URI.create("https://example.com/pets/42"))
            .build();

    // Compile-time shape: these assignments only type-check if the components are UUID and URI.
    UUID uid = fields.uid();
    URI ref = fields.ref();

    FormatFields roundTrip = FormatFieldsCodec.decode(FormatFieldsCodec.encode(fields));
    assertEquals(uid, roundTrip.uid());
    assertEquals(ref, roundTrip.ref());
    assertNull(roundTrip.token());
    assertNull(roundTrip.dur());
    assertNull(roundTrip.contact());
  }

  @Test
  void absent_fields_decode_to_null_and_the_plain_string_to_its_default() throws IOException {
    FormatFields fields =
        FormatFieldsCodec.decode(FormatFieldsCodec.encode(FormatFields.builder().build()));
    assertNull(fields.uid());
    assertNull(fields.ref());
    assertNull(fields.token());
    assertNull(fields.dur());
    assertEquals("", fields.label());
    assertNull(fields.contact());
  }

  @Test
  void unparseable_wire_string_fails_loud() throws IOException {
    // Field 1 is uid (UUID): the wire carries a string the format cannot parse.
    byte[] notAUuid = stringFieldBytes(1, "not-a-uuid");
    assertThrows(IllegalArgumentException.class, () -> FormatFieldsCodec.decode(notAUuid));

    // Field 6 is the oneof's homepage member (URI).
    byte[] notAUri = stringFieldBytes(6, "ht!tp://nope");
    assertThrows(IllegalArgumentException.class, () -> FormatFieldsCodec.decode(notAUri));
  }

  @Test
  void unregistered_type_fails_loud_before_any_parsing() throws IOException {
    // Field 4 is dur (java.time.Duration): no format is registered for it, so decode fails on the
    // registry miss even for a well-formed wire string.
    byte[] present = stringFieldBytes(4, "PT0S");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> FormatFieldsCodec.decode(present));
    String msg = e.getMessage();
    assertNotNull(msg);
    assertTrue(msg.contains("java.time.Duration"), msg);
  }

  @Test
  void oneof_member_round_trips_and_unwraps_to_its_named_type() throws IOException {
    FormatFields fields =
        FormatFields.builder()
            .contact(new FormatFields.Contact.HomepageValue(URI.create("https://example.com")))
            .build();

    FormatFields roundTrip = FormatFieldsCodec.decode(FormatFieldsCodec.encode(fields));
    if (!(roundTrip.contact() instanceof FormatFields.Contact.HomepageValue homepage)) {
      throw new AssertionError("the oneof member was lost: " + roundTrip.contact());
    }
    assertEquals(URI.create("https://example.com"), homepage.value());
  }

  @Test
  void repeated_fields_round_trip_and_the_wire_carries_the_strings() throws IOException {
    List<URI> links = List.of(URI.create("https://example.com/pets/42"));
    List<Ulid> tokens = List.of(Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FAV"));

    RepeatedFormatFields fields =
        RepeatedFormatFields.builder().links(links).tokens(tokens).build();
    byte[] wire = RepeatedFormatFieldsCodec.encode(fields);

    // Compile-time shape: the components are List<URI> and List<Ulid>.
    assertEquals(links, fields.links());
    assertEquals(tokens, fields.tokens());

    // The wire carries the format's string form, not the typed values.
    byte[] linkBytes = "https://example.com/pets/42".getBytes(StandardCharsets.UTF_8);
    byte[] tokenBytes = "01ARZ3NDEKTSV4RRFFQ69G5FAV".getBytes(StandardCharsets.UTF_8);
    assertTrue(contains(wire, linkBytes), "wire should carry the URI string");
    assertTrue(contains(wire, tokenBytes), "wire should carry the ULID string");

    RepeatedFormatFields roundTrip = RepeatedFormatFieldsCodec.decode(wire);
    assertEquals(fields, roundTrip);
  }

  @Test
  void repeated_absent_is_an_empty_list() throws IOException {
    RepeatedFormatFields fields =
        RepeatedFormatFieldsCodec.decode(
            RepeatedFormatFieldsCodec.encode(RepeatedFormatFields.builder().build()));
    assertTrue(fields.links().isEmpty());
    assertTrue(fields.tokens().isEmpty());
  }

  @Test
  void repeated_unparseable_element_fails_loud() throws IOException {
    // Field 1 is links (repeated URI): one bad element poisons the decode.
    byte[] notAUri = stringFieldBytes(1, "ht!tp://nope");
    assertThrows(IllegalArgumentException.class, () -> RepeatedFormatFieldsCodec.decode(notAUri));
  }

  /** Raw wire bytes: a single string field {@code number} carrying {@code text}. */
  private static byte[] stringFieldBytes(int number, String text) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CodedOutputStream stream = CodedOutputStream.newInstance(out);
    stream.writeString(number, text);
    stream.flush();
    return out.toByteArray();
  }

  /** Whether {@code haystack} contains {@code needle} as a contiguous byte run. */
  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }
}
