package bijnagte.recordbuf.fixture.iface;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that a generated type implementing a declared user interface compiles and
 * actually behaves as that interface. These classes are produced from {@code iface_marker.proto}
 * by the recordbuf plugin (via the {@code genInterface} Gradle task) and compiled against the
 * hand-written {@link Marker} / {@link Named} interfaces on the test classpath.
 */
class InterfaceImplementationTest {

    @Test
    void sampleImplementsMarker() {
        Sample sample = new Sample(7);
        assertTrue(sample instanceof Marker);
        Marker marker = sample;
        assertTrue(marker instanceof Sample);
    }

    @Test
    void colorImplementsNamed() {
        assertTrue(Color.RED instanceof Named);
        Named named = Color.GREEN;
        assertTrue(named instanceof Color);
    }
}
