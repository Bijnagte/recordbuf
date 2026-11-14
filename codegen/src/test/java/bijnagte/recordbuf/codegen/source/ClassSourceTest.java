package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The M4-C2b-2a gate: the M4-C2a AST can now also render a nested, statically-scoped <em>class</em>
 * -- the per-message {@code Codec} that M4-C2b-2+ will fill with {@code encode}/{@code decode}.
 * This is the first record/codec shape on the AST: a record (the {@code Message} shell) owning a
 * nested {@code public static final class Codec} whose private constructor + two trivial method
 * bodies prove, through {@link SourceRenderer} + the in-process {@code javac}, that a static class
 * nests inside a record and renders compilable Java. The method BODIES are {@code List<Element>}s
 * already (the "line-emission for the rest" half), so no further AST node is needed for the codec
 * skeleton; M4-C2b-2b fills {@code encode}/{@code decode}'s bodies with the real per-field wire
 * logic.
 */
class ClassSourceTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  /** A message shell record with a nested {@code public static final class Codec} skeleton. */
  private static Type.Record messageWithCodec() {
    Type.Class codec =
        Type.Class.named("Codec")
            .public_()
            .static_()
            .final_()
            .javadoc(new Javadoc("Wire codec for the MessageRec record."))
            // M4-C2b-2b-1: the codec's members are built with @RecordBuilder's fluent builders,
            // so a caller sets only the elements it needs -- and the `throws` clause rides on the
            // new `thrown` field (M4-C2b's per-message encode/decode are `throws IOException`).
            .constructor(Constructor.builder().private_().build())
            .method(
                Method.named("encode")
                    .public_()
                    .static_()
                    .returnType(TypeRef.BYTES)
                    .param(TypeRef.of("MessageRec"), "v")
                    .thrown(TypeRef.IO_EXCEPTION)
                    .body(Element.of("return new byte[0];"))
                    .build(),
                Method.named("decode")
                    .public_()
                    .static_()
                    .returnType(TypeRef.of("MessageRec"))
                    .param(TypeRef.BYTES, "inBytes")
                    .thrown(TypeRef.IO_EXCEPTION)
                    .body(Element.of("return new MessageRec(0);"))
                    .build())
            .build();
    return Type.Record.named("MessageRec")
        .public_()
        .component(Component.named("int", "id").build())
        .nested(codec)
        .build();
  }

  @Test
  void renders_a_nested_static_class_through_the_ast_and_compiles() throws Exception {
    TypeCollector ci = new TypeCollector("bijnagte.recordbuf.codegen.source");
    String src =
        SourceRenderer.render(
            new JavaFile(
                "bijnagte.recordbuf.codegen.source", new Javadoc(""), messageWithCodec(), ci));

    // The shape that M4-C2b-2 targets: a message record that owns a nested static Codec class.
    assertTrue(src.contains("public record MessageRec("), "the record shell: " + src);
    assertTrue(src.contains("public static final class Codec"), "a nested static class: " + src);
    assertTrue(src.contains("private Codec()"), "the Codec's private constructor: " + src);
    assertTrue(src.contains("byte[] encode(MessageRec v)"), "a static encode method: " + src);
    assertTrue(src.contains("MessageRec decode(byte[] inBytes)"), "a static decode method: " + src);
    assertTrue(
        src.contains("throws IOException"),
        "the codec's throws clause renders (java.io.IOException resolved to a short name): " + src);
    assertTrue(src.contains("import java.io.IOException;"), "the thrown type is imported: " + src);
    assertFalse(src.contains("record Codec"), "a class renders as `class`, not `record`: " + src);
    assertCompiles("MessageRec", src);
  }

  /**
   * Compile to a temp dir; fail loudly on a missing compiler (a JRE) or any diagnostic. Mirrors
   * {@link JavaSourceTest#assertCompiles(String)} -- the trivial-skeleton case with no external
   * runtime on the compile classpath.
   */
  private static void assertCompiles(String className, String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/codegen/source/" + className + ".java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-class-javac");
    boolean ok =
        compiler
            .getTask(null, null, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    String diagStr =
        diags.getDiagnostics().stream()
            .map(d -> d.toString())
            .collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(
        ok,
        "rendered source must compile:\n"
            + source
            + "\n-- diagnostics --\n"
            + diagStr
            + "\n-- raw --\n"
            + diags);
  }

  /**
   * The M4-C2b-2b-2 gate: a record that OWNS a nested {@code public static final class Codec} whose
   * {@code encode}/ {@code decode} bodies are REAL scalar codec bodies -- they call the runtime's
   * {@link bijnagte.recordbuf.runtime.Encoder} / {@link bijnagte.recordbuf.runtime.Decoder} /
   * {@link bijnagte.recordbuf.runtime.Wire} / {@link bijnagte.recordbuf.runtime.CodecDefaults}
   * helpers just as {@code CodecGenerator} does today, but expressed via {@link Element}s in the
   * source AST. The bodies reference the runtime module's classes fully-qualified so the file needs
   * no runtime imports; the compile classpath is the test's own runtime classpath so {@code javac}
   * sees the referenced classes. The first real codec -- singular int fields, no oneof, no WKT, no
   * nested-message resolution, no unknown-field carrier -- the subset the plan scoped as the
   * M4-C2b-2b-2 gate. M4-C2b-2b-3+ (deferred) fills in the remaining field families.
   */
  @Test
  void message_owns_a_nested_static_codec_with_real_scalar_bodies() throws Exception {
    TypeCollector ci = new TypeCollector("bijnagte.recordbuf.codegen.source");
    String src =
        SourceRenderer.render(
            new JavaFile(
                "bijnagte.recordbuf.codegen.source", new Javadoc(""), recordWithRealCodec(), ci));
    // The shape assertions: the nested Codec class + its encode/decode signatures + the runtime
    // references in the bodies all render.
    assertTrue(src.contains("public static final class Codec"), "a nested static class: " + src);
    assertTrue(
        src.contains("byte[] encode(RecordWithRealCodec v) throws IOException"),
        "the encode signature + its throws clause: " + src);
    assertTrue(
        src.contains("RecordWithRealCodec decode(byte[] inBytes) throws IOException"),
        "the decode signature + its throws clause: " + src);
    assertTrue(
        src.contains("bijnagte.recordbuf.runtime.Encoder.start()"),
        "the codec's body calls the runtime Encoder: " + src);
    assertTrue(
        src.contains("bijnagte.recordbuf.runtime.Wire.readTag(in)"),
        "the decode body drives the tag loop via the runtime Wire: " + src);
    assertTrue(
        src.contains("bijnagte.recordbuf.runtime.CodecDefaults.int32()"),
        "the codec body reads the per-field default from the runtime CodecDefaults: " + src);
    // The body references FQN runtime types (no import lines generated): the file is self-contained
    // apart from java.io.IOException (which the TypeCollector resolves + imports).
    assertFalse(
        src.contains("import bijnagte.recordbuf.runtime"),
        "no runtime imports needed -- bodies use fully-qualified names: " + src);
    assertTrue(src.contains("import java.io.IOException;"), "throws type is imported: " + src);
    // Then the hard gate: the rendered source compiles, with the test's runtime classpath so
    // javac sees the referenced runtime classes.
    assertCompilesWithRuntime("RecordWithRealCodec", src);
  }

  /** A record that owns a nested {@code Codec} with real scalar encode/decode bodies. */
  /**
   * M4-C2b-2b-3 gate: a record with THREE scalar fields (int, long, String) and a nested {@code
   * Codec} whose encode/decode bodies are real multi-scalar codec bodies -- proving the shape works
   * for more than one field family and more than one wire type in the same codec. Unlike {@link
   * #recordWithRealCodec()} which handles a single int field, this handles int + long + string,
   * exercising {@code writeInt32 / writeInt64 / writeString} on the encode side and {@code
   * readInt32 / readInt64 / readString} on the decode side, all under one tag-loop switch with a
   * {@code default -> Wire.skip(in, tag)} arm. The shape gate is that these three field families
   * render + compile via the source AST with no manual code writing.
   */
  @Test
  void message_owns_a_nested_static_codec_with_real_multi_scalar_bodies() throws Exception {
    TypeCollector ci = new TypeCollector("bijnagte.recordbuf.codegen.source");
    String src =
        SourceRenderer.render(
            new JavaFile(
                "bijnagte.recordbuf.codegen.source",
                new Javadoc(""),
                recordWithRealMultiScalarCodec(),
                ci));
    // Shape assertions: the nested Codec class + its encode/decode signatures + all three
    // wire-type helpers referenced in the body (int32, int64, string).
    assertTrue(src.contains("class Codec"), "the nested Codec class must render");
    assertTrue(
        src.contains("byte[] encode(RecordWithRealMultiScalar v)"),
        "the encode signature must render through the AST");
    assertTrue(
        src.contains("RecordWithRealMultiScalar decode(byte[] inBytes)"),
        "the decode signature must render through the AST");
    assertTrue(src.contains("writeInt32(1, v.id())"), "field 1: int32 write guard");
    assertTrue(src.contains("writeInt64(2, v.value())"), "field 2: int64 write guard");
    assertTrue(src.contains("writeString(3, v.text())"), "field 3: string write guard");
    assertTrue(src.contains("readInt32()"), "field 1: int32 read");
    assertTrue(src.contains("readInt64()"), "field 2: int64 read");
    assertTrue(src.contains("readString()"), "field 3: string read");
    assertTrue(
        src.contains("default -> bijnagte.recordbuf.runtime.Wire.skip(in, tag);"),
        "unknown tag must skip");
    // No placeholder / no TODO / no empty body -- the first real multi-scalar codec body.
    assertFalse(src.contains("TODO"), "no TODO placeholders in a real codec body");
    assertFalse(src.contains("/* */"), "no empty body comments in a real codec body");
    assertTrue(src.contains("public record RecordWithRealMultiScalar"), "the record shell");
    // Compile: the multi-scalar codec body must compile against the runtime classpath.
    assertCompilesWithRuntime("RecordWithRealMultiScalar", src);
    System.out.println("--- M4-C2b-2b-3 multi-scalar codec source ---\n" + src);
  }

  /**
   * M4-C2b-2c gate: a semantic-equivalence round-trip. Renders the multi-scalar codec source,
   * compiles it to a temp dir with the runtime on the compile classpath, loads the resulting
   * classes via a URLClassLoader with the same runtime classpath, calls {@code
   * Codec.encode(record)} via reflection, then {@code Codec.decode(bytes)} via reflection, and
   * asserts the decoded record's field values match the original byte-for-byte. This is the first
   * proof that the source-AST shape produces runnable code that behaves correctly, not just
   * compiles.
   */
  @Test
  void multi_scalar_codec_round_trips_via_urlclassloader_and_reflection() throws Exception {
    String pkg = "bijnagte.recordbuf.codegen.source.roundtrip";
    TypeCollector ci = new TypeCollector(pkg);
    String src =
        SourceRenderer.render(
            new JavaFile(pkg, new Javadoc(""), recordWithRealMultiScalarCodec(), ci));

    // 1. Compile to a temp dir with the runtime on the classpath.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    var fm = compiler.getStandardFileManager(diags, null, null);
    List<java.io.File> cp =
        Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(java.io.File::new)
            .toList();
    fm.setLocation(StandardLocation.CLASS_PATH, cp);
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/" + pkg.replace('.', '/') + "/RecordWithRealMultiScalar.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return src;
          }
        };
    Path outDir = Files.createTempDirectory("recordbuf-roundtrip");
    boolean ok =
        compiler
            .getTask(null, fm, diags, List.of("-d", outDir.toString()), null, List.of(unit))
            .call();
    assertTrue(ok, "multi-scalar codec must compile:\n" + src + "\n" + diags);

    // 2. Load the compiled classes + the runtime via a URLClassLoader with the compile classpath.
    List<java.net.URL> urlList = new java.util.ArrayList<>();
    for (java.io.File f : cp) {
      urlList.add(f.toURI().toURL());
    }
    urlList.add(outDir.toUri().toURL());

    try (var loader =
        new java.net.URLClassLoader(
            urlList.toArray(new java.net.URL[0]), ClassLoader.getSystemClassLoader())) {
      Class<?> recordClass = loader.loadClass(pkg + ".RecordWithRealMultiScalar");
      Class<?> codecClass = loader.loadClass(pkg + ".RecordWithRealMultiScalar$Codec");

      // 3. Construct a record instance via its canonical constructor.
      java.lang.reflect.Constructor<?> ctor =
          recordClass.getDeclaredConstructor(int.class, long.class, String.class);
      Object original = ctor.newInstance(42, 99L, "hello");

      // 4. Call Codec.encode(record) -> byte[].
      java.lang.reflect.Method encodeMethod = codecClass.getMethod("encode", recordClass);
      byte[] bytes = (byte[]) encodeMethod.invoke(null, original);
      assertNotNull(bytes, "encode must produce bytes");
      assertTrue(bytes.length > 0, "encode must produce non-empty bytes");

      // 5. Call Codec.decode(bytes) -> RecordWithRealMultiScalar2.
      java.lang.reflect.Method decodeMethod = codecClass.getMethod("decode", byte[].class);
      Object decoded = decodeMethod.invoke(null, bytes);
      assertNotNull(decoded, "decode must produce a record");

      // 6. Assert the decoded record's field values match the original.
      int decodedId = (int) recordClass.getMethod("id").invoke(decoded);
      long decodedValue = ((Number) recordClass.getMethod("value").invoke(decoded)).longValue();
      String decodedText = (String) recordClass.getMethod("text").invoke(decoded);
      assertEquals(42, decodedId, "decoded id must round-trip");
      assertEquals(99, decodedValue, "decoded value must round-trip");
      assertEquals("hello", decodedText, "decoded text must round-trip");
    }
  }

  private static Type.Record recordWithRealMultiScalarCodec() {
    Type.Class codec =
        Type.Class.named("Codec")
            .public_()
            .static_()
            .final_()
            .javadoc(new Javadoc("Wire codec for the RecordWithRealMultiScalar record."))
            .constructor(Constructor.builder().private_().build())
            .method(
                // M4-C2b-2b-3: the multi-scalar encode body -- three guarded writes for int
                // (field 1), long (field 2), and String (field 3).
                Method.named("encode")
                    .public_()
                    .static_()
                    .returnType(TypeRef.BYTES)
                    .param(TypeRef.of("RecordWithRealMultiScalar"), "v")
                    .thrown(TypeRef.IO_EXCEPTION)
                    .body(
                        Element.of("var enc = bijnagte.recordbuf.runtime.Encoder.start();"),
                        Element.of("var out = enc.out();"),
                        Element.of(
                            "if (v.id() != bijnagte.recordbuf.runtime.CodecDefaults.int32()) "
                                + "out.writeInt32(1, v.id());"),
                        Element.of(
                            "if (v.value() != bijnagte.recordbuf.runtime.CodecDefaults.int64()) "
                                + "out.writeInt64(2, v.value());"),
                        Element.of("if (!v.text().isEmpty()) " + "out.writeString(3, v.text());"),
                        Element.of("return enc.finish();"))
                    .build(),
                // M4-C2b-2b-3: the multi-scalar decode body -- three per-field default-init,
                // then a tag-loop switch that dispatches to the correct read for each field
                // number and skips unknown tags.
                Method.named("decode")
                    .public_()
                    .static_()
                    .returnType(TypeRef.of("RecordWithRealMultiScalar"))
                    .param(TypeRef.BYTES, "inBytes")
                    .thrown(TypeRef.IO_EXCEPTION)
                    .body(
                        Element.of("var dec = bijnagte.recordbuf.runtime.Decoder.start(inBytes);"),
                        Element.of("var in = dec.in();"),
                        Element.of("int id = bijnagte.recordbuf.runtime.CodecDefaults.int32();"),
                        Element.of(
                            "long value = bijnagte.recordbuf.runtime.CodecDefaults.int64();"),
                        Element.of(
                            "String text = bijnagte.recordbuf.runtime.CodecDefaults.string();"),
                        Element.of("while (true) {"),
                        Element.of("    int tag = bijnagte.recordbuf.runtime.Wire.readTag(in);"),
                        Element.of("    if (tag == 0) break;"),
                        Element.of(
                            "    switch (bijnagte.recordbuf.runtime.Wire.tagFieldNumber(tag)) {"),
                        Element.of("      case 1 -> id = in.readInt32();"),
                        Element.of("      case 2 -> value = in.readInt64();"),
                        Element.of("      case 3 -> text = in.readString();"),
                        Element.of(
                            "      default -> bijnagte.recordbuf.runtime.Wire.skip(in, tag);"),
                        Element.of("    }"),
                        Element.of("}"),
                        Element.of("return new RecordWithRealMultiScalar(id, value, text);"))
                    .build())
            .build();
    return Type.Record.named("RecordWithRealMultiScalar")
        .public_()
        .component(
            Component.named("int", "id").build(),
            Component.named("long", "value").build(),
            Component.named("String", "text").build())
        .nested(codec)
        .build();
  }

  private static Type.Record recordWithRealCodec() {
    Type.Class codec =
        Type.Class.named("Codec")
            .public_()
            .static_()
            .final_()
            .javadoc(new Javadoc("Wire codec for the RecordWithRealCodec record."))
            .constructor(Constructor.builder().private_().build())
            .method(
                // M4-C2b-2b-2: the codec's encode is the real scalar-encode body -- a fresh
                // runtime Encoder, a per-field guarded write, and a finish. No oneof / no WKT /
                // no nested-message / no unknown-field carrier -- deferred to M4-C2b-2b-3+.
                Method.named("encode")
                    .public_()
                    .static_()
                    .returnType(TypeRef.BYTES)
                    .param(TypeRef.of("RecordWithRealCodec"), "v")
                    .thrown(TypeRef.IO_EXCEPTION)
                    .body(
                        Element.of("var enc = bijnagte.recordbuf.runtime.Encoder.start();"),
                        Element.of("var out = enc.out();"),
                        Element.of(
                            "if (v.id() != bijnagte.recordbuf.runtime.CodecDefaults.int32()) "
                                + "out.writeInt32(1, v.id());"),
                        Element.of("return enc.finish();"))
                    .build(),
                // The codec's decode is the real tag-loop body: a fresh runtime Decoder, a
                // per-field default-init, the tag/field-number loop, a switch that dispatches
                // to the field, and a return built from the locally-decoded values.
                Method.named("decode")
                    .public_()
                    .static_()
                    .returnType(TypeRef.of("RecordWithRealCodec"))
                    .param(TypeRef.BYTES, "inBytes")
                    .thrown(TypeRef.IO_EXCEPTION)
                    .body(
                        Element.of("var dec = bijnagte.recordbuf.runtime.Decoder.start(inBytes);"),
                        Element.of("var in = dec.in();"),
                        Element.of("int id = bijnagte.recordbuf.runtime.CodecDefaults.int32();"),
                        Element.of("while (true) {"),
                        Element.of("    int tag = bijnagte.recordbuf.runtime.Wire.readTag(in);"),
                        Element.of("    if (tag == 0) break;"),
                        Element.of(
                            "    switch (bijnagte.recordbuf.runtime.Wire.tagFieldNumber(tag)) {"),
                        Element.of("      case 1 -> id = in.readInt32();"),
                        Element.of(
                            "      default -> bijnagte.recordbuf.runtime.Wire.skip(in, tag);"),
                        Element.of("    }"),
                        Element.of("}"),
                        Element.of("return new RecordWithRealCodec(id);"))
                    .build())
            .build();
    return Type.Record.named("RecordWithRealCodec")
        .public_()
        .component(Component.named("int", "id").build())
        .nested(codec)
        .build();
  }

  /**
   * Compile against the test's own runtime classpath so a real-codec body can reference runtime.
   */
  @Test
  void record_with_bytes_component_renders_and_compiles() throws Exception {
    TypeCollector ci = new TypeCollector(PKG);

    var dataField = Component.named("bijnagte.recordbuf.runtime.Bytes", "data").build();

    var encode =
        Method.named("encode")
            .public_()
            .static_()
            .returnType(TypeRef.BYTES)
            .param(TypeRef.of("RecordWithBytes"), "v")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(
                Element.of("var enc = bijnagte.recordbuf.runtime.Encoder.start();"),
                Element.of("var out = enc.out();"),
                Element.of("out.writeByteArray(1, v.data().toByteArray());"),
                Element.of("return enc.finish();"))
            .build();

    var decode =
        Method.named("decode")
            .public_()
            .static_()
            .returnType(TypeRef.of("RecordWithBytes"))
            .param(TypeRef.BYTES, "inBytes")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(
                Element.of("var dec = bijnagte.recordbuf.runtime.Decoder.start(inBytes);"),
                Element.of("var in = dec.in();"),
                Element.of(
                    "bijnagte.recordbuf.runtime.Bytes data = bijnagte.recordbuf.runtime.Bytes.empty();"),
                Element.of("while (true) {"),
                Element.of("    int tag = bijnagte.recordbuf.runtime.Wire.readTag(in);"),
                Element.of("    if (tag == 0) break;"),
                Element.of("    switch (bijnagte.recordbuf.runtime.Wire.tagFieldNumber(tag)) {"),
                Element.of(
                    "      case 1 -> data = bijnagte.recordbuf.runtime.Bytes.of(in.readBytes().toByteArray());"),
                Element.of("      default -> bijnagte.recordbuf.runtime.Wire.skip(in, tag);"),
                Element.of("    }"),
                Element.of("}"),
                Element.of("return new RecordWithBytes(data);"))
            .build();

    Type.Class codec =
        Type.Class.named("Codec").public_().static_().final_().method(encode, decode).build();

    var record =
        Type.Record.named("RecordWithBytes")
            .public_()
            .final_()
            .component(dataField)
            .nested(codec)
            .build();

    String src = SourceRenderer.render(new JavaFile(PKG, new Javadoc(""), record, ci));
    assertCompilesWithRuntime("RecordWithBytes", src);
  }

  @Test
  void record_with_bytes_round_trips_via_urlclassloader_and_reflection() throws Exception {
    TypeCollector ci = new TypeCollector(PKG);

    var dataField = Component.named("bijnagte.recordbuf.runtime.Bytes", "data").build();

    var encode =
        Method.named("encode")
            .public_()
            .static_()
            .returnType(TypeRef.BYTES)
            .param(TypeRef.of("RecordWithBytes"), "v")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(
                Element.of("var enc = bijnagte.recordbuf.runtime.Encoder.start();"),
                Element.of("var out = enc.out();"),
                Element.of("out.writeByteArray(1, v.data().toByteArray());"),
                Element.of("return enc.finish();"))
            .build();

    var decode =
        Method.named("decode")
            .public_()
            .static_()
            .returnType(TypeRef.of("RecordWithBytes"))
            .param(TypeRef.BYTES, "inBytes")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(
                Element.of("var dec = bijnagte.recordbuf.runtime.Decoder.start(inBytes);"),
                Element.of("var in = dec.in();"),
                Element.of(
                    "bijnagte.recordbuf.runtime.Bytes data = bijnagte.recordbuf.runtime.Bytes.empty();"),
                Element.of("while (true) {"),
                Element.of("    int tag = bijnagte.recordbuf.runtime.Wire.readTag(in);"),
                Element.of("    if (tag == 0) break;"),
                Element.of("    switch (bijnagte.recordbuf.runtime.Wire.tagFieldNumber(tag)) {"),
                Element.of(
                    "      case 1 -> data = bijnagte.recordbuf.runtime.Bytes.of(in.readBytes().toByteArray());"),
                Element.of("      default -> bijnagte.recordbuf.runtime.Wire.skip(in, tag);"),
                Element.of("    }"),
                Element.of("}"),
                Element.of("return new RecordWithBytes(data);"))
            .build();

    Type.Class codec =
        Type.Class.named("Codec").public_().static_().final_().method(encode, decode).build();

    var record =
        Type.Record.named("RecordWithBytes")
            .public_()
            .final_()
            .component(dataField)
            .nested(codec)
            .build();

    String pkg = "bijnagte.recordbuf.codegen.source.roundtrip";
    ci = new TypeCollector(pkg);
    String src = SourceRenderer.render(new JavaFile(pkg, new Javadoc(""), record, ci));

    // 1. Compile to a temp dir with the runtime on the classpath.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    var fm = compiler.getStandardFileManager(diags, null, null);
    List<java.io.File> cp =
        Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(java.io.File::new)
            .toList();
    fm.setLocation(StandardLocation.CLASS_PATH, cp);
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/" + pkg.replace('.', '/') + "/RecordWithBytes.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return src;
          }
        };
    Path outDir = Files.createTempDirectory("recordbuf-bytes-roundtrip");
    boolean ok =
        compiler
            .getTask(null, fm, diags, List.of("-d", outDir.toString()), null, List.of(unit))
            .call();
    assertTrue(ok, "bytes codec must compile:\n" + src + "\n" + diags);

    // 2. Load + run the round-trip.
    List<java.net.URL> urlList = new java.util.ArrayList<>();
    for (java.io.File f : cp) urlList.add(f.toURI().toURL());
    urlList.add(outDir.toUri().toURL());

    try (var loader =
        new java.net.URLClassLoader(
            urlList.toArray(new java.net.URL[0]), ClassLoader.getSystemClassLoader())) {
      Class<?> recordClass = loader.loadClass(pkg + ".RecordWithBytes");
      Class<?> codecClass = loader.loadClass(pkg + ".RecordWithBytes$Codec");
      bijnagte.recordbuf.runtime.Bytes originalBytes =
          bijnagte.recordbuf.runtime.Bytes.of(
              "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      java.lang.reflect.Constructor<?> ctor =
          recordClass.getDeclaredConstructor(bijnagte.recordbuf.runtime.Bytes.class);
      Object original = ctor.newInstance(originalBytes);
      java.lang.reflect.Method encodeMethod = codecClass.getMethod("encode", recordClass);
      byte[] encoded = (byte[]) encodeMethod.invoke(null, original);
      assertNotNull(encoded, "encode must produce bytes");
      assertTrue(encoded.length > 0, "encode must produce non-empty bytes");
      java.lang.reflect.Method decodeMethod = codecClass.getMethod("decode", byte[].class);
      Object decoded = decodeMethod.invoke(null, encoded);
      assertNotNull(decoded, "decode must produce a record");
      bijnagte.recordbuf.runtime.Bytes decodedBytes =
          (bijnagte.recordbuf.runtime.Bytes) recordClass.getMethod("data").invoke(decoded);
      assertArrayEquals(
          "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8),
          decodedBytes.toByteArray());
    }
  }

  private static void assertCompilesWithRuntime(String className, String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    var fm = compiler.getStandardFileManager(diags, null, null);
    // Pass the test module's runtime classpath (which includes :recordbuf-runtime + protobuf-java
    // transitively) as the compiler's CLASS_PATH so the codec's FQN references resolve.
    List<java.io.File> cp =
        Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(java.io.File::new)
            .toList();
    fm.setLocation(StandardLocation.CLASS_PATH, cp);
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/codegen/source/" + className + ".java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-codec-javac");
    boolean ok =
        compiler
            .getTask(null, fm, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    String diagStr =
        diags.getDiagnostics().stream()
            .map(d -> d.toString())
            .collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(
        ok,
        "rendered source must compile:\n"
            + source
            + "\n-- diagnostics --\n"
            + diagStr
            + "\n-- raw --\n"
            + diags);
  }
}
