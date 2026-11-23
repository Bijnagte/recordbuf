package bijnagte.recordbuf.fixture.canonicalformat;

import bijnagte.recordbuf.fixture.canonicalformat.rec.Item;
import bijnagte.recordbuf.fixture.canonicalformat.rec.Kind;
import bijnagte.recordbuf.fixture.canonicalformat.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The golden cases of family {@code canonicalformat}, migrated from {@code ProtoCanonicalJsonTest}:
 * a {@code Sample} with one field per canonical-rendering aspect, and the default {@code Sample},
 * which renders as an empty object. Every format that applies to {@code Sample} is a golden here.
 *
 * <p>The proto3 JSON format is not applicable: it crosses to protobuf-java through the generated
 * {@code Interop} converter, and {@code Sample} does not carry {@code (recordbuf.protobuf_java)}.
 * It was kept off because the converter used to fail on its singular enum field {@code kind}; the
 * converter now handles singular enums, and putting {@code Sample} on Interop is follow-up work
 * (plan 2026-11-23-002). So the protobuf canonical JSON goldens, {@code probe.proto3.json} and
 * {@code probe-empty.proto3.json}, are checked structurally by {@code ProtoCanonicalJsonTest},
 * through the record's wire codec instead.
 */
public final class CanonicalFormatCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "canonicalformat";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "probe", probe())),
        build(jacksonLossless(FAMILY, "probe-empty", Sample.builder().build())));
  }

  /** One field per canonical-rendering aspect; see {@code canonical_format.proto}. */
  public static Sample probe() {
    // Insertion order deliberately unsorted (z, a, m): canonical form must sort the keys.
    LinkedHashMap<String, Integer> attrs = new LinkedHashMap<>();
    attrs.put("z", 1);
    attrs.put("a", 2);
    attrs.put("m", 3);

    return Sample.builder()
        .i32(-42)
        .i64(9007199254740993L) // 2^53 + 1: beyond double precision, must survive as a string
        .u64(9223372036854775807L) // max uint64
        .s32(-1)
        .f64(7L)
        .flt(0.5F)
        .dbl(0.1D) // shortest round-trip decimal must be exactly "0.1"
        .flag(true)
        .text("say \"hi\" \\ é A") // quotes + backslash escaping, raw UTF-8
        .blob(Bytes.of(new byte[] {1, (byte) 0xFF})) // base64 "Af8="
        .kind(Kind.ALPHA) // enum by NAME, not number
        .item(Item.builder().id(5).label("nested").build())
        .tags(List.of("a", "b")) // array element ORDER preserved, never sorted
        .attrs(attrs)
        .build();
  }
}
