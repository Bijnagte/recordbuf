package bijnagte.recordbuf.fixture.interopdynamic;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.interopdynamic.rec.InteropDynamic;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.json.DynamicWkt;
import com.google.protobuf.Any;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import java.util.List;

/**
 * The golden cases of family {@code interopdynamic}, testing dynamic well-known type fields through
 * the Interop converter. The {@link InteropDynamic} message carries {@code Struct}, {@code Any} and
 * {@code Value} singular, and {@code Struct}, {@code Any}, {@code Value} and {@code ListValue}
 * repeated. The record holds each as its Jackson node, built from protobuf-java messages through
 * {@link DynamicWkt}. Every case also has a proto3 JSON golden produced through the generated
 * {@code Interop} converter. The proto3 JSON adapter resolves the {@code Payload} an {@code Any}
 * packs.
 */
public final class InteropDynamicCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopdynamic";

  /**
   * Registers {@link Payload}, the message the {@code Any} fields pack, for the proto3 JSON
   * adapter.
   */
  public InteropDynamicCases() {
    super(
        new Proto3JsonFormat(
            JsonFormat.TypeRegistry.newBuilder().add(Payload.getDescriptor()).build()));
  }

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", InteropDynamic.builder().build())));
  }

  /**
   * Every dynamic WKT set: a {@code Struct} with nested object, list, number, bool, string and null
   * members, an {@code Any} packing a {@link Payload}, and a {@code Value} of each kind. Each
   * repeated field has two elements (the {@code Value} list has one of each kind).
   */
  public static InteropDynamic populated() {
    Struct struct =
        Struct.newBuilder()
            .putFields("name", string("recordbuf"))
            .putFields("nested", structValue(Struct.newBuilder().putFields("flag", bool(true))))
            .putFields(
                "items",
                listValue(ListValue.newBuilder().addValues(number(-0.25)).addValues(string("a"))))
            .putFields("count", number(2.5))
            .putFields("missing", nullValue())
            .build();
    return InteropDynamic.builder()
        .optionalStruct(DynamicWkt.struct(struct))
        .optionalAny(DynamicWkt.any(Any.pack(payload("hello", 7))))
        .optionalValue(DynamicWkt.value(number(-0.5)))
        .repeatedStruct(
            List.of(
                DynamicWkt.struct(Struct.newBuilder().putFields("k", string("v")).build()),
                DynamicWkt.struct(Struct.newBuilder().putFields("n", number(1.5)).build())))
        .repeatedAny(
            List.of(
                DynamicWkt.any(Any.pack(payload("a", 1))),
                DynamicWkt.any(Any.pack(payload("b", -2)))))
        .repeatedValue(
            List.of(
                DynamicWkt.value(nullValue()),
                DynamicWkt.value(number(2.5)),
                DynamicWkt.value(string("s")),
                DynamicWkt.value(bool(false)),
                DynamicWkt.value(structValue(Struct.newBuilder().putFields("k", bool(true)))),
                DynamicWkt.value(listValue(ListValue.newBuilder().addValues(number(1.25))))))
        .repeatedListValue(
            List.of(
                DynamicWkt.list(
                    ListValue.newBuilder()
                        .addValues(string("x"))
                        .addValues(number(3.5))
                        .addValues(nullValue())
                        .build()),
                DynamicWkt.list(
                    ListValue.newBuilder()
                        .addValues(bool(true))
                        .addValues(structValue(Struct.newBuilder().putFields("a", nullValue())))
                        .build())))
        .build();
  }

  private static Payload payload(String note, int n) {
    return Payload.newBuilder().setNote(note).setN(n).build();
  }

  private static Value string(String value) {
    return Value.newBuilder().setStringValue(value).build();
  }

  private static Value number(double value) {
    return Value.newBuilder().setNumberValue(value).build();
  }

  private static Value bool(boolean value) {
    return Value.newBuilder().setBoolValue(value).build();
  }

  private static Value nullValue() {
    return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
  }

  private static Value structValue(Struct.Builder struct) {
    return Value.newBuilder().setStructValue(struct).build();
  }

  private static Value listValue(ListValue.Builder list) {
    return Value.newBuilder().setListValue(list).build();
  }
}
