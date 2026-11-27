package bijnagte.recordbuf.fixture.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import bijnagte.recordbuf.fixture.combined.rec.Compat.CompatCodec;
import bijnagte.recordbuf.fixture.combined.rec.Sub;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Interop;
import bijnagte.recordbuf.runtime.UnknownFields;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A message field that occurs more than once on the wire merges into the one already read, as in a
 * protobuf parser: the later occurrence's set fields override, its repeated fields append, and the
 * unknown fields of both are kept. {@code protobuf-java} parses the same bytes, so it is the
 * oracle.
 *
 * <p>{@code decodeInto(base, bytes)} is the entry point the field arms use; it parses the base's
 * serialized form followed by the new bytes, which is protobuf's own definition of a merge. {@code
 * TestAllTypesProto3} (the wide builder path) is covered by the conformance mirrors.
 */
class MergeTest {

  private interface Writer {
    void write(CodedOutputStream out) throws IOException;
  }

  private static byte[] bytes(Writer writer) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    writer.write(out);
    out.flush();
    return bytes.toByteArray();
  }

  /** {@code Sub{id, label}} on the wire, either part optional. */
  private static byte[] sub(Integer id, String label, Integer unknownVarint) throws IOException {
    return bytes(out -> {
      if (id != null) {
        out.writeInt32(1, id);
      }
      if (label != null) {
        out.writeString(2, label);
      }
      if (unknownVarint != null) {
        out.writeInt32(99, unknownVarint);
      }
    });
  }

  @Test
  void singularMessage_mergesFieldByFieldAndKeepsBothUnknownFields() throws Exception {
    byte[] wire =
        bytes(out -> {
          out.writeByteArray(6, sub(7, "seven", 1));
          out.writeByteArray(6, sub(null, "eight", 2));
        });

    Compat decoded = Compat.fromBytes(wire);

    Sub nested = decoded.nested();
    assertEquals(7, nested.id(), "the first occurrence's id survives");
    assertEquals("eight", nested.label(), "the second occurrence overrides the label");
    assertEquals(2, nested.unknownFields().fields().size(), "both unknown fields are kept");
    assertEquals(
        bijnagte.recordbuf.fixture.combined.Compat.parseFrom(wire),
        Compat.Interop.to(decoded),
        "protobuf-java merges the same way");
  }

  @Test
  void oneofMessageMember_mergesIntoTheMemberAlreadyHeld() throws Exception {
    byte[] wire =
        bytes(out -> {
          out.writeByteArray(3, bytes(o -> o.writeString(1, "abc")));
          out.writeByteArray(3, bytes(o -> o.writeInt32(99, 5)));
        });

    var decoded = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(wire);

    var item = (bijnagte.recordbuf.fixture.oneof.rec.Sub) decoded.choice();
    assertEquals("abc", item.text(), "the second, empty occurrence keeps the text");
    assertEquals(1, item.unknownFields().fields().size());
    assertEquals(bijnagte.recordbuf.fixture.oneof.Compat.parseFrom(wire), Interop.to(decoded));
  }

  @Test
  void decodeInto_startsFromTheBaseRecord() throws Exception {
    Compat base =
        Compat.builder()
            .i(1)
            .s("base")
            .packed(List.of(1, 2))
            .subs(List.of(Sub.builder().id(1).build()))
            .m(Map.of("a", 1))
            .presence(5)
            .build();
    byte[] more =
        bytes(out -> {
          out.writeInt32(1, 2);
          out.writeByteArray(7, sub(2, null, null));
          out.writeByteArray(8, bytes(entry -> entryOf(entry, "b", 2)));
          out.writeInt32(98, 9);
        });

    Compat merged = CompatCodec.decodeInto(base, more);

    assertEquals(2, merged.i(), "a field set in the later bytes overrides");
    assertEquals("base", merged.s(), "a field the later bytes leave out keeps the base");
    assertEquals(5, merged.presence());
    assertEquals(2, merged.subs().size(), "repeated fields append");
    assertEquals(Map.of("a", 1, "b", 2), merged.m(), "map entries add");
    assertEquals(1, merged.unknownFields().fields().size());
    assertEquals(List.of(1, 2), base.packed(), "the base record is not changed");
    assertEquals(1, base.subs().size());
  }

  @Test
  void decodeInto_withNoBaseIsAPlainDecode() throws Exception {
    byte[] wire = bytes(out -> out.writeInt32(1, 3));

    assertEquals(Compat.fromBytes(wire), CompatCodec.decodeInto(null, wire));
    assertEquals(UnknownFields.empty(), CompatCodec.decodeInto(null, wire).unknownFields());
  }

  private static void entryOf(CodedOutputStream out, String key, int value) throws IOException {
    out.writeString(1, key);
    out.writeInt32(2, value);
  }
}
