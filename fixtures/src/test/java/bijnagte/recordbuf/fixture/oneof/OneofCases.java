package bijnagte.recordbuf.fixture.oneof;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.oneof.rec.Color;
import bijnagte.recordbuf.fixture.oneof.rec.Compat;
import bijnagte.recordbuf.fixture.oneof.rec.Sub;
import java.util.List;

/**
 * The golden cases of family {@code oneof}, migrated from {@code OneofRoundTripTest}: {@link
 * Compat}, a oneof {@code choice} whose members are a string, an int32, an enum and a message, and
 * the record with the oneof unset. One case per value that class asserted on: {@code name-hello}
 * and {@code name-x} (the string member, ordinary and one character), {@code count-42} and {@code
 * count-999} (the int member), {@code color-green}, {@code item-seven} and {@code unset}.
 *
 * <p>{@code Compat} carries {@code (recordbuf.protobuf_java)}, so every case also has a proto3 JSON
 * golden produced through the generated {@code Interop} converter. The Jackson goldens show the
 * oneof flat, the set member under its JSON name plus the {@code choice} discriminator. The family
 * {@code jackson-oneof} holds further renderings of this same record.
 */
public final class OneofCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "oneof";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "name-hello", choice(new Compat.Choice.NameValue("hello")))),
        build(jacksonLossless(FAMILY, "name-x", choice(new Compat.Choice.NameValue("x")))),
        build(jacksonLossless(FAMILY, "count-42", choice(new Compat.Choice.CountValue(42)))),
        build(jacksonLossless(FAMILY, "count-999", choice(new Compat.Choice.CountValue(999)))),
        build(
            jacksonLossless(
                FAMILY, "color-green", choice(new Compat.Choice.ColorValue(Color.GREEN)))),
        build(
            jacksonLossless(
                FAMILY,
                "item-seven",
                choice(new Compat.Choice.ItemValue(Sub.builder().text("seven").build())))),
        build(jacksonLossless(FAMILY, "unset", Compat.builder().build())));
  }

  private static Compat choice(Compat.Choice choice) {
    return Compat.builder().choice(choice).build();
  }
}
