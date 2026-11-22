package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.fixture.jackson.off.rec.Inner;
import bijnagte.recordbuf.fixture.jackson.off.rec.Off;
import bijnagte.recordbuf.runtime.Bytes;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AE1/AE5 (2026-11-21-004 U10): a {@code recordbuf.jackson = false} file generates a record with no
 * Jackson surface at all -- no Jackson annotation, no retained-properties component -- that still
 * round-trips through its codec. The jackson-on twin is the control: the same probe finds its
 * surface.
 */
class JacksonInvarianceTest {

  /**
   * Every runtime-visible Jackson annotation on the record, its components, and their accessors.
   */
  private static List<String> jacksonAnnotations(Class<?> type) {
    List<AnnotatedElement> elements = new ArrayList<>();
    elements.add(type);
    for (RecordComponent c : type.getRecordComponents()) {
      elements.add(c);
      elements.add(c.getAccessor());
    }
    List<String> found = new ArrayList<>();
    for (AnnotatedElement e : elements) {
      for (Annotation a : e.getAnnotations()) {
        String name = a.annotationType().getName();
        if (name.startsWith("com.fasterxml.jackson.") || name.startsWith("tools.jackson.")) {
          found.add(e + ": " + name);
        }
      }
    }
    return found;
  }

  private static List<String> componentNames(Class<?> type) {
    List<String> names = new ArrayList<>();
    for (RecordComponent c : type.getRecordComponents()) {
      names.add(c.getName());
    }
    return names;
  }

  @Test
  void jacksonOffRecordCarriesNoJacksonSurface() {
    assertEquals(List.of(), jacksonAnnotations(Off.class));
    assertFalse(componentNames(Off.class).contains("jsonExtras"));
  }

  @Test
  void theProbeFindsTheSurfaceOnTheJacksonOnTwin() {
    assertFalse(jacksonAnnotations(Full.class).isEmpty());
    assertTrue(componentNames(Full.class).contains("jsonExtras"));
  }

  @Test
  void jacksonOffRecordRoundTripsThroughItsCodec() throws Exception {
    Off off =
        Off.builder()
            .name("n")
            .count(-3L)
            .flag(true)
            .ratio(0.5)
            .blob(Bytes.of(new byte[] {1}))
            .nickname("nick")
            .inner(Inner.builder().label("in").build())
            .at(Instant.ofEpochSecond(10, 20))
            .span(Duration.ofSeconds(-1, 500_000_000))
            .chunks(List.of(Bytes.of(new byte[] {2}), Bytes.empty()))
            .build();

    assertEquals(off, Off.fromBytes(off.toByteArray()));
  }
}
