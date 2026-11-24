package bijnagte.recordbuf.fixture.oneofnames;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.oneofnames.rec.OneofNames;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code oneofnames}, testing snake_case oneof member names. The {@link
 * OneofNames} message has one oneof, {@code oneof_field}, with one member of each kind that
 * TestAllTypesProto3's oneof carries. A case sets one member, and the {@code unset} case leaves the
 * oneof empty. Each member record is UpperCamelCase ({@code OneofUint32Value}, ...). Every case
 * round-trips through the protobuf binary and Jackson formats; the Interop and Avro formats do not
 * apply, because this probe has neither.
 */
public final class OneofNamesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "oneofnames";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY, "uint32", oneofField(new OneofNames.OneofField.OneofUint32Value(42)))),
        build(
            jacksonLossless(
                FAMILY,
                "nested-message",
                oneofField(
                    new OneofNames.OneofField.OneofNestedMessageValue(
                        OneofNames.NestedMessage.builder().a(7).build())))),
        build(
            jacksonLossless(
                FAMILY, "string", oneofField(new OneofNames.OneofField.OneofStringValue("hello")))),
        build(
            jacksonLossless(
                FAMILY,
                "bytes",
                oneofField(
                    new OneofNames.OneofField.OneofBytesValue(Bytes.of(new byte[] {1, 2, 3}))))),
        build(
            jacksonLossless(
                FAMILY, "bool", oneofField(new OneofNames.OneofField.OneofBoolValue(true)))),
        build(
            jacksonLossless(
                FAMILY,
                "uint64",
                oneofField(new OneofNames.OneofField.OneofUint64Value(5_000_000_000L)))),
        build(
            jacksonLossless(
                FAMILY, "float", oneofField(new OneofNames.OneofField.OneofFloatValue(1.5f)))),
        build(
            jacksonLossless(
                FAMILY, "double", oneofField(new OneofNames.OneofField.OneofDoubleValue(2.25)))),
        build(
            jacksonLossless(
                FAMILY,
                "enum",
                oneofField(new OneofNames.OneofField.OneofEnumValue(OneofNames.NestedEnum.BAR)))),
        build(jacksonLossless(FAMILY, "unset", OneofNames.builder().build())));
  }

  private static OneofNames oneofField(OneofNames.OneofField member) {
    return OneofNames.builder().oneofField(member).build();
  }
}
