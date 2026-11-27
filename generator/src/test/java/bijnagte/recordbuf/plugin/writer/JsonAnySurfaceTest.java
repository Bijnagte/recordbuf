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
 * U6 of the 2026-11-20 Jackson plan: the retained unknown JSON properties as a record component.
 *
 * <p>On a {@code recordbuf.jackson} file the record carries the {@code jsonExtras} component
 * ({@code @JsonIgnore @JsonAnyGetter @JsonAnySetter @Nullable} — no accessor methods; Jackson
 * honors the annotations on the record component/accessor), a compact constructor normalizing it
 * and the carrier, a builder wired to it, and a decode that passes a {@code null} extras argument.
 * The extras are JSON-only: the encode never writes them to the wire. On a file without the toggle
 * the rendered source (record + codec) carries none of it.
 */
final class JsonAnySurfaceTest {

  /** A minimal three-field string message (mirrors the U4 surface-test model). */
  private static Message message() {
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
            null,
            false,
            null,
            null,
            false,
            null,
            "renamedName",
            false);
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
            null,
            false,
            null,
            null,
            false,
            null,
            "secret",
            true);
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

  private static String render(boolean jackson) {
    RecordSpec spec = RecordSourceMapping.toSourceWithCodec(message(), jackson);
    JavaFile file = new JavaFile("com.example", null, spec, new TypeCollector("com.example"));
    return SourceRenderer.render(file, GoogleJavaFormatter.getInstance());
  }

  @Test
  void jacksonOnCarriesTheAnySurfaceComponent() {
    String source = render(true);
    assertTrue(
        source.contains("Map<String, JsonNode> jsonExtras"),
        "expected the jsonExtras component: " + source);
    assertTrue(
        source.contains("@JsonAnyGetter") && source.contains("@JsonAnySetter"),
        "expected the any-getter/any-setter annotations: " + source);
    assertTrue(
        source.contains("@JsonIgnore"),
        "expected @JsonIgnore on the extras component and the carrier: " + source);
    assertTrue(
        source.contains("@Nullable"),
        "expected the extras component to be nullable (Jackson passes null when no unknowns): "
            + source);
  }

  @Test
  void jacksonOnNormalizesExtrasAndTheCarrierInTheCompactConstructor() {
    String source = render(true);
    assertTrue(
        source.contains("name = name == null ? \"\" : name"),
        "expected the compact constructor to normalize explicit-null scalars to their proto3 "
            + "default: "
            + source);
    assertTrue(
        source.contains("jsonExtras == null"),
        "expected the compact constructor to normalize the nullable extras: " + source);
    assertTrue(
        source.contains("LinkedHashMap"),
        "expected the compact constructor to rebind the extras to a fresh mutable map: " + source);
  }

  @Test
  void jacksonOnWiresTheBuilder() {
    String source = render(true);
    // The builder's fluent setter for the retained properties (mirrors the carrier setter).
    assertTrue(
        source.contains("public Builder jsonExtras(") || source.contains("Builder jsonExtras("),
        "expected the builder's jsonExtras setter: " + source);
  }

  @Test
  void jacksonOnDecodePassesANullExtrasArgument() {
    String source = render(true).replace("\n", "").replace("  ", " ");
    assertTrue(
        source.contains("null, unknown.build()"),
        "expected the decode construction to pass a null extras argument before the carrier: "
            + source);
  }

  @Test
  void jacksonOnEncodeNeverWritesTheExtrasToTheWire() {
    String source = render(true);
    assertFalse(
        source.contains("v.jsonExtras"),
        "the codec must not read the extras: they are JSON-only: " + source);
    assertTrue(
        source.contains("v.unknownFields().writeTo(out)"),
        "the encode still re-emits the binary unknown-field carrier: " + source);
  }

  @Test
  void fileWithoutTheToggleCarriesNoneOfTheAnySurface() {
    String source = render(false);
    assertFalse(
        source.contains("jsonExtras"),
        "jackson-off output must not mention the surface: " + source);
    assertFalse(
        source.contains("JsonNode"),
        "jackson-off output must not import or reference Jackson node types: " + source);
    assertFalse(
        source.contains("JsonAnyGetter"),
        "jackson-off output must not carry any-surface annotations: " + source);
    assertFalse(
        source.contains("jackson"),
        "jackson-off output must be free of the feature's vocabulary: " + source);
    // and the rendering is deterministic.
    assertEquals(source, render(false));
  }
}
