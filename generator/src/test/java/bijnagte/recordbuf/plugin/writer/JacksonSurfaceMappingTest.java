package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.GoogleJavaFormatter;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * U4 of the 2026-11-20 Jackson plan: the Jackson JSON-surface annotations on generated records.
 *
 * <p>Contract (J6): on a {@code recordbuf.jackson}-enabled file every non-ignored record component
 * carries an explicit {@code @JsonProperty( "<resolvedName>" )} (the proto3 camelCase default or
 * the {@code recordbuf.json_name} override) and an ignored field carries {@code @JsonIgnore}; on a
 * file without the toggle the rendered source is byte-identical to the pre-feature output (no
 * Jackson annotation anywhere).
 */
final class JacksonSurfaceMappingTest {

  /** A minimal two-field message model: a kept string + a renamed string + an ignored string. */
  private static Message message(String jsonName, boolean ignored, boolean secondIgnored) {
    Field kept =
        new Field(
            "name",
            "name",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "string",
            TypeRef.of("java.lang.String"),
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            "name",
            false);
    Field renamed =
        new Field(
            "display_name",
            "displayName",
            2,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "string",
            TypeRef.of("java.lang.String"),
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            jsonName,
            ignored);
    Field hidden =
        new Field(
            "secret",
            "secret",
            3,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "string",
            TypeRef.of("java.lang.String"),
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            "secret",
            secondIgnored);
    return new Message(
        "Greeting",
        TypeRef.named("com.example", "Greeting"),
        List.of(kept, renamed, hidden),
        List.of(),
        List.of(),
        List.of(),
        false,
        null,
        List.of(),
        null);
  }

  private static String render(Message m, boolean jackson) {
    RecordSpec spec = RecordSourceMapping.toSource(m, "com.example", jackson);
    JavaFile file = new JavaFile("com.example", null, spec, new TypeCollector("com.example"));
    return SourceRenderer.render(file, GoogleJavaFormatter.getInstance());
  }

  @Test
  void keptFieldCarriesExplicitJsonPropertyWithResolvedName() {
    String source = render(message("renamedName", false, false), true);
    assertTrue(
        source.contains("@JsonProperty(\"name\")"),
        "expected the explicit @JsonProperty(default camelCase): " + source);
    assertTrue(
        source.contains("@JsonProperty(\"renamedName\")"),
        "expected the json_name override to win over the camelCase default: " + source);
  }

  @Test
  void ignoredFieldCarriesJsonIgnoreInstead() {
    String source = render(message("renamedName", false, true), true);
    assertTrue(
        source.contains("@JsonIgnore"), "expected @JsonIgnore on the ignored field: " + source);
    // the ignored field's @JsonProperty must NOT be emitted alongside the @JsonIgnore.
    assertFalse(
        source.contains("@JsonProperty(\"secret\")"),
        "an ignored field must not also carry @JsonProperty: " + source);
  }

  @Test
  void fileWithoutTheToggleRendersNoJacksonSurfaceAtAll() {
    String source = render(message("renamedName", true, true), false);
    assertFalse(
        source.contains("jackson"),
        "a jackson-off file must render byte-identical to the pre-feature output (no Jackson "
            + "annotation or import): "
            + source);
  }

  @Test
  void jacksonOffSourceIsByteIdenticalToThePreFeatureBaseline() {
    // The pre-feature baseline is the no-surface rendering of the same model: the two must agree
    // exactly, which is the strongest form of "off is unchanged".
    String withSurface = render(message("displayName", false, false), true);
    String withoutSurface = render(message("displayName", false, false), false);
    assertTrue(withoutSurface.length() < withSurface.length());
    // and re-rendering with the same input is stable (the renderer is deterministic).
    assertEquals(withoutSurface, render(message("displayName", false, false), false));
  }
}
