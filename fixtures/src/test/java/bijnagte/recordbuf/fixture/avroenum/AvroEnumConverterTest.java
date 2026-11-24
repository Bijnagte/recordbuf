package bijnagte.recordbuf.fixture.avroenum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.avroenum.rec.Color;
import bijnagte.recordbuf.fixture.avroenum.rec.Sample;
import bijnagte.recordbuf.fixture.avroenum.rec.SampleAvroConverter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Avro enum converter of {@link Sample}: an enum travels as its constant's name, and a name no
 * constant has converts to the first constant (the record's {@code forNumber} fallback) instead of
 * failing.
 */
class AvroEnumConverterTest {

  @Test
  void undeclaredAvroEnumNamesConvertToTheFirstConstant() {
    SampleAvro s = new SampleAvro();
    s.setKind("NOT_DECLARED");
    s.setColor("NOT_DECLARED");
    s.setKinds(List.of("ALPHA", "NOT_DECLARED"));
    s.setColors(List.of("NOT_DECLARED"));
    s.setUnknownFields(List.of());

    Sample r = SampleAvroConverter.toRecord(s);

    assertEquals(Sample.Kind.KIND_ZERO, r.kind());
    assertEquals(Color.COLOR_UNSPECIFIED, r.color());
    assertEquals(List.of(Sample.Kind.ALPHA, Sample.Kind.KIND_ZERO), r.kinds());
    assertEquals(List.of(Color.COLOR_UNSPECIFIED), r.colors());
  }

  @Test
  void anUnsetEnumWritesTheFirstConstantName() {
    Sample r = Sample.builder().build();

    SampleAvro s = SampleAvroConverter.fromRecord(r);

    assertEquals("KIND_ZERO", s.getKind().toString());
    assertEquals("COLOR_UNSPECIFIED", s.getColor().toString());
  }
}
