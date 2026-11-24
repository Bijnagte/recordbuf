package bijnagte.recordbuf.fixture.jackson;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenCaseSource;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.fixture.golden.format.AvroBinaryFormat;
import bijnagte.recordbuf.fixture.golden.format.AvroJsonFormat;
import bijnagte.recordbuf.fixture.golden.format.JacksonFormat;
import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import java.util.List;

/**
 * The base of the golden case sources that migrated the Jackson golden prototypes: it fixes the
 * five formats and declares each case in all of them. A case names its Jackson outcome; every other
 * format is lossless where the format applies to the record and not applicable, with the format's
 * own reason, where it does not (see {@link GoldenCase.Builder#otherwiseLosslessWhereApplicable}).
 * Most of these records are generated into the test source set without the protobuf-java builtin,
 * so they have no protobuf binary or proto3 JSON; the ones with an Avro converter get both Avro
 * formats.
 */
public abstract class JacksonGoldenCases implements GoldenCaseSource {

  /** The protobuf binary format. */
  protected final ProtobufBinaryFormat protobufBinary = new ProtobufBinaryFormat();

  /** The proto3 JSON format. */
  protected final Proto3JsonFormat proto3Json;

  /** The Jackson JSON format. */
  protected final JacksonFormat jackson = new JacksonFormat();

  /** The canonical Avro JSON format. */
  protected final AvroJsonFormat avroJson = new AvroJsonFormat();

  /** The Avro binary format. */
  protected final AvroBinaryFormat avroBinary = new AvroBinaryFormat();

  /** A source whose proto3 JSON format resolves no {@code Any} type. */
  protected JacksonGoldenCases() {
    this(new Proto3JsonFormat());
  }

  /**
   * A source whose proto3 JSON format resolves the {@code Any} types of its registry, for a family
   * that packs its own messages into an {@code Any}.
   */
  protected JacksonGoldenCases(Proto3JsonFormat proto3Json) {
    this.proto3Json = proto3Json;
  }

  @Override
  public final List<GoldenFormat> formats() {
    return List.of(protobufBinary, proto3Json, jackson, avroJson, avroBinary);
  }

  /**
   * Starts a case whose Jackson golden round-trips the record unchanged. Declare the formats that
   * differ, then finish with {@link #build}.
   */
  protected final GoldenCase.Builder jacksonLossless(String family, String name, Object record) {
    return GoldenCase.builder(family, name, record).lossless(jackson);
  }

  /**
   * Starts a case whose Jackson golden deserializes to {@code expected}, not the built record.
   * Declare the formats that differ, then finish with {@link #build}.
   */
  protected final GoldenCase.Builder jacksonExpectedAfterTrip(
      String family, String name, Object record, Object expected) {
    return GoldenCase.builder(family, name, record).expectedAfterTrip(jackson, expected);
  }

  /**
   * Finishes a case: each format not declared yet is lossless where it applies to the record and
   * not applicable, with the format's own reason, where it does not.
   */
  protected final GoldenCase build(GoldenCase.Builder builder) {
    return builder.otherwiseLosslessWhereApplicable(formats()).build(formats());
  }
}
