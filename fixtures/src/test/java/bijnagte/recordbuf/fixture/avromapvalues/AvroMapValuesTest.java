package bijnagte.recordbuf.fixture.avromapvalues;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Avro round-trip tests for bytes, message and enum map values ({@code avromapvalues}). */
class AvroMapValuesTest {

  private static final AvroMapValuesCases SOURCE = new AvroMapValuesCases();

  @TestFactory
  Stream<DynamicNode> avroMapValues_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
