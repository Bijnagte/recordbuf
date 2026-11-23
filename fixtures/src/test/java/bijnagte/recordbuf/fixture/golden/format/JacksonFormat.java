package bijnagte.recordbuf.fixture.golden.format;

import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON a record produces through recordbuf's generated Jackson surface, as {@code
 * .jackson.json} goldens.
 *
 * <p>The mapper is the one the jackson fixture tests use: a {@link JsonMapper} with no module
 * registered, because the per-field annotations the generator emits carry the whole contract. The
 * one addition is {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}: a record's map is a
 * {@code HashMap} (or a randomly ordered {@code Map.of}), so without it the member order of a map
 * field would change from run to run and break the order-sensitive golden comparison. It is the
 * same reasoning as {@code sortingMapKeys()} in {@link Proto3JsonFormat}. It is off by default (a
 * mutation test confirmed unsorted output), and it also sorts the retained {@code jsonExtras}
 * properties. The output is pretty-printed with Jackson's default printer.
 *
 * <p>The format applies only to records generated with the Jackson surface on. A {@code
 * recordbuf.jackson = false} record carries no Jackson annotation at all, which is what {@link
 * #notApplicableReason} looks for (the probe {@code JacksonInvarianceTest} uses).
 *
 * <p>Information the surface drops by design, such as the wire-level {@code unknownFields} carrier
 * (a {@code @JsonIgnore} component), does not survive a golden: cases declare the record the golden
 * deserializes to.
 */
public final class JacksonFormat implements GoldenFormat.Json {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  @Override
  public String extension() {
    return "jackson.json";
  }

  @Override
  public Optional<String> notApplicableReason(Object record) {
    Class<?> recordType = record.getClass();
    if (hasJacksonSurface(recordType)) {
      return Optional.empty();
    }
    return Optional.of(
        recordType.getName()
            + " carries no Jackson annotation (generated with recordbuf.jackson = false), so it has"
            + " no Jackson JSON surface");
  }

  @Override
  public String serialize(Object record) {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record);
  }

  @Override
  public Object deserialize(String goldenText, Class<?> recordType) {
    return MAPPER.readValue(goldenText, recordType);
  }

  /**
   * Whether the record, a component, or an accessor carries a runtime-visible Jackson annotation.
   */
  private static boolean hasJacksonSurface(Class<?> recordType) {
    List<AnnotatedElement> elements = new ArrayList<>();
    elements.add(recordType);
    for (RecordComponent component : recordType.getRecordComponents()) {
      elements.add(component);
      elements.add(component.getAccessor());
    }
    for (AnnotatedElement element : elements) {
      for (Annotation annotation : element.getAnnotations()) {
        String name = annotation.annotationType().getName();
        if (name.startsWith("com.fasterxml.jackson.") || name.startsWith("tools.jackson.")) {
          return true;
        }
      }
    }
    return false;
  }
}
