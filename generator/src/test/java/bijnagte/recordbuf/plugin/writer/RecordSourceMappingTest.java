package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
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
 * The M4-C2b-1 gate: map a proto {@link Message} model into the {@code source} AST through {@link
 * RecordSourceMapping}, render it with {@link SourceRenderer}, and compile the rendered source
 * in-process (mirroring {@link EnumSourceMappingTest} and {@code JavaSourceTest}). This proves the
 * record <em>shell</em> -- the record header ({@code public record Address(int, String)} with its
 * Javadoc + scalar components), plus its nested {@code Color} enum (reusing the M4-C2a-ext mapping,
 * so the nested enum's {@code BY_NUMBER} cluster renders inside the record body with its shared
 * {@code java.util.*} imports) -- emits through the pure-Java AST and compiles, with NO codec yet
 * (encode/decode is M4-C2b-2+).
 *
 * <p>The fixture has only scalar components, so no generic-component rendering (a M4-C2b-2+ fix).
 * M4-C2b-1 renders + compiles it WITHOUT touching {@code CodeGenerator} or the live emit.
 */
class RecordSourceMappingTest {

  private static final String PKG = "bijnagte.recordbuf.plugin.writer.out";

  @Test
  void maps_the_message_shell_through_the_source_ast_and_compiles() throws Exception {
    TypeCollector ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSource(address(PKG), PKG);
    String src = SourceRenderer.render(new JavaFile(PKG, Javadoc.builder().build(), rec, ci));

    // The record shell -- header + scalar components + the message's Javadoc; no codec yet.
    assertTrue(src.contains("public record Address("), "the record header: " + src);
    assertTrue(src.contains("int houseNumber"), "a scalar component (int32 -> int): " + src);
    assertTrue(src.contains("String street"), "a scalar component (string -> String): " + src);
    assertTrue(src.contains("A street address."), "the message's Javadoc: " + src);

    // The nested enum reuses the M4-C2a-ext mapping, so its full core + BY_NUMBER cluster render
    // inside the record body with shared java.util.* imports.
    assertTrue(src.contains("enum Color"), "the nested enum rendered: " + src);
    assertTrue(src.contains("RED(0)"), "the nested enum's numbered constants: " + src);
    assertTrue(
        src.contains("private static final Map<Integer, Color> BY_NUMBER"),
        "the nested enum's reverse-lookup cluster rendered: " + src);
    assertTrue(src.contains("import java.util.HashMap;"), "shared HashMap import: " + src);

    // No codec yet -- encode/decode + the unknown-field carrier are M4-C2b-2+.
    assertFalse(src.contains("encode"), "no encode in the M4-C2b-1 shell: " + src);
    assertFalse(src.contains("decode"), "no decode in the M4-C2b-1 shell: " + src);
    assertFalse(src.contains("UnknownFields"), "no unknown-field carrier yet: " + src);

    assertCompiles(src);
  }

  /**
   * The M4-C2b-2c gate: map a proto {@link Message} to the source AST with its nested {@code Codec}
   * rendered, and compile the source in-memory against the test's own runtime classpath. This
   * proves the mapping emits a record + its nested codec (encode/decode static methods with real
   * {@code List<Element>} bodies), and the rendered source is structurally correct enough for
   * javac.
   */
  @Test
  void maps_the_message_with_nested_codec_through_the_source_ast_and_compiles() throws Exception {
    TypeCollector ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSourceWithCodec(address(PKG));
    String src = SourceRenderer.render(new JavaFile(PKG, Javadoc.builder().build(), rec, ci));

    // The shell is still there:
    assertTrue(src.contains("public record Address("), "the record header: " + src);
    assertTrue(src.contains("int houseNumber"), "a scalar component: " + src);

    // The nested Codec is present with real encode/decode bodies:
    assertTrue(src.contains("public static final class AddressCodec"), "the nested Codec: " + src);
    assertTrue(
        src.contains("byte[] encode(Address v) throws IOException"),
        "the codec's encode signature: " + src);
    assertTrue(
        src.contains("Address decode(byte[] inBytes) throws IOException"),
        "the codec's decode signature: " + src);

    // The codec's encode body has real per-field guarded writes:
    assertTrue(
        src.contains("v.houseNumber()"), "the encode body references the field getter: " + src);
    assertTrue(
        src.contains("CodecDefaults.int32()"), "the encode guard uses CodecDefaults: " + src);

    // The codec's decode body has the tag-loop structure:
    assertTrue(
        src.contains("Wire.readTag"),
        "the decode body uses the Wire short-form tag reader: " + src);

    // The shell-only assertion from M4-C2b-1 (NO codec) no longer holds: encode/decode are present.
    assertTrue(src.contains("encode"), "encode is present in the M4-C2b-2c source: " + src);
    assertTrue(src.contains("decode"), "decode is present in the M4-C2b-2c source: " + src);

    // The codec references runtime types, so a compile with the runtime classpath is required.
    assertCompilesWithRuntime("Address", src);
  }

