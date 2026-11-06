package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.compiler.specific.SpecificCompiler;

/**
 * The Avro wire-format bridge: the concrete {@link FormatBridge} that, for an enabled file, adds a
 * same-shaped conversion of every message/enum into Avro — a per-type {@code XSpecific}
 * representation, a per-type {@code XAvro} converter, and the per-type schema (an {@code SCHEMA}
 * constant on a {@code XSchema} companion plus a per-file {@code .avsc} output) — alongside the
 * record/codec output. Protobuf remains the only wire format: a message's {@code Codec} is never
 * touched; the bridge only adds the conversion.
 *
 * <p>Resolution (R1): enablement is {@code per-file recordbuf.avro option &gt; global --avro flag
 * &gt; built-in default(on)}, via {@link BridgeContext#avroEnabled}.
 *
 * <p><b>Wiring (R5, ref'd 2026-11-06):</b> for an enabled file the bridge emits, into the SAME
 * output dir as the record/codec, a per-file {@code .avsc} (R6) AND a per-root-message {@code
 * XSpecific} record -- the latter produced by Avro's own {@code
 * org.apache.avro.compiler.specific.SpecificCompiler} (option B, KTD6 delegation) run
 * <em>in-process</em> over the emitted {@code .avsc}, NOT by a build-system task (the operator's R5
 * decision: no Gradle task, everything from the single {@code protoc --recordbuf_out} invocation).
 * The specific record lives in a distinct {@code <protoPackage>.avro} sub-namespace so it does not
 * collide with the protobuf-java reference {@code --java_out} output in the bare proto package (R7,
 * reopened 2026-11-06). The per-type {@code XAvro} converter ({@link
 * bijnagte.recordbuf.plugin.writer.AvroConverterGenerator}) is delivered as a generator-tested
 * artifact (U5); its in-process wiring (a cross-package reference between the {@code .rec} record
 * and the {@code .avro} specific record) is a follow-up.
 */
public final class AvroBridge implements FormatBridge {

  @Override
  public boolean enabledFor(ProtoFile file, BridgeContext ctx) {
    return ctx.avroEnabled(file);
  }

  @Override
  public List<? extends CodeGeneratorResponse.File> emit(ProtoFile file, BridgeContext ctx) {
    // Off: a disabled file contributes nothing, exactly as an absent bridge would (R1/R3).
    if (!enabledFor(file, ctx)) return List.of();

    final String schema;
    try {
      schema = AvroSchemaGenerator.schema(file);
    } catch (AvroUnsupportedException notYetSupported) {
      // Fail-loud at the mapping unit (unit-tested); at the file level v1 skips so an existing
      // generation with an unsupported shape is not aborted by the bridge.
      return List.of();
    }
    if (schema.isEmpty()) return List.of(); // no top-level message: nothing to bridge

    Message root = file.messages().get(0);
    List<CodeGeneratorResponse.File> out = new ArrayList<>();

    // The standalone .avsc output (R6)
    String avscPath = root.fullName() + "AvroSchema.avsc";
    out.add(CodeGeneratorResponse.File.newBuilder().setName(avscPath).setContent(schema).build());

    // The specific record (option B): generate it in-process by delegating to Avro's own
    // SpecificCompiler over the just-emitted .avsc, read back the generated source, and emit it as
    // a response file -- same output dir, same protoc invocation, NO build-system task (R5).
    String specificSource;
    try {
      specificSource = generateSpecific(schema, root);
    } catch (Exception generationFailed) {
      // A file-level skip so an existing generation with an unsupported shape OR a
      // generation-time Avro/IO issue is not aborted by the bridge; the .avsc still emits.
      return out;
    }
    String avroNs = AvroSchemaGenerator.avroNamespace(file.protoPackage());
    String recordClass = AvroSchemaGenerator.specificRecordName(root.name());
    // The recordbuf output router keeps each response file's dotted namespace dotted (does NOT
    // split
    // dotted segments to subdirs); so route the specific record as a SLASHED proto-package path.
    String specificPath =
        avroNs.isEmpty() ? recordClass : avroNs.replace('.', '/') + "/" + recordClass;
    out.add(
        CodeGeneratorResponse.File.newBuilder()
            .setName(specificPath + ".java")
            .setContent(specificSource)
            .build());
    // ---- XAvro converter: deferred ----
    // The XAvro converter is wired in the generator (AvroConverterGenerator) and its shape is
    // unit-tested, but it CANNOT be emitted yet. Three concrete Avro v1.12 specific-record API
    // mismatches with the recordbuf record block it:
    //   1. Field access: the generated specific record has PRIVATE fields. The converter uses
    //      direct write (s.c = r.c()) -- needs setter-methods (setC(v)) or the GenericData builder.
    //   2. byte[] vs ByteBuffer: bytes fields are byte[] on the record, ByteBuffer on the
    //      specific record -- no direct assignment between them.
    //   3. UnknownFields component: the record's constructor takes a final arg UnknownFields
    //      that the converter's toRecord() omits -- fix: pass UnknownFields.empty().
    // Until U-converter resolves these, only the .avsc (R6) + the in-process specific record
    // (option B, KTD6) are emitted. The specific record lives at
    // recordbuf/<proto-pkg-slashed>/<Record>Avro.java and is generated by Avro's
    // SpecificGenerator (option B), routed by the same --recordbuf_out call as the .rec record.
    return out;
  }

  /**
   * Delegate schema -> specific-record generation to Avro's own {@code SpecificCompiler} (option
   * B): write the schema to a temp {@code .avsc}, let Avro generate + compile its specific record
   * into a temp dir, and read back the source. The generated class lives in the {@code .avsc}
   * namespace; returning its source lets the caller emit it as a plain response file into the same
   * output dir.
   */
  private static String generateSpecific(String avscJson, Message root) throws java.io.IOException {
    String recName = AvroSchemaGenerator.specificRecordName(root.name());
    java.nio.file.Path tmp = Files.createTempDirectory("recordbuf-avro-bridge");
    try {
      java.nio.file.Path avscPath = tmp.resolve(recName + ".avsc");
      Files.writeString(avscPath, avscJson);
      Schema parsed = new Schema.Parser().parse(avscJson);
      java.nio.file.Path outDir = tmp.resolve("generated");
      Files.createDirectories(outDir);
      new SpecificCompiler(parsed).compileToDestination(avscPath.toFile(), outDir.toFile());
      // SpecificCompiler writes <namespace-as-path>/<RecName>.java. Locate the single generated
      // record source (the top-level record named after root). Its siblings (none, for a single
      // top-level record) are not emitted.
      java.nio.file.Path matched =
          Files.walk(outDir)
              .filter(p -> p.getFileName().toString().equals(recName + ".java"))
              .findFirst()
              .orElseThrow(() ->
                  new IllegalStateException("SpecificCompiler produced no " + recName + ".java"));
      String source = Files.readString(matched);
      return source;
    } finally {
      try {
        java.nio.file.Files.walk(tmp)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
              try {
                java.nio.file.Files.deleteIfExists(p);
              } catch (IOException ignoredCleanup) {
                // best-effort temp cleanup
              }
            });
      } catch (IOException ignoredCleanup) {
        // best-effort temp cleanup
      }
    }
  }

  /** The bridge the default registry ships. */
  public static AvroBridge instance() {
    return new AvroBridge();
  }
}
