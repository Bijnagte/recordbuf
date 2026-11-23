package bijnagte.recordbuf.fixture.combined;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code combined} golden family (see {@link CompatCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not. The protobuf binary format carries the byte comparison against protobuf-java across every
 * field family at once, and the decode of protobuf-java's bytes, that this class used to assert by
 * hand; the proto3 JSON format carries the generated {@code Interop} converter in both directions
 * (the checks {@code CompatInteropTest} used to make by value). The unknown-field cases are part of
 * the same matrix; {@link UnknownFieldRoundTripTest} keeps the carrier-inspection checks.
 */
class CompatTest {

  private static final CompatCases SOURCE = new CompatCases();

  @TestFactory
  Stream<DynamicNode> combinedCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
