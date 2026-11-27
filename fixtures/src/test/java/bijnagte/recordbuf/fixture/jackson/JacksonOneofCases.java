package bijnagte.recordbuf.fixture.jackson;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.oneof.rec.Event;
import bijnagte.recordbuf.fixture.oneof.rec.Color;
import bijnagte.recordbuf.fixture.oneof.rec.Compat;
import bijnagte.recordbuf.fixture.oneof.rec.Sub;
import bijnagte.recordbuf.runtime.Bytes;
import java.time.Instant;
import java.util.List;

/**
 * The golden cases of family {@code jackson-oneof}, migrated from {@code
 * JacksonOneofRoundTripTest}: a oneof renders flat, the set member as a top-level property under
 * its JSON name plus a discriminator naming it, and an unset oneof renders neither. {@code Compat}
 * is the default discriminator ({@code choice}); {@code Event} overrides it ({@code kind}) and
 * pairs a Timestamp and a bytes member.
 */
public final class JacksonOneofCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "jackson-oneof";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "compat-name", compatName())),
        build(jacksonLossless(FAMILY, "compat-count", compatCount())),
        build(jacksonLossless(FAMILY, "compat-item", compatItem())),
        build(jacksonLossless(FAMILY, "compat-color", compatColor())),
        build(jacksonLossless(FAMILY, "compat-unset", compatUnset())),
        build(jacksonLossless(FAMILY, "event-at", eventAt())),
        build(jacksonLossless(FAMILY, "event-blob", eventBlob())));
  }

  /** A {@code Compat} with its string member set. */
  public static Compat compatName() {
    return compat(new Compat.Choice.NameValue("blue"));
  }

  /** A {@code Compat} with its int member set. */
  public static Compat compatCount() {
    return compat(new Compat.Choice.CountValue(3));
  }

  /** A {@code Compat} with its message member set. */
  public static Compat compatItem() {
    return compat(Sub.builder().text("t").build());
  }

  /** A {@code Compat} with its enum member set. */
  public static Compat compatColor() {
    return compat(Color.RED);
  }

  /** A {@code Compat} with its oneof unset. */
  public static Compat compatUnset() {
    return Compat.builder().build();
  }

  /** An {@code Event} whose oneof holds a Timestamp. */
  public static Event eventAt() {
    return Event.builder()
        .id(7)
        .when(new Event.When.AtValue(Instant.ofEpochSecond(1_763_614_896L, 789_000_000)))
        .build();
  }

  /** An {@code Event} whose oneof holds bytes. */
  public static Event eventBlob() {
    return Event.builder()
        .id(7)
        .when(new Event.When.BlobValue(Bytes.of(new byte[] {1, 2, 3})))
        .build();
  }

  private static Compat compat(Compat.Choice choice) {
    return Compat.builder().choice(choice).build();
  }
}
