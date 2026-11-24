package bijnagte.recordbuf.fixture.edgenames;

import bijnagte.recordbuf.fixture.edgenames.rec.EdgeNames;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code edgenames}, testing upper-case-start field names. The {@link
 * EdgeNames} message carries the eight vendored names that protoc's Java rule leaves starting
 * upper-case, so each record component lowers its first letter. A populated case sets each field to
 * a distinct non-zero value, and a default case sets them all to their defaults. Both must
 * round-trip identically through all five formats, and the proto3 JSON keys are protoc's ({@code
 * FieldName3}, {@code FIELDNAME11}, ...).
 */
public final class EdgeNamesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "edgenames";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", EdgeNames.builder().build())));
  }

  /** A non-default, non-zero value for every field. */
  public static EdgeNames populated() {
    return EdgeNames.builder()
        .fieldName3(31)
        .fieldName8(-38)
        .fieldName10(410)
        .fIELDNAME11(-411)
        .fIELDName12(412)
        .fieldName13(-413)
        .fieldName14(414)
        .fieldName18(-418)
        .build();
  }
}
