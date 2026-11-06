package bijnagte.recordbuf.fixture.iface;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that a generated type implementing a declared user interface compiles and
 * actually behaves as that interface. These classes are produced from {@code iface_marker.proto} by
 * the recordbuf plugin (via the {@code generateTestProto} task; the test source set has the
 * protobuf-java builtin removed, so no reference is emitted) and compiled against the hand-written
 * {@link Marker} / {@link Named} interfaces on the test classpath.
 */
class InterfaceImplementationTest {

  @Test
  void sampleImplementsMarker() {
    // Cast to Object so the instanceof is a genuine runtime check; static typing
    // would make EP report BadInstanceof (trivially true for a subtype).
    Object sample = Sample.builder().id(7).build();
    assertTrue(sample instanceof Marker);
    Marker marker = (Marker) sample;
    assertTrue(marker instanceof Sample);
  }

  @Test
  void colorImplementsNamed() {
    Object color = Color.RED;
    assertTrue(color instanceof Named);
    Named named = Color.GREEN;
    assertTrue(named instanceof Color);
  }
}
