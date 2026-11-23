package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.combined.rec.Compat.MEntry;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.fixture.oneof.rec.Color;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests of the record-to-reference mapping of {@link ReferenceTypes}. */
class ReferenceTypesTest {

  @Test
  void topLevelRecord_mapsToTheNaturalPackageMessage() {
    assertEquals(
        Optional.of(bijnagte.recordbuf.fixture.i32.Sample.class),
        ReferenceTypes.referenceFor(bijnagte.recordbuf.fixture.i32.rec.Sample.class));
    assertEquals(
        Optional.empty(),
        ReferenceTypes.missingReason(bijnagte.recordbuf.fixture.i32.rec.Sample.class));
  }

  @Test
  void nestedRecord_mapsToTheNestedMessage() {
    assertEquals(
        Optional.of(bijnagte.recordbuf.fixture.optional.Sample.Fixed.class),
        ReferenceTypes.referenceFor(bijnagte.recordbuf.fixture.optional.rec.Sample.Fixed.class));
  }

  @Test
  void recordOutsideARecPackage_hasNoReference() {
    Class<?> ifaceSample = bijnagte.recordbuf.fixture.iface.Sample.class;

    assertEquals(Optional.empty(), ReferenceTypes.referenceFor(ifaceSample));
    assertTrue(
        ReferenceTypes.missingReason(ifaceSample).orElseThrow().contains("not in a .rec package"));
  }

  @Test
  void recordWhoseNaturalClassDoesNotExist_hasNoReference() {
    assertEquals(Optional.empty(), ReferenceTypes.referenceFor(Full.class));
    assertTrue(
        ReferenceTypes.missingReason(Full.class)
            .orElseThrow()
            .contains("bijnagte.recordbuf.fixture.jackson.full.Full"));
  }

  @Test
  void mapEntryRecord_hasNoReference() {
    assertEquals(Optional.empty(), ReferenceTypes.referenceFor(MEntry.class));
  }

  @Test
  void enum_hasNoReferenceBecauseItIsNotAMessage() {
    Optional<String> reason = ReferenceTypes.missingReason(Color.class);

    assertTrue(reason.orElseThrow().contains("not a protobuf-java message"), reason.toString());
  }
}
