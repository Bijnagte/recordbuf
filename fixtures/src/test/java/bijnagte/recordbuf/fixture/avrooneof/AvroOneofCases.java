package bijnagte.recordbuf.fixture.avrooneof;

import bijnagte.recordbuf.fixture.avrooneof.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code avrooneof}, testing a snake_case oneof through the Avro
 * converter. The {@link Sample} message has one oneof, {@code oneof_field}, with a scalar, an enum,
 * a {@code bytes} and a message member, and no other field: Avro cannot express an absent singular
 * message, so every case is complete. A case sets one member, and the {@code unset} case leaves the
 * oneof empty. Proto3 JSON is not applicable: the file has no Interop converter.
 */
public final class AvroOneofCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avrooneof";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY, "uint32", oneofField(new Sample.OneofField.OneofUint32Value(42)))),
        build(
            jacksonLossless(
                FAMILY, "nested-message", oneofField(Sample.NestedMessage.builder().a(7).build()))),
        build(
            jacksonLossless(
                FAMILY,
                "bytes",
                oneofField(new Sample.OneofField.OneofBytesValue(Bytes.of(new byte[] {1, 2, 3}))))),
        build(jacksonLossless(FAMILY, "enum", oneofField(Sample.NestedEnum.BAR))),
        build(jacksonLossless(FAMILY, "unset", Sample.builder().build())));
  }

  private static Sample oneofField(Sample.OneofField member) {
    return Sample.builder().oneofField(member).build();
  }
}
