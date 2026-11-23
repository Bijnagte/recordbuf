package bijnagte.recordbuf.fixture.canonicalformat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Protobuf CANONICAL JSON for the generated .rec records. The record crosses to the protobuf-java
 * reference through its generated wire codec ({@code toByteArray} -> {@code parseFrom}) and is then
 * printed with protobuf's JSON printer; the output must equal the committed {@code
 * golden/canonicalformat/*.proto3.json} golden, member order included, and the golden must parse
 * back to the same message and the same record.
 *
 * <p>Which "canonical" is asserted: the proto3 JSON mapping rendered with every field in
 * FIELD-NUMBER order and map KEYS sorted. That is the shape protobuf's canonical-JSON tools produce
 * for a message -- stable across producer implementations given the same schema and values, and
 * reproducible from any other producer's output. Whitespace is insignificant: the comparison goes
 * through the golden matrix's normalized JSON tree, which keeps member order.
 *
 * <p>These two goldens are not matrix cases in the proto3 JSON format (see {@link
 * CanonicalFormatCases}), so this class makes the proto3 JSON checks the matrix would make; the
 * other formats of the same cases run in the matrix.
 */
class ProtoCanonicalJsonTest {

  private static final CanonicalFormatCases SOURCE = new CanonicalFormatCases();

  private static final String PROTO3_JSON = "proto3.json";

  private static final JsonFormat.Printer CANONICAL_PRINTER = JsonFormat.printer().sortingMapKeys();

  /** Every format that applies to the cases, forward and reverse, against the committed goldens. */
  @TestFactory
  Stream<DynamicNode> canonicalFormatCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  private static Sample recordToReference(bijnagte.recordbuf.fixture.canonicalformat.rec.Sample rec)
      throws InvalidProtocolBufferException, IOException {
    return Sample.parseFrom(rec.toByteArray());
  }

  private static String golden(String caseName) {
    return GoldenStore.classpath().loadText(CanonicalFormatCases.FAMILY, caseName, PROTO3_JSON);
  }

  @Test
  void populatedRecordMatchesCommittedCanonicalGolden() throws Exception {
    GoldenAssertions.assertJsonMatches(
        golden("probe"),
        CANONICAL_PRINTER.print(recordToReference(CanonicalFormatCases.probe())),
        "canonicalformat/probe." + PROTO3_JSON);
  }

  @Test
  void defaultRecordRendersEmptyObject() throws Exception {
    GoldenAssertions.assertJsonMatches(
        golden("probe-empty"),
        CANONICAL_PRINTER.print(Sample.getDefaultInstance()),
        "canonicalformat/probe-empty." + PROTO3_JSON);
  }

  /** The golden parses to the message the record crosses to, and that message to the record. */
  @Test
  void committedGoldensParseBackToTheMessageAndTheRecord() throws Exception {
    assertGoldenParsesBackTo("probe", CanonicalFormatCases.probe());
    assertGoldenParsesBackTo(
        "probe-empty", bijnagte.recordbuf.fixture.canonicalformat.rec.Sample.builder().build());
  }

  private static void assertGoldenParsesBackTo(
      String caseName, bijnagte.recordbuf.fixture.canonicalformat.rec.Sample record)
      throws Exception {
    Sample.Builder parsed = Sample.newBuilder();
    JsonFormat.parser().merge(golden(caseName), parsed);

    assertEquals(recordToReference(record), parsed.build(), caseName);
    assertEquals(
        record,
        bijnagte.recordbuf.fixture.canonicalformat.rec.Sample.fromBytes(
            parsed.build().toByteArray()),
        caseName);
  }

  /** Canonical text is also VALID proto3 JSON: printing then re-parsing reproduces the message. */
  @Test
  void canonicalPrintRoundTripsThroughTheParser() throws Exception {
    Sample reference = recordToReference(CanonicalFormatCases.probe());
    String canonical = CANONICAL_PRINTER.omittingInsignificantWhitespace().print(reference);
    Sample.Builder reparsed = Sample.newBuilder();
    JsonFormat.parser().merge(canonical, reparsed);
    assertEquals(reference, reparsed.build());
  }
}
