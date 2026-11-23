package bijnagte.recordbuf.fixture.string;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.string.rec.Sample;
import java.util.List;

/**
 * The golden cases of family {@code string}, migrated from {@code StringTest}: the three-string
 * {@link Sample}. {@code mixed-shapes} is the one value that test asserted on: an empty string (the
 * proto3 default, not written), a plain ASCII string and a multibyte string (accented, CJK and
 * emoji) that forces real UTF-8 handling. The other cases are the default record and characters a
 * JSON writer must escape.
 */
public final class StringCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "string";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY,
                "mixed-shapes",
                Sample.builder()
                    .a("")
                    .b("hello")
                    .c("h\u00e9llo-\u4e16\u754c-\uD83C\uDF89")
                    .build())),
        build(jacksonLossless(FAMILY, "all-empty", Sample.builder().build())),
        build(
            jacksonLossless(
                FAMILY,
                "json-escapes",
                Sample.builder()
                    .a("\"quoted\" back\\slash")
                    .b("tab\tnewline\nnul\u0000")
                    .c("</script>")
                    .build())));
  }
}