  /**
   * M4-C2b-2c integration round-trip: render the Address source via {@link
   * RecordSourceMapping#toSourceWithCodec}, compile it in-memory against the runtime classpath,
   * load {@code Address} + {@code Address.AddressCodec} via a URLClassLoader, then call {@code
   * Codec.encode(new Address(1, "Main"))} + {@code Codec.decode(byte[])} and assert the round-trip.
   * Mirrors {@code ClassSourceTest.round_trips} but through the plugin's mapping path rather than
   * hand-built AST.
   */
  @Test
  void codec_round_trips_through_the_source_ast_and_runtime() throws Exception {
    TypeCollector ci = new TypeCollector("bijnagte.recordbuf.plugin.writer.it");
    var rec = RecordSourceMapping.toSourceWithCodec(address("bijnagte.recordbuf.plugin.writer.it"));
    String src =
        SourceRenderer.render(
            new JavaFile(
                "bijnagte.recordbuf.plugin.writer.it", Javadoc.builder().build(), rec, ci));

    // Compile in-memory; output to a temp class dir.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required for this test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    var fm = compiler.getStandardFileManager(diags, null, null);
    List<File> cp =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(File::new)
            .toList();
    fm.setLocation(StandardLocation.CLASS_PATH, cp);
    Path out = Files.createTempDirectory("recordbuf-rt-javac");
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/plugin/writer/it/Address.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return src;
          }
        };
    boolean compiled =
        compiler
            .getTask(null, fm, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    assertTrue(
        compiled,
        "the rendered source must compile:\n" + src + "\n-- diags --\n" + diags.getDiagnostics());

    // Load via URLClassLoader.
    try (var ucl =
        new URLClassLoader(
            new URL[] {out.toUri().toURL()}, RecordSourceMappingTest.class.getClassLoader())) {
      Class<?> addressCls = ucl.loadClass("bijnagte.recordbuf.plugin.writer.it.Address");
      Class<?> codecCls = ucl.loadClass("bijnagte.recordbuf.plugin.writer.it.Address$AddressCodec");

      // M4-C2b-2b-5: the carrier is the 3rd component.
      var unknownFieldsCls = ucl.loadClass("bijnagte.recordbuf.runtime.UnknownFields");
      var emptyUf = unknownFieldsCls.getMethod("empty").invoke(null);
      var ctor = addressCls.getConstructor(int.class, String.class, unknownFieldsCls);
      Object addr = ctor.newInstance(1, "Main", emptyUf);
      var encode = codecCls.getMethod("encode", addressCls);
      byte[] encoded = (byte[]) encode.invoke(null, addr);
      assertNotNull(encoded, "encode() must produce a byte[]");

      // Codec.decode(byte[])
      var decode = codecCls.getMethod("decode", byte[].class);
      Object decoded = decode.invoke(null, (Object) encoded);
      assertNotNull(decoded, "decode() must produce an Address");

      // Assert the round-trip.
      var cn = addressCls.getMethods();
      for (var m : cn) {
        if (m.getName().equals("houseNumber")) {
          assertEquals(1, m.invoke(decoded));
        }
        if (m.getName().equals("street")) {
          assertEquals("Main", m.invoke(decoded));
        }
      }
    }
  }

  /**
   * Builder round-trip: render Address through source-AST, compile, and verify builder() ->
   * toBuilder() -> build() flows produce identical values.
   */
  @Test
  void builder_round_trips_through_the_source_ast() throws Exception {
    TypeCollector ci = new TypeCollector("bijnagte.recordbuf.plugin.writer.it");
    var rec = RecordSourceMapping.toSourceWithCodec(address("bijnagte.recordbuf.plugin.writer.it"));
    String src =
        SourceRenderer.render(
            new JavaFile(
                "bijnagte.recordbuf.plugin.writer.it", Javadoc.builder().build(), rec, ci));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    var diags = new DiagnosticCollector<JavaFileObject>();
    var fm = compiler.getStandardFileManager(diags, null, null);
    List<File> cp =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(File::new)
            .toList();
    fm.setLocation(StandardLocation.CLASS_PATH, cp);
    Path out = Files.createTempDirectory("recordbuf-builder-javac");
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/plugin/writer/it/Address.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return src;
          }
        };
    compiler.getTask(null, fm, diags, List.of("-d", out.toString()), null, List.of(unit)).call();

    try (var ucl =
        new URLClassLoader(
            new URL[] {out.toUri().toURL()}, RecordSourceMappingTest.class.getClassLoader())) {
      Class<?> addressCls = ucl.loadClass("bijnagte.recordbuf.plugin.writer.it.Address");

      // Build via builder()
      var builderFactory = addressCls.getMethod("builder");
      Object builder = builderFactory.invoke(null);
      var setHouseNumber = builder.getClass().getMethod("houseNumber", int.class);
      var setStreet = builder.getClass().getMethod("street", String.class);
      setHouseNumber.invoke(builder, 42);
      setStreet.invoke(builder, "Main");
      var build = builder.getClass().getMethod("build");
      Object addr1 = build.invoke(builder);

      // Verify houseNumber + street
      assertEquals(42, addressCls.getMethod("houseNumber").invoke(addr1));
      assertEquals("Main", addressCls.getMethod("street").invoke(addr1));

      // toBuilder then modify
      var toBuilder = addressCls.getMethod("toBuilder");
      Object builder2 = toBuilder.invoke(addr1);
      setStreet.invoke(builder2, "Second");
      Object addr2 = build.invoke(builder2);
      assertEquals("Second", addressCls.getMethod("street").invoke(addr2));
      assertEquals(42, addressCls.getMethod("houseNumber").invoke(addr2)); // unchanged
    }
  }

  /**
   * A representative proto3 message: two scalar fields + a nested enum, no oneof / map / nested
   * msg.
   */
  private static Message address(String pkg) {
    Field houseNumber =
        new Field(
            "houseNumber",
            "houseNumber",
            1,
            FieldLabel.REQUIRED,
            FieldKind.SCALAR,
            "int32",
            TypeRef.Primitive.INT,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    Field street =
        new Field(
            "street",
            "street",
            2,
            FieldLabel.REQUIRED,
            FieldKind.SCALAR,
            "string",
            TypeRef.STRING,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);

    Enum color =
        new Enum(
            "Color",
            List.of(
                new Enum.EnumConstant("RED", 0, false, null),
                new Enum.EnumConstant("GREEN", 1, false, "the green value"),
                new Enum.EnumConstant("BLUE", 2, false, null)),
            false,
            null,
            List.of());

    return new Message(
        "Address",
        TypeRef.named(pkg, "Address"),
        List.of(houseNumber, street),
        List.of(),
        List.of(color),
        List.of(),
        false,
        "A street address.",
        List.of(),
        null);
  }

  /** Compile the rendered source in-memory without the runtime classpath (record shell only). */
  private static void assertCompiles(String src) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/" + PKG.replace('.', '/') + "/Address.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return src;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-record-javac");
    boolean ok =
        compiler
            .getTask(null, null, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    assertTrue(
        ok,
        "rendered record shell must compile:\n"
            + src
            + "\n-- diagnostics --\n"
            + diags.getDiagnostics());
  }

  /**
   * Compile against the test's own runtime classpath (mirroring {@code
   * ClassSourceTest.assertCompilesWithRuntime}) so the codec's runtime references (Encoder,
   * Decoder, Wire, CodecDefaults, Bytes) resolve.
   */
  private static void assertCompilesWithRuntime(String className, String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    var fm = compiler.getStandardFileManager(diags, null, null);
    List<File> cp =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(File::new)
            .toList();
    fm.setLocation(StandardLocation.CLASS_PATH, cp);
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/" + PKG.replace('.', '/') + "/" + className + ".java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-with-codec-javac");
    boolean ok =
        compiler
            .getTask(null, fm, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    assertTrue(
        ok,
        "rendered record+codec must compile:\n"
            + source
            + "\n-- diagnostics --\n"
            + diags.getDiagnostics());
  }

  @Test
  void maps_a_repeated_scalar_through_the_source_ast_and_compiles() {
    // M4-C2b-2b-4: a `repeated int32 nums = 1` field is rendered as `List<Integer>` component.
    Field nums =
        new Field(
            "nums",
            "nums",
            1,
            FieldLabel.REPEATED,
            FieldKind.SCALAR,
            "int32",
            TypeRefs.LIST.withGenerics(TypeRef.INTEGER),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    Message m =
        new Message(
            "RepeatedMsg",
            TypeRef.named(PKG, "RepeatedMsg"),
            List.of(nums),
            List.of(),
            List.of(),
            List.of(),
            false,
            "A record with a repeated int32 field.",
            List.of(),
            null);

    TypeCollector ci = new TypeCollector(PKG);
    RecordSpec ast = RecordSourceMapping.toSourceWithCodec(m);
    String source =
        SourceRenderer.render(
            new JavaFile(
                PKG,
                Javadoc.builder().text("Generated by recordbuf -- DO NOT EDIT.").build(),
                ast,
                ci));
    assertTrue(
        source.contains("List") && source.contains("Integer"),
        "rendered repeated scalar must declare a List<Integer> component:\n" + source);
    assertTrue(source.contains("pushLimit"), "rendered codec must emit packed decode:\n" + source);
  }

  @Test
  void source_ast_emits_nullable_for_optional_scalar() throws Exception {
    var m =
        new Message(
            "OptionalMsg",
            TypeRef.named(PKG, "OptionalMsg"),
            List.of(
                new Field(
                    "opt",
                    "opt",
                    1,
                    FieldLabel.OPTIONAL,
                    FieldKind.SCALAR,
                    "int32",
                    TypeRef.INTEGER,
                    null,
                    null,
                    true,
                    null,
                    null,
                    false,
                    /* jacksonFormat= */ null,
                    "jsonName",
                    /* jsonIgnored= */ false)),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSourceWithCodec(m);
    var file =
        new JavaFile(
            PKG, Javadoc.builder().text("Generated by recordbuf -- DO NOT EDIT.").build(), rec, ci);
    var src = SourceRenderer.render(file);
    assertTrue(
        src.contains("@Nullable") && src.contains("Integer opt"),
        "optional scalar gets a @Nullable boxed type: " + src);
    assertCompilesWithRuntime("OptionalMsg", src);
  }

  /**
   * M4-C2d-2: a message with nested-message singular + repeated fields renders via the source AST.
   */
  @Test
  void source_ast_emits_nested_message_singular_and_repeated() {
    var single =
        new Field(
            "single",
            "single",
            1,
            FieldLabel.REQUIRED,
            FieldKind.MESSAGE,
            "bijnagte.recordbuf.fixture.message.Sub",
            TypeRef.named("", "Sub"),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var items =
        new Field(
            "items",
            "items",
            2,
            FieldLabel.REPEATED,
            FieldKind.MESSAGE,
            "bijnagte.recordbuf.fixture.message.Sub",
            TypeRefs.LIST.withGenerics(TypeRef.named("", "Sub")),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var parent =
        new Message(
            "Parent",
            TypeRef.named(PKG, "Parent"),
            List.of(single, items),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    TypeCollector ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSource(parent, PKG);
    var file = new JavaFile(PKG, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    assertTrue(src.contains("Sub single"), "singular nested-message field:\n" + src);
    assertTrue(src.contains("List<Sub> items"), "repeated nested-message:\n" + src);
  }

  /** M4-C2b-2b-12: a message with one group renders the sealed interface + @Nullable component. */
  @Test
  void source_ast_emits_oneof_sealed_interface() {
    var oneofDescriptor = OneofDescriptorProto.newBuilder().setName("choice").build();
    var parentDescriptor = DescriptorProto.newBuilder().setName("Parent").build();
    Oneof choice =
        new Oneof(
            "choice",
            TypeRef.named(PKG, "Parent").sub("Choice"),
            oneofDescriptor,
            parentDescriptor,
            List.of(),
            /* jsonOneof= */ null);
    var parent =
        new Message(
            "Parent",
            TypeRef.named(PKG, "Parent"),
            List.of(),
            List.of(),
            List.of(),
            List.of(choice),
            false,
            null,
            List.of(),
            null);
    TypeCollector ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSource(parent, PKG);
    var file = new JavaFile(PKG, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    // M4-C2b-2b-12: @Nullable Choice choice component + nested sealed interface.
    // @Nullable is rendered on its own line above the component type (uniform annotation list).
    assertTrue(
        src.contains("@Nullable") && src.contains("Choice choice"),
        "must have @Nullable Choice component:\n" + src);
    assertTrue(src.contains("interface Choice"), "must have nested sealed interface:\n" + src);
  }

  /** M4-C2b-2b-13: a message with a map field renders {@code Map<K, V>} via the source AST. */
  @Test
  void source_ast_emits_map_field() throws Exception {
    var tags =
        new Field(
            "tags",
            "tags",
            1,
            FieldLabel.REPEATED,
            FieldKind.MAP,
            "",
            TypeRefs.MAP.withGenerics(TypeRef.STRING, TypeRef.STRING),
            "string",
            "string",
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var m =
        new Message(
            "HasMap",
            TypeRef.named(PKG, "HasMap"),
            List.of(tags),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    TypeCollector ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSource(m, PKG);
    var file = new JavaFile(PKG, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    assertTrue(
        src.contains("Map<String, String>"), "map field should be Map<String, String>:\n" + src);
    assertCompilesWithRuntime("HasMap", src);
  }

  /**
   * M4-C2d-5: a message with a nested-message field renders a codec that dispatches to the nested
   * message's codec via the field's typed model. The generated source must compile through the
   * source-AST pipeline.
   */
  @Test
  void nested_message_codec_routes_through_typed_model() throws Exception {
    // A Parent message with a nested Single message (singular, nullable).
    var single =
        new Field(
            "single",
            "single",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.MESSAGE,
            PKG + ".Single",
            TypeRef.named(PKG, "Single"),
            null,
            null,
            true,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var parent =
        new Message(
            "NestedMsg",
            TypeRef.named(PKG, "NestedMsg"),
            List.of(single),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSourceWithCodec(parent);
    var file = new JavaFile(PKG, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    // The generated source should contain the nested-message codec dispatch.
    assertTrue(
        src.contains("SingleCodec.encode"),
        "nested-message codec should call SingleCodec.encode:\n" + src);
    assertTrue(
        src.contains("SingleCodec.decode"),
        "nested-message decode should call SingleCodec.decode:\n" + src);
  }

  /**
   * M4-C2d-7: a message with a MAP field renders a record with a nested entry type + entry codec,
   * and a main codec that dispatches the map field to the entry codec.
   */
  @Test
  void map_field_emits_entry_type_and_codec_through_source_ast() throws Exception {
    var mapField =
        new Field(
            "scores",
            "scores",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.MAP,
            "google.protobuf.MapField",
            TypeRefs.MAP.withGenerics(TypeRef.INTEGER, TypeRef.STRING),
            "int32",
            "string",
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var m =
        new Message(
            "MapMsg",
            TypeRef.named(PKG, "MapMsg"),
            List.of(mapField),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSourceWithCodec(m);
    var file = new JavaFile(PKG, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    // Verify the entry type is emitted
    assertTrue(src.contains("ScoresEntry"), "entry type emitted: " + src);
    assertTrue(src.contains("ScoresEntryCodec"), "entry codec emitted: " + src);
    // Verify the MAP encode iterates the entry set
    assertTrue(
        src.contains("writeByteArray(1, ScoresEntry.ScoresEntryCodec.encode"),
        "MAP encode dispatches to entry codec: " + src);
  }

  /**
   * M4-C2b-2b-9: a message with an ENUM field renders a codec that dispatches to the enum's {@code
   * forNumber}, referencing the enum by its short name (no {@code Codec} suffix).
   */
  @Test
  void enum_field_codec_dispatches_to_forNumber() throws Exception {
    var colorField =
        new Field(
            "color",
            "color",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.ENUM,
            PKG + ".Color",
            TypeRef.named(PKG, "Color"),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var m =
        new Message(
            "EnumMsg",
            TypeRef.named(PKG, "EnumMsg"),
            List.of(colorField),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSourceWithCodec(m);
    var file = new JavaFile(PKG, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    // The codec should reference Color.forNumber for the decode path
    assertTrue(src.contains("Color.forNumber"), "enum decode should call Color.forNumber: " + src);
    // The encode should reference writeEnum for the enum field
    assertTrue(src.contains("writeEnum(1,"), "enum encode should call writeEnum: " + src);
  }

  /**
   * Regression for the singular MESSAGE/ENUM default: a {@code REQUIRED} enum-typed field is
   * presence-tracked, so the record component is {@code @Nullable} and its builder-storage default
   * must be {@code null} -- not the boxed {@code 0} that the old typeUrl-default fallback used to
   * emit (which would not compile against a reference type, since {@code 0} is not a {@code
   * Color}). A {@code REQUIRED} enum field has {@code isNullable() == false} (proto3 default ->
   * "0"}. This pins the fix: singular MESSAGE/ENUM fields default to {@code null}.
   */
  @Test
  void required_enum_field_defaults_to_null_not_zero() {
    var colorField =
        new Field(
            "color",
            "color",
            1,
            FieldLabel.REQUIRED,
            FieldKind.ENUM,
            PKG + ".Color",
            TypeRef.named(PKG, "Color"),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var m =
        new Message(
            "EnumMsg",
            TypeRef.named(PKG, "EnumMsg"),
            List.of(colorField),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var ci = new TypeCollector(PKG);
    var rec = RecordSourceMapping.toSourceWithCodec(m);
    var src = SourceRenderer.render(new JavaFile(PKG, Javadoc.builder().build(), rec, ci));

    // The record component is @Nullable Color, and the builder-storage default is null -- not 0.
    // The @Nullable annotation renders on its own line above the component type.
    assertTrue(
        src.contains("@Nullable") && src.contains("Color color = null"),
        "a REQUIRED enum field's @Nullable component defaults to null: " + src);
    assertFalse(src.contains("Color color = 0"), "the boxed-0 default must not be emitted: " + src);
  }

  /**
   * M4-C2d-8: a message with singular + repeated nested-message fields (like the {@code
   * message.proto} fixture — a {@code Sample} with {@code Item single} + {@code repeated Item
   * items}) renders through the source-AST path and compiles via {@code javac}.
   */
  @Test
  void nested_message_message_integration_test() throws Exception {
    String pkg = PKG;
    // Build the Item model (a 2-field scalar-only message).
    var id =
        new Field(
            "id",
            "id",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "int32",
            TypeRef.Primitive.INT,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var item =
        new Field(
            "name",
            "name",
            2,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "string",
            TypeRef.STRING,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var itemModel =
        new Message(
            "Item",
            TypeRef.named(pkg, "Item"),
            List.of(id, item),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var itemCi = new TypeCollector(pkg);
    var itemAst = RecordSourceMapping.toSourceWithCodec(itemModel);
    var itemFile = new JavaFile(pkg, Javadoc.builder().build(), itemAst, itemCi);
    var itemSrc = SourceRenderer.render(itemFile);

    // Build the Sample model (single Item + repeated Item).
    var single =
        new Field(
            "single",
            "single",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.MESSAGE,
            pkg + ".Item",
            TypeRef.named(pkg, "Item"),
            null,
            null,
            true,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var items =
        new Field(
            "items",
            "items",
            2,
            FieldLabel.REPEATED,
            FieldKind.MESSAGE,
            pkg + ".Item",
            TypeRef.named(pkg, "Item"),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    var sampleModel =
        new Message(
            "Sample",
            TypeRef.named(pkg, "Sample"),
            List.of(single, items),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var sampleCi = new TypeCollector(pkg);
    var sampleAst = RecordSourceMapping.toSourceWithCodec(sampleModel);
    var sampleFile = new JavaFile(pkg, Javadoc.builder().build(), sampleAst, sampleCi);
    var sampleSrc = SourceRenderer.render(sampleFile);

    // Verify both sources contain the expected codec dispatch.
    assertTrue(
        sampleSrc.contains("ItemCodec.encode"),
        "Sample should encode via ItemCodec.encode: " + sampleSrc);
    assertTrue(
        sampleSrc.contains("ItemCodec.decode"),
        "Sample should decode via ItemCodec.decode: " + sampleSrc);
    assertTrue(itemSrc.contains("encode"), "Item should have encode method: " + itemSrc);
  }

  /**
   * M4-C2d-8b: a comprehensive integration test that covers ALL field families in a single message,
   * mirroring the {@code compat.proto} fixture.
   *
   * <p>The record is rendered through the source-AST path, compiled via {@code javac}, and verified
   * to have the expected structure.
   */
  @Test
  void all_families_compat_message_renders_source_ast() throws Exception {
    String pkg = PKG;
    // int32 i = 1;
    var iF =
        new Field(
            "i",
            "i",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "int32",
            TypeRef.Primitive.INT,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // fixed64 fx = 2;
    var fx =
        new Field(
            "fx",
            "fx",
            2,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "fixed64",
            TypeRef.Primitive.LONG,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // string s = 3;
    var sF =
        new Field(
            "s",
            "s",
            3,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "string",
            TypeRef.STRING,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // bytes b = 4;
    var bF =
        new Field(
            "b",
            "b",
            4,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "bytes",
            TypeRefs.BYTES,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // repeated int32 packed = 5;
    var packed =
        new Field(
            "packed",
            "packed",
            5,
            FieldLabel.REPEATED,
            FieldKind.SCALAR,
            "int32",
            TypeRefs.LIST.withGenerics(TypeRef.INTEGER),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // Sub nested = 6;
    var nested =
        new Field(
            "nested",
            "nested",
            6,
            FieldLabel.OPTIONAL,
            FieldKind.MESSAGE,
            pkg + ".Sub",
            TypeRef.named(pkg, "Sub"),
            null,
            null,
            true,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // repeated Sub subs = 7;
    var subs =
        new Field(
            "subs",
            "subs",
            7,
            FieldLabel.REPEATED,
            FieldKind.MESSAGE,
            pkg + ".Sub",
            TypeRefs.LIST.withGenerics(TypeRef.named(pkg, "Sub")),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // map<string,int32> m = 8;
    var mapF =
        new Field(
            "m",
            "m",
            8,
            FieldLabel.REPEATED,
            FieldKind.MAP,
            "",
            TypeRefs.MAP.withGenerics(TypeRef.STRING, TypeRef.INTEGER),
            "string",
            "int32",
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    // optional int32 presence = 9;
    var presence =
        new Field(
            "presence",
            "presence",
            9,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "int32",
            TypeRef.INTEGER,
            null,
            null,
            true,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);

    var fields = List.of(iF, fx, sF, bF, packed, nested, subs, mapF, presence);
    var m =
        new Message(
            "Compat",
            TypeRef.named(pkg, "Compat"),
            fields,
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    var ci = new TypeCollector(pkg);
    var rec = RecordSourceMapping.toSourceWithCodec(m);
    var file = new JavaFile(pkg, Javadoc.builder().build(), rec, ci);
    var src = SourceRenderer.render(file);
    // Verify key dispatches are present.
    assertTrue(src.contains("record Compat"), "record header: " + src);
    assertTrue(src.contains("class Builder"), "builder: " + src);
    assertTrue(src.contains("class CompatCodec"), "codec: " + src);
  }
}
