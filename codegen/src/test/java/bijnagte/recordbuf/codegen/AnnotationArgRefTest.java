package bijnagte.recordbuf.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.Arg;
import bijnagte.recordbuf.codegen.source.ArgValue;
import bijnagte.recordbuf.codegen.source.Component;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.TypeRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link ArgValue} rendering on the {@link SourceRenderer}: a string value renders quoted;
 * a type-reference value renders through the file's {@link TypeCollector} (imported as a short
 * name, or fully-qualified at every site on a short-name collision) plus the value's suffix -- the
 * mechanism the jackson custom (de)serializer pair rides on ({@code using = JsonTemporal.X.class}).
 * Strings and refs may mix within one argument list.
 */
class AnnotationArgRefTest {

  private static final String PKG = "bijnagte.recordbuf.fixture";

  private static String render(RecordSpec rec) {
    JavaFile file = new JavaFile(PKG, Javadoc.empty(), rec, new TypeCollector(PKG));
    return SourceRenderer.render(file);
  }

  private static Annotation jsonSerialize(String temporalFqn) {
    TypeRef t = TypeRef.of(temporalFqn);
    return new Annotation(
        TypeRef.of("tools.jackson.databind.annotation.JsonSerialize"),
        List.of(
            Arg.of("using", ArgValue.classLit(t)), Arg.of("contentUsing", ArgValue.classLit(t))));
  }

  @Test
  void refArgRendersImportedShortNameWithClassSuffix() {
    RecordSpec rec =
        RecordSpec.named("Doc")
            .public_()
            .component(
                Component.type(TypeRef.of("java.time.Instant"), "ts")
                    .annotations(
                        List.of(
                            jsonSerialize("bijnagte.recordbuf.json.JsonTemporal"),
                            Annotation.SUPPRESS_ALL))
                    .build())
            .build();

    String out = render(rec);
    assertTrue(
        out.contains("import bijnagte.recordbuf.json.JsonTemporal;"),
        "the class-literal's type must be imported: " + out);
    assertTrue(out.contains("using=JsonTemporal.class"), out);
    assertTrue(out.contains("contentUsing=JsonTemporal.class"), out);
    // String-argument regression pin: still quoted, unaffected by the Ref form.
    assertTrue(out.contains("@SuppressWarnings(\"all\")"), out);
  }

  @Test
  void collidedShortNameRendersBothSitesFullyQualifiedAndImportsNeither() {
    RecordSpec rec =
        RecordSpec.named("Doc")
            .public_()
            .component(
                Component.type(TypeRef.of("java.time.Instant"), "a")
                    .annotations(List.of(jsonSerialize("com.x.JsonTemporal")))
                    .build())
            .component(
                Component.type(TypeRef.of("java.time.Instant"), "b")
                    .annotations(List.of(jsonSerialize("com.y.JsonTemporal")))
                    .build())
            .build();

    String out = render(rec);
    assertFalse(out.contains("import com.x.JsonTemporal;"), out);
    assertFalse(out.contains("import com.y.JsonTemporal;"), out);
    assertTrue(out.contains("using=com.x.JsonTemporal.class"), out);
    assertTrue(out.contains("using=com.y.JsonTemporal.class"), out);
  }

  @Test
  void stringArrayArgRendersQuotedAndBraced() {
    RecordSpec rec =
        RecordSpec.named("Doc")
            .public_()
            .annotations(List.of(Annotation.suppressWarnings("all", "unused")))
            .build();
    String out = render(rec);
    assertTrue(out.contains("@SuppressWarnings({\"all\", \"unused\"})"), out);
  }
}
