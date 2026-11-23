package bijnagte.recordbuf.fixture.message;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.message.rec.Item;
import bijnagte.recordbuf.fixture.message.rec.Sample;
import java.util.List;

/**
 * The golden cases of family {@code message}, migrated from {@code MessageTest} and {@code
 * MessageBuilderTest}: the {@link Sample} with a singular nested {@link Item} and a repeated one.
 * {@code populated} and {@code all-default} are the two values {@code MessageTest} asserted on, and
 * {@code single-and-one-item} is the third value the builder test constructed. The rest are the
 * presence boundaries of a nested message: {@code single-only} and {@code items-only} each leave
 * one side absent, {@code single-default-item} holds an empty {@link Item}, present on the wire as
 * a zero-length message and so distinct from {@code all-default}'s {@code null}, and {@code
 * items-with-default-element} keeps an empty element in a repeated message, which is never dropped.
 *
 * <p>No case has an Avro golden: {@code Sample} is the second message of its proto file (gap {@code
 * avro-first-message-only}).
 */
public final class MessageCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "message";

  private static final String NO_AVRO_FOR_SECOND_MESSAGE =
      "Sample is the second top-level message of message.proto, and the Avro surface is generated"
          + " for the first message (Item) and what it references only, so Sample has no Avro"
          + " converter (gap avro-first-message-only)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            withoutAvro(
                jacksonLossless(
                    FAMILY,
                    "populated",
                    Sample.builder()
                        .single(item(7, "seven"))
                        .items(List.of(item(1, "one"), item(2, "two")))
                        .build()))),
        build(withoutAvro(jacksonLossless(FAMILY, "all-default", Sample.builder().build()))),
        build(
            withoutAvro(
                jacksonLossless(
                    FAMILY,
                    "single-and-one-item",
                    Sample.builder()
                        .single(item(7, "seven"))
                        .items(List.of(item(1, "one")))
                        .build()))),
        build(
            withoutAvro(
                jacksonLossless(
                    FAMILY, "single-only", Sample.builder().single(item(7, "seven")).build()))),
        build(
            withoutAvro(
                jacksonLossless(
                    FAMILY,
                    "items-only",
                    Sample.builder().items(List.of(item(1, "one"))).build()))),
        build(
            withoutAvro(
                jacksonLossless(
                    FAMILY,
                    "single-default-item",
                    Sample.builder().single(Item.builder().build()).build()))),
        build(
            withoutAvro(
                jacksonLossless(
                    FAMILY,
                    "items-with-default-element",
                    Sample.builder()
                        .items(List.of(Item.builder().build(), item(2, "two")))
                        .build()))));
  }

  private GoldenCase.Builder withoutAvro(GoldenCase.Builder builder) {
    return builder
        .notApplicable(avroJson, NO_AVRO_FOR_SECOND_MESSAGE)
        .notApplicable(avroBinary, NO_AVRO_FOR_SECOND_MESSAGE);
  }

  private static Item item(int id, String name) {
    return Item.builder().id(id).name(name).build();
  }
}
