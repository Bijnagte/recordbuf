package bijnagte.recordbuf.fixture.golden;

import bijnagte.recordbuf.fixture.annotations.AnnotationsCases;
import bijnagte.recordbuf.fixture.avroenum.AvroEnumCases;
import bijnagte.recordbuf.fixture.avrooneof.AvroOneofCases;
import bijnagte.recordbuf.fixture.avrorecursive.AvroRecursiveCases;
import bijnagte.recordbuf.fixture.avrosingular.AvroSingularCases;
import bijnagte.recordbuf.fixture.avrostructured.AvroStructuredCases;
import bijnagte.recordbuf.fixture.avrowrappers.AvroWrappersCases;
import bijnagte.recordbuf.fixture.bytes.BytesCases;
import bijnagte.recordbuf.fixture.canonicalformat.CanonicalFormatCases;
import bijnagte.recordbuf.fixture.combined.CompatCases;
import bijnagte.recordbuf.fixture.corpus.TestAllTypesCorpusCases;
import bijnagte.recordbuf.fixture.edgenames.EdgeNamesCases;
import bijnagte.recordbuf.fixture.enumalias.EnumAliasCases;
import bijnagte.recordbuf.fixture.fixed.FixedCases;
import bijnagte.recordbuf.fixture.i32.IntCases;
import bijnagte.recordbuf.fixture.interopbytes.InteropBytesCases;
import bijnagte.recordbuf.fixture.interopdynamic.InteropDynamicCases;
import bijnagte.recordbuf.fixture.interopenum.InteropEnumCases;
import bijnagte.recordbuf.fixture.interopnames.InteropNamesCases;
import bijnagte.recordbuf.fixture.interoponeof.InteropOneofCases;
import bijnagte.recordbuf.fixture.interopoptionalenum.InteropOptionalEnumCases;
import bijnagte.recordbuf.fixture.interopwkt.InteropWktCases;
import bijnagte.recordbuf.fixture.jackson.JacksonDynamicWktCases;
import bijnagte.recordbuf.fixture.jackson.JacksonKnobsCases;
import bijnagte.recordbuf.fixture.jackson.JacksonOneofCases;
import bijnagte.recordbuf.fixture.jackson.JacksonRoundTripCases;
import bijnagte.recordbuf.fixture.jacksonformat.JacksonFormatCases;
import bijnagte.recordbuf.fixture.map.MapCases;
import bijnagte.recordbuf.fixture.mapvalues.MapValuesCases;
import bijnagte.recordbuf.fixture.message.MessageCases;
import bijnagte.recordbuf.fixture.oneof.OneofCases;
import bijnagte.recordbuf.fixture.oneofnames.OneofNamesCases;
import bijnagte.recordbuf.fixture.oneoforder.OneofOrderCases;
import bijnagte.recordbuf.fixture.oneofwkt.OneofWktCases;
import bijnagte.recordbuf.fixture.optional.OptionalCases;
import bijnagte.recordbuf.fixture.order.OrderCases;
import bijnagte.recordbuf.fixture.packed.PackedCases;
import bijnagte.recordbuf.fixture.repeated.RepeatedCases;
import bijnagte.recordbuf.fixture.string.StringCases;
import bijnagte.recordbuf.fixture.wellknown.WktCases;
import java.util.List;

/**
 * The explicit list of {@link GoldenCaseSource}s the {@code seedGoldens} task walks. A new family
 * of golden cases is seedable once its source is added to {@link #sources()}.
 */
public final class GoldenCaseRegistry {

  private GoldenCaseRegistry() {}

  /** Returns every registered case source, in the order the seeder walks them. */
  public static List<GoldenCaseSource> sources() {
    return List.of(
        new AdapterGoldenCases(),
        new TestAllTypesCorpusCases(),
        new JacksonRoundTripCases(),
        new JacksonDynamicWktCases(),
        new JacksonOneofCases(),
        new JacksonFormatCases(),
        new JacksonKnobsCases(),
        new CanonicalFormatCases(),
        new IntCases(),
        new FixedCases(),
        new StringCases(),
        new BytesCases(),
        new PackedCases(),
        new RepeatedCases(),
        new OptionalCases(),
        new OrderCases(),
        new MessageCases(),
        new MapCases(),
        new WktCases(),
        new OneofCases(),
        new OneofOrderCases(),
        new OneofWktCases(),
        new CompatCases(),
        new InteropNamesCases(),
        new EdgeNamesCases(),
        new EnumAliasCases(),
        new OneofNamesCases(),
        new MapValuesCases(),
        new InteropEnumCases(),
        new InteropOptionalEnumCases(),
        new InteropBytesCases(),
        new InteropWktCases(),
        new InteropDynamicCases(),
        new InteropOneofCases(),
        new AnnotationsCases(),
        new AvroEnumCases(),
        new AvroOneofCases(),
        new AvroWrappersCases(),
        new AvroStructuredCases(),
        new AvroSingularCases(),
        new AvroRecursiveCases());
  }
}
