package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.AvroConverterSourceMapping;
import bijnagte.recordbuf.plugin.writer.AvroRegistry;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>Resolution (R1): enablement is {@code per-file recordbuf.avro option > global --avro flag >
 * built-in default(on)}, via {@link BridgeContext#avroEnabled}.
 *
 * <p><b>Wiring (R5, ref'd 2026-11-06; structural twin, 2026-11-17):</b> for an enabled file the
 * bridge emits, into the SAME output dir as the record/codec, a per-file {@code .avsc} (R6) — the
 * <em>whole reachable type graph</em> (the root record plus every cross-file reference it reaches,
 * resolved through the request's {@link FileTable}) as one Avro package, with the trailing
 * unknown-field carrier — and the Avro-specific generated for it (produced by Avro's own {@code
 * org.apache.avro.compiler.specific.SpecificCompiler} (option B, KTD6 delegation) run
 * <em>in-process</em> over the emitted {@code .avsc}, NOT by a build-system task (the operator's R5
 * decision: no Gradle task, everything from the single {@code protoc --recordbuf_out} invocation)).
 * The specific record lives in a distinct {@code <protoPackage>.avro} sub-namespace so it does not
 * collide with the protobuf-java reference {@code --java_out} output in the bare proto package (R7,
 * reopened 2026-11-06). The per-type {@code XAvroConverter} (the record → specific record
 * conversion) is delivered alongside; that converter generation is the next unit of this family.
 */
public final class AvroBridge implements FormatBridge {

  @Override
  public boolean enabledFor(ProtoFile file, BridgeContext ctx) {
    return ctx.avroEnabled(file);
  }

  @Override
  public List<? extends CodeGeneratorResponse.File> emit(
      ProtoFile file, BridgeContext ctx, FileTable table, Formatter formatter) {
    // Off: a disabled file contributes nothing, exactly as an absent bridge would (R1/R3).
    if (!enabledFor(file, ctx)) return List.of();
    if (file.messages().isEmpty()) return List.of(); // no top-level message: nothing to bridge
    Message root = file.messages().get(0);

    // The structural twin: the reachable type graph (the registry) drives the whole .avsc
    // document; a mapping failure aborts the bridge for this file (R3 fail-loud: the mapping
    // layer turns this into a per-field diagnostic rather than a half-generated document).
    final String schema;
    final AvroRegistry registry;
    try {
      registry = AvroRegistry.build(file, root, table);
      schema = AvroSchemaGenerator.pretty(AvroSchemaGenerator.document(file, root, registry));
    } catch (AvroUnsupportedException notYetSupported) {
      // Fail-loud at the mapping unit (unit-tested); at the file level v1 skips so an existing
      // generation with an unsupported shape is not aborted by the bridge.
      return List.of();
    }
    if (schema.isEmpty()) return List.of();

    List<CodeGeneratorResponse.File> out = new ArrayList<>();

    // The standalone .avsc output (R6)
    String avscPath = root.type().fqn() + "AvroSchema.avsc";
    out.add(CodeGeneratorResponse.File.newBuilder().setName(avscPath).setContent(schema).build());

    // The specific record (option B): generate it in-process by delegating to Avro's own
    // SpecificCompiler over the just-emitted .avsc, read back the generated source, and emit it as
    // a response file -- same output dir, same protoc invocation, NO build-system task (R5).
    try {
      // KTD2: the document's every named record compiles to a specific class (the root record,
      // every reachable record, and the shared unknown-field entry record). Each is emitted as a
      // response file at its declared package; a generation failure skips the specific records
      // (the .avsc still emits) so an Avro/IO issue never aborts the rest of the generation.
      for (SpecificFile specific : generateSpecifics(schema)) {
        // The recordbuf output router keeps a response file's dotted name dotted (it does NOT
        // split dotted segments into subdirs), so route as a SLASHED package path.
        String path =
            specific.packageName().isEmpty()
                ? specific.className()
                : specific.packageName().replace('.', '/') + "/" + specific.className();
        out.add(
            CodeGeneratorResponse.File.newBuilder()
                .setName(path + ".java")
                .setContent(specific.source())
                .build());
      }
    } catch (Exception generationFailed) {
      return out;
    }
    String avroNs = registry.documentNamespace();
    // ---- XAvro converter: lives in the record's .rec package; specific record referenced by FQN
    // ----
    String recordPkg = recordPkg(file);
    String converterSource =
        AvroConverterSourceMapping.generate(
            root.name(), file.fileName(), root, avroNs, recordPkg, formatter);
    String converterPkg =
        recordPkg.isEmpty()
            ? root.name() + "AvroConverter"
            : recordPkg + "." + root.name() + "AvroConverter";
    out.add(
        CodeGeneratorResponse.File.newBuilder()
            .setName(converterPkg.replace('.', '/') + ".java")
            .setContent(converterSource)
            .build());
    return out;
  }

  /**
   * The recordbuf record package for this file: {@link ProtoFile#javaPackage()} — which {@link
   * bijnagte.recordbuf.plugin.model.FileTable} has already resolved with the run-wide {@code
   * package_suffix} (e.g. {@code .rec}) applied. The converter lives in this same package, keeping
   * the record type resolvable as a short name.
   */
  private static String recordPkg(ProtoFile file) {
    return file.hasJavaPackage() ? file.javaPackage() : file.protoPackage();
  }

  /**
   * One specific-record class generated by {@code SpecificCompiler} (its declared package +
   * source).
   */
  record SpecificFile(String packageName, String className, String source) {}

  /**
   * Delegate schema -> specific-class generation to Avro's own {@code SpecificCompiler} (option B):
   * write the document to a temp {@code .avsc}, let Avro generate + compile every named record in
   * it into a temp dir, and read back each source. The classes live in their records' declared
   * namespaces; the caller emits each as a plain response file into the same output dir.
   */
  private static List<SpecificFile> generateSpecifics(String avscJson) throws IOException {
    Path tmp = Files.createTempDirectory("recordbuf-avro-bridge");
    try {
      Path avscPath = tmp.resolve("document.avsc");
      Files.writeString(avscPath, avscJson);
      // The document is {protocol, namespace, types[], message}: the Avro Schema.Parser parses
      // one schema node, so feed it the types definitions first (its name cache resolves the
      // cross-references) and then the root record.
      JsonNode doc = new ObjectMapper().readTree(avscJson);
      Schema.Parser parser = new Schema.Parser();
      for (JsonNode type : doc.get("types")) {
        parser.parse(type.toString());
      }
      Schema parsed = parser.parse(doc.get("message").toString());
      Path outDir = tmp.resolve("generated");
      Files.createDirectories(outDir);
      new SpecificCompiler(parsed).compileToDestination(avscPath.toFile(), outDir.toFile());
      // SpecificCompiler writes every named record in the document as a top-level class
      // (<namespace-as-path>/<Name>.java) -- the root, every reachable record, and the shared
      // unknown-field entry record. Collect ALL of them: the records reference each other (a
      // record's unknown-fields trailer references the entry record), so emitting only the root
      // would leave its references dangling.
      List<SpecificFile> files = new ArrayList<>();
      try (var walk = Files.walk(outDir)) {
        for (Path p :
            walk.filter(q -> q.getFileName().toString().endsWith(".java")).sorted().toList()) {
          String source = Files.readString(p);
          files.add(
              new SpecificFile(
                  declaredPackage(source),
                  p.getFileName().toString().replace(".java", ""),
                  source));
        }
      }
      if (files.isEmpty()) {
        throw new IllegalStateException("SpecificCompiler produced no classes for " + avscPath);
      }
      return files;
    } finally {
      try (var walk = Files.walk(tmp)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (IOException ignoredCleanup) {
                // best-effort temp cleanup
              }
            });
      } catch (IOException ignoredWalk) {
        // best-effort temp cleanup
      }
    }
  }

  /** The package a generated specific class declares (its first {@code package <ns>;} line). */
  private static String declaredPackage(String source) {
    // The generated source may open with a license-header comment; scan lines until the package
    // clause, stopping at the first declaration if a package clause never appears (default
    // package).
    int cursor = 0;
    while (cursor < source.length()) {
      int eol = source.indexOf('\n', cursor);
      eol = eol < 0 ? source.length() : eol;
      String t = source.substring(cursor, eol).trim();
      if (t.startsWith("package ")) {
        int semi = t.indexOf(';');
        return semi > 0 ? t.substring("package ".length(), semi).trim() : "";
      }
      if (t.startsWith("public ")) {
        break;
      }
      cursor = eol + 1;
    }
    return "";
  }

  /** The bridge the default registry ships. */
  public static AvroBridge instance() {
    return new AvroBridge();
  }
}
