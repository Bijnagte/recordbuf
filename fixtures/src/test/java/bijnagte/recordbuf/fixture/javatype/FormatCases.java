package bijnagte.recordbuf.fixture.javatype;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.javatype.rec.FormatFields;
import bijnagte.recordbuf.fixture.javatype.rec.RepeatedFormatFields;
import com.github.f4b6a3.ulid.Ulid;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The golden cases of family {@code javatype}: a {@code java_type} format field holds its option's
 * Java type in the record while every surface (wire, proto3 JSON, Jackson, Avro) carries the
 * format's string form. The round trip is lossless in every applicable format, so a case simply
 * names its Jackson golden.
 */
public final class FormatCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "javatype";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "absent", FormatFields.builder().build())),
        build(jacksonLossless(FAMILY, "repeated_populated", repeatedPopulated())));
  }

  /** Every format field set, plus the plain-string control and the oneof's format member. */
  private static FormatFields populated() {
    return FormatFields.builder()
        .uid(UUID.fromString("9b2f3c1e-4d5a-7f60-8e9b-1c2d3e4f5a6b"))
        .ref(URI.create("https://example.com/pets/42"))
        .token(Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        // dur (java.time.Duration) has NO registered format: it stays absent in every case; the
        // registry-miss failure is exercised directly by FormatFieldTest.
        .label("plain string control")
        .contact(new FormatFields.Contact.HomepageValue(URI.create("https://example.com/home")))
        .build();
  }

  /** Every repeated format field set to more than one element. */
  private static RepeatedFormatFields repeatedPopulated() {
    return RepeatedFormatFields.builder()
        .links(
            List.of(
                URI.create("https://example.com/pets/42"),
                URI.create("https://example.com/pets/43")))
        .tokens(
            List.of(
                Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FAV"), Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FBW")))
        .build();
  }
}
