package bijnagte.recordbuf.fixture.canonicalformat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Protobuf CANONICAL JSON for the generated .rec records. The record crosses to the protobuf-java
 * reference through its generated wire codec ({@code toByteArray} -> {@code parseFrom}) and is then
 * printed with protobuf's canonical-JSON printer; the output must equal a committed golden file
 * EXACTLY (member order and number rendering included), not merely as an equal tree.
 *
 * <p>Which "canonical" is asserted: the proto3 JSON mapping rendered with every field in
 * FIELD-NUMBER order, map KEYS sorted, and whitespace insignificant (rendered compactly). That is
 * the shape protobuf's canonical-JSON tools produce for a message -- stable across producer
 * implementations given the same schema and values, and reproducible from any other producer's
 * output.
 */
class ProtoCanonicalJsonTest {

  private static JsonFormat.Printer canonicalPrinter() {
    return JsonFormat.printer().omittingInsignificantWhitespace().sortingMapKeys();
  }

  /** One field per canonical-rendering aspect; see {@code canonical_format.proto}. */
  private static bijnagte.recordbuf.fixture.canonicalformat.rec.Sample probeRecord() {
    // Insertion order deliberately unsorted (z, a, m): canonical form must sort the keys.
    LinkedHashMap<String, Integer> attrs = new LinkedHashMap<>();
    attrs.put("z", 1);
    attrs.put("a", 2);
    attrs.put("m", 3);

    return bijnagte.recordbuf.fixture.canonicalformat.rec.Sample.builder()
        .i32(-42)
        .i64(9007199254740993L) // 2^53 + 1: beyond double precision, must survive as a string
        .u64(9223372036854775807L) // max uint64
        .s32(-1)
        .f64(7L)
        .flt(0.5F)
        .dbl(0.1D) // shortest round-trip decimal must be exactly "0.1"
        .flag(true)
        .text("say \"hi\" \\ é A") // quotes + backslash escaping, raw UTF-8
        .blob(Bytes.of(new byte[] {1, (byte) 0xFF})) // base64 "Af8="
        .kind(bijnagte.recordbuf.fixture.canonicalformat.rec.Kind.ALPHA) // enum by NAME, not number
        .item(
            bijnagte.recordbuf.fixture.canonicalformat.rec.Item.builder()
                .id(5)
                .label("nested")
                .build())
        .tags(List.of("a", "b")) // array element ORDER preserved, never sorted
        .attrs(attrs)
        .build();
  }

  private static Sample recordToReference(bijnagte.recordbuf.fixture.canonicalformat.rec.Sample rec)
      throws InvalidProtocolBufferException, IOException {
    return Sample.parseFrom(rec.toByteArray());
  }

  @Test
  void populatedRecordMatchesCommittedCanonicalGolden() throws Exception {
    JsonGolden.assertCanonicalMatches(
        "canonicalformat/probe.json", canonicalPrinter().print(recordToReference(probeRecord())));
  }

  @Test
  void defaultRecordRendersEmptyObject() throws Exception {
    JsonGolden.assertCanonicalMatches(
        "canonicalformat/probe-empty.json", canonicalPrinter().print(Sample.getDefaultInstance()));
  }

  /** Canonical text is also VALID proto3 JSON: printing then re-parsing reproduces the message. */
  @Test
  void canonicalPrintRoundTripsThroughTheParser() throws Exception {
    Sample reference = recordToReference(probeRecord());
    String canonical = canonicalPrinter().print(reference);
    Sample.Builder reparsed = Sample.newBuilder();
    JsonFormat.parser().merge(canonical, reparsed);
    assertEquals(reference, reparsed.build());
  }
}
