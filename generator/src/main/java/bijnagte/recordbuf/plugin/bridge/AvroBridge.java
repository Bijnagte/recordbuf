package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.AvroConverterSourceMapping;
import bijnagte.recordbuf.plugin.writer.AvroRegistry;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.compiler.specific.SpecificCompiler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    // The first top-level message is the root, and so is every later one that no earlier root
    // reaches: a message already in an earlier root's graph is emitted with it, not again.
    List<CodeGeneratorResponse.File> all = new ArrayList<>();
    Set<String> seenFqns = new HashSet<>();
    Set<String> seenPaths = new HashSet<>();
    for (Message root : file.messages()) {
      List<CodeGeneratorResponse.File> rootFiles = new ArrayList<>();
      Set<String> rootFqns = new HashSet<>();
      emitRoot(file, root, table, formatter, rootFiles, rootFqns, seenFqns);
      seenFqns.addAll(rootFqns);
      for (CodeGeneratorResponse.File f : rootFiles) {
        // The shared records (unknown-field entry, oneof value) come out of every root's document.
        if (seenPaths.add(f.getName())) {
          all.add(f);
        }
      }
    }
    return all;
  }

  /**
   * Emit the Avro surface of one root message: its reachable graph's schemas, specific records and
   * converters. A root an earlier one already reached emits nothing.
   *
   * @param reached receives the fqns of every message in this root's graph
   * @param alreadyEmitted the fqns earlier roots covered
   */
  private void emitRoot(
      ProtoFile file,
      Message root,
      FileTable table,
      Formatter formatter,
      List<CodeGeneratorResponse.File> out,
      Set<String> reached,
      Set<String> alreadyEmitted) {

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
      return;
    }
    if (schema.isEmpty()) return;

    reached.addAll(registry.protoFqns());
    if (alreadyEmitted.contains(registry.fqnOf(root))) return;

    // ---- the .avsc document for every reachable message ----
    // Each bridged record's structural twin is its message's complete .avsc (the whole reachable
    // graph in one document, KTD3) at the specific record's home package; the root and every
    // referenced message get one (R6).
    String rootFqn = registry.fqnOf(root);
    String avroNsPath = registry.documentNamespace().replace('.', '/');
    for (String fqn : registry.protoFqns()) {
      Message m = registry.messageOf(fqn);
      if (m == null) continue;
      String name = registry.nameOf(fqn);
      if (name == null) continue;
      String avsc = AvroSchemaGenerator.pretty(AvroSchemaGenerator.document(file, m, registry));
      String dir = avroNsPath.isEmpty() ? "" : avroNsPath + "/";
      String base =
          fqn.equals(rootFqn) ? root.name() : AvroSchemaGenerator.specificRecordName(m.name());
      out.add(
          CodeGeneratorResponse.File.newBuilder()
              .setName(dir + base + "AvroSchema.avsc")
              .setContent(avsc)
              .build());
    }
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
      return;
    }
    // ---- XAvro converter: lives in the record's .rec package; specific record referenced by FQN
    // ----
    // ---- a converter per reachable message ----
    // Each bridged record gets its <Record>AvroConverter in its own .rec package, referring to the
    // sibling-specific records and (message-typed fields) their converters by FQN; the root
    // converter is the consumer entry point.
    for (String fqn : registry.protoFqns()) {
      Message m = registry.messageOf(fqn);
      if (m == null) continue;
      String name = registry.nameOf(fqn);
      String mRecordPkg = registry.recordPackageOf(fqn);
      String specPkg = registry.namespaceOf(fqn);
      if (name == null || mRecordPkg == null || specPkg == null) continue;
      String converterName = AvroSchemaGenerator.specificRecordName(m.name()) + "Converter";
      String converterSource =
          AvroConverterSourceMapping.generate(
              m.name(), file.fileName(), m, name, specPkg, mRecordPkg, registry, formatter);
      String converterPkg = mRecordPkg.isEmpty() ? converterName : mRecordPkg + "." + converterName;
      out.add(
          CodeGeneratorResponse.File.newBuilder()
              .setName(converterPkg.replace('.', '/') + ".java")
              .setContent(converterSource)
              .build());
    }
  }

  /**
   * The recordbuf record package for this file: {@link ProtoFile#javaPackage()} — which {@link
   * bijnagte.recordbuf.plugin.model.FileTable} has already resolved with the run-wide {@code
   * package_suffix} (e.g. {@code .rec}) applied. The converter lives in this same package, keeping
   * the record type resolvable as a short name.
   */
  public static String recordPkg(ProtoFile file) {
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
      Schema parsed = parseDocument(new ObjectMapper().readTree(avscJson));
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

  /**
   * Parse a {@code .avsc} document ({@code {protocol, namespace, types[], message}}) into its root
   * schema, with every type it defines registered in the parser. Names resolve once the whole
   * document is read, not per definition: a type may reference one defined later in {@code types}
   * (a mutual cycle, or a nested type that references the root message, which is parsed last). A
   * definition that is parsed alone and references a later one fails with {@code Undefined schema}.
   *
   * @param doc the parsed document
   * @return the parsed root schema
   */
  public static Schema parseDocument(JsonNode doc) {
    Schema.Parser parser = new Schema.Parser();
    for (JsonNode type : doc.get("types")) {
      parser.parseInternal(type.toString());
    }
    return parser.parse(doc.get("message").toString());
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
