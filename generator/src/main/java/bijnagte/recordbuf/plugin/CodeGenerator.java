package bijnagte.recordbuf.plugin;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.EnumSpec;
import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.bridge.BridgeContext;
import bijnagte.recordbuf.plugin.bridge.BridgeRegistry;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.IdentityCases;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ModelLoader;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.EnumSourceMapping;
import bijnagte.recordbuf.plugin.writer.RecordSourceMapping;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.Feature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives code generation for a single {@link CodeGeneratorRequest}.
 *
 * <p>For each file in the request's {@code file_to_generate} list we produce one Java source file
 * (the record for that file's top-level types). Dependencies (the other {@code proto_file} entries)
 * are not generated, but are used to build the type-resolution table.
 */
public final class CodeGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(CodeGenerator.class);

  // Assigned at the start of generate() from the request's plugin parameter so
  // per-call generation (Main invokes one CodeGenerator per request) stays
  // stateless. Built fresh each call with the parsed package_suffix.
  private FileTable table = new FileTable();
  // The identity-subtype decisions for this request, indexed over every file being generated and
  // applied to each file's model as it loads. Rebuilt per generate() call (stateless per request).
  private IdentityCases identityCases = IdentityCases.none(table);

  // Per-request de-dup for the generated @NullMarked package-info.java: several .proto
  // files can map to ONE java package, so that package-level marker is emitted once per
  // package, not once per producer file (unlike the per-file record/enum/codec files).
  // Built fresh in generate() like the table + oneof registry, so per-call generation
  // (Main invokes one CodeGenerator per request) stays stateless.
  private Set<String> emittedPackageInfos = new LinkedHashSet<>();

  // Per-invocation bridge context (the parsed --avro flag, etc.) + the registered bridge(s) that
  // add a non-protobuf wire-format conversion alongside every enabled file's record/codec output.
  // Built fresh in generate() like the other per-request state, so per-call generation (Main
  // invokes one CodeGenerator per request) stays stateless.
  private BridgeContext bridgeContext = BridgeContext.empty();
  private BridgeRegistry bridgeRegistry = BridgeRegistry.defaultRegistry();

  // The formatter the plugin threads every rendered surface (the record/enum files and the
  // per-package package-info) through, past the render pass. A generation-time concern -- a
  // GJF-backed Formatter (R2) -- so the emitted .rec output is GJF-clean by construction (R4,
  // the "spotless parity" bar format(format(x)) == format(x), incl. package-info). The SPI
  // (bijnagte.recordbuf.codegen.source.Formatter) is pluggable (R3); today the plugin chooses
  // the GJF-backed GoogleJavaFormatter. GoogleJavaFormatter is stateless, so a single final
  // field is as correct as a per-request rebuild (one CodeGenerator per request).
  private final Formatter formatter = GoogleJavaFormatter.getInstance();

  /** Run generation; returns a response ready to serialize to the client. */
  public CodeGeneratorResponse generate(CodeGeneratorRequest request) {
    // Pass 1: load ALL files (including dependencies) into the type table.
    // Without this, we wouldn't know the Java FQNs for cross-file types.
    // The table carries the run-wide package_suffix so every file's java package
    // (and every cross-file resolution through it) is suffixed uniformly.
    table = new FileTable(parsePackageSuffix(request.getParameter()));
    table.addAll(request.getProtoFileList());
    // Fresh per request so a package-info is de-duplicated WITHIN this request only.
    this.emittedPackageInfos = new LinkedHashSet<>();
    // The bridge context + registry are per-invocation too: the --avro flag is parsed from the
    // same plugin parameter the run-wide package_suffix is, and a fresh registry keeps generation
    // stateless across the one CodeGenerator Main gives each request.
    BridgeContext ctx = BridgeContext.parse(request.getParameter());
    this.bridgeContext = ctx;
    this.bridgeRegistry = BridgeRegistry.defaultRegistry();

    // Index the identity-subtype cases over every file in this run before any file is written: a
    // case type must implement each oneof it is an identity member of, which can live in another
    // file. The first load is only for the index; each file is loaded again with the decisions.
    Map<String, ProtoFile> plainModels = new LinkedHashMap<>();
    for (String fileName : request.getFileToGenerateList()) {
      FileDescriptorProto fdp = findByPath(fileName);
      if (fdp == null) throw new IllegalStateException("file not found: " + fileName);
      plainModels.put(fileName, new ModelLoader(table, table.javaPackage(fdp)).load(fdp));
    }
    this.identityCases = IdentityCases.of(table, plainModels);

    List<CodeGeneratorResponse.File> outFiles = new ArrayList<>();
    for (String fileName : request.getFileToGenerateList()) {
      outFiles.addAll(generateForFile(fileName));
    }

    CodeGeneratorResponse.Builder resp = CodeGeneratorResponse.newBuilder();
    resp.setSupportedFeatures(Feature.FEATURE_PROTO3_OPTIONAL_VALUE);
    // A file's multiple bridged messages can share a package and thus compile to the same
    // class names (each message's own specific class AND the shared unknown-field entry record),
    // so the same output file can be emitted more than once; protoc rejects a response with
    // duplicate file names. The duplicates are structurally identical (same name + package), so
    // keeping the first is lossless.
    var seenFileNames = new LinkedHashSet<String>();
    for (CodeGeneratorResponse.File f : outFiles) {
      if (seenFileNames.add(f.getName())) {
        resp.addFile(f);
      }
    }
    return resp.build();
  }

  private List<CodeGeneratorResponse.File> generateForFile(String fileName) {
    FileDescriptorProto fdp = findByPath(fileName);
    if (fdp == null) throw new IllegalStateException("file not found: " + fileName);

    String javaPkg = table.javaPackage(fdp);
    ProtoFile pf = new ModelLoader(table, javaPkg, identityCases).load(fdp);
    String outDir = javaPkg.replace('.', '/') + "/";
    LOG.debug(
        "generating {} -> package {} ({} message(s), {} enum(s))",
        fdp.getName(),
        javaPkg,
        pf.messages().size(),
        pf.enums().size());
    List<CodeGeneratorResponse.File> files = new ArrayList<>();

    // The @NullMarked package-info is a PER-PACKAGE marker, not a per-file one: emit it once
    // per generated java package even when several .proto files share the package, and precede
    // it in the response so it sits ahead of the package's own type files. Only a file that
    // actually generates a type (a top-level record or enum) claims a package; a type-less file
    // -- e.g. the options proto, which declares only extensions -- generates no record/enum files
    // and so produces no marker, leaving an empty @NullMarked package out of the user's output.
    // A bare root package likewise has no declaration to mark, so it yields no package-info.
    boolean generatesType = !pf.messages().isEmpty() || !pf.enums().isEmpty();
    if (generatesType && !javaPkg.isEmpty() && emittedPackageInfos.add(javaPkg)) {
      files.add(emitPackageInfo(javaPkg));
    }

    // Each top-level enum gets its OWN file so the public type name matches
    // its filename (Java requires one public type per source file).
    for (var e : pf.enums()) {
      files.add(
          generateEnum(fdp, javaPkg, outDir + JavaNames.cap(e.name()) + ".java", e, pf.jackson()));
    }
    // Each top-level message gets its OWN file (record + nested types + codec).
    // All message families are handled by the source-AST pipeline.
    for (var m : pf.messages()) {
      files.add(
          generateRecord(
              fdp, javaPkg, outDir + JavaNames.cap(m.name()) + ".java", m, pf.jackson()));
    }
    // The extension slot: every registered FormatBridge runs over this file and emits its
    // conditional, per-bridge output (e.g. the Avro bridge's XSpecific/XAvro/XSchema + .avsc) only
    // when the bridge is enabled for this file (R1: file > flag > default-on). A disabled bridge
    // contributes nothing, exactly as an absent one would.
    files.addAll(bridgeRegistry.emitAll(pf, bridgeContext, table, formatter));
    return files;
  }

  /**
   * Generate one Java source file for a single top-level type.
   *
   * <p>Generation runs in <b>two passes over a shared {@link
   * bijnagte.recordbuf.codegen.TypeCollector}</b> so a file's referenced types resolve to short
   * names with a deduplicated, ordered import block — not fully-qualified, repeated names:
   *
   * <ol>
   *   <li>a <i>collect</i> pass runs the body on a throwaway writer, filling the collector with
   *       every type the file references (a collision is a property of the whole set, so it can
   *       only be known after the file is walked in full);
   *   <li>{@link bijnagte.recordbuf.codegen.TypeCollector#finalizeResolution()} then fixes each
   *       name to its short form (imported) or fully-qualified form (when its short name collides),
   *   <li>a <i>render</i> pass writes the header, package element, import block, and body into the
   *       real writer with those fixed names.
   * </ol>
   *
   * /** Generate a single-enum source file through the {@code bijnagte.recordbuf.codegen.source}
   * AST: map the {@link Enum} model through {@link EnumSourceMapping} to a {@link EnumSpec}, wrap
   * it in a {@link JavaFile} with the standard generated-by Javadoc header, and render via {@link
   * SourceRenderer#render}.
   */
  private CodeGeneratorResponse.File generateEnum(
      FileDescriptorProto fdp, String javaPkg, String outName, Enum e, boolean jackson) {
    TypeCollector ci = new TypeCollector(javaPkg);
    Javadoc header =
        Javadoc.builder()
            .text("Generated by recordbuf -- DO NOT EDIT.\nProto: ", fdp.getName())
            .build();
    EnumSpec ast = EnumSourceMapping.toSource(e, javaPkg, jackson);
    JavaFile javaFile = new JavaFile(javaPkg, header, ast, ci);
    String source = SourceRenderer.render(javaFile, formatter);
    return CodeGeneratorResponse.File.newBuilder().setName(outName).setContent(source).build();
  }

  /**
   * Generate a single-message source file through the source AST: map the {@link Message} model
   * through {@link RecordSourceMapping#toSourceWithCodec} to a {@link RecordSpec} with a nested
   * {@code Codec}, wrap it in a {@link JavaFile} with the standard generated-by Javadoc header, and
   * render via {@link SourceRenderer#render}. The source-AST pipeline handles all field families
   * (scalars, repeated, optional, map, nested-message, singular/repeated-enum, oneof, and WKT,
   * including recordbuf.protobuf_java interop).
   */
  private CodeGeneratorResponse.File generateRecord(
      FileDescriptorProto fdp, String javaPkg, String outName, Message m, boolean jackson) {
    Javadoc header =
        Javadoc.builder()
            .text("Generated by recordbuf -- DO NOT EDIT.\nProto: ", fdp.getName())
            .build();
    RecordSpec ast = RecordSourceMapping.toSourceWithCodec(m, jackson);
    // The collision-seed travels via the JavaFile side channel: when this message opts into
    // recordbuf.protobuf_java interop, register the record's own FQN so its short name occupies the
    // declaring package and the out-of-package protobuf-java reference type (same short name) is
    // emitted fully-qualified by the TypeCollector's collision policy. Empty otherwise.
    Set<TypeRef> externalRefs = m.protobufJava() != null ? Set.of(m.type()) : Set.of();
    JavaFile javaFile =
        new JavaFile(javaPkg, header, ast, new TypeCollector(javaPkg), externalRefs);
    String source = SourceRenderer.render(javaFile, formatter);
    return CodeGeneratorResponse.File.newBuilder().setName(outName).setContent(source).build();
  }

  /**
   * Emit the per-package {@code @NullMarked} marker: a {@code package-info.java} declaring that
   * every type in a recordbuf-generated java package is non-null by default, with {@code @Nullable}
   * the single escape. It mirrors {@code bijnagte.recordbuf.runtime}'s own {@code
   * package-info.java} and is the mark that lets a consumer running NullAway with {@code
   * OnlyNullMarked=true} (the project's convention) scope the nullness analysis to exactly the
   * generated records, leaving the un-marked protobuf-java reference output ({@code Sample}/{@code
   * SampleOrBuilder}/…) out of scope.
   *
   * <p>Emitted in the plugin's clean-output style: a {@code @NullMarked} annotation before the
   * {@code package} element (JSpecify's module convention, so the usual package/import/body order a
   * type file uses does not apply to a package-info), then the import, under the generated-file
   * header. Built directly (not through a writer body-emission path) because the annotation
   * precedes the package element there.
   */
  private CodeGeneratorResponse.File emitPackageInfo(String javaPkg) {
    String name = javaPkg.replace('.', '/') + "/package-info.java";
    // The package-info is built as a raw string (not a SourceRenderer render), so it is fed
    // through the same formatter the record/enum files get before entering the response, so all
    // rendered surfaces share one GJF-canonical form (R2). GJF leaves this already-canonical
    // string unchanged (verified: it is GJF-idempotent), so this is a no-op today -- but it keeps
    // the package-info on the same formatting path as the rest of the .rec surface.
    String content =
        formatter.format(
            """
            /*
             * Generated by recordbuf -- DO NOT EDIT.
             */
            @NullMarked
            package %s;

            import org.jspecify.annotations.NullMarked;
            """
                .formatted(javaPkg));
    return CodeGeneratorResponse.File.newBuilder().setName(name).setContent(content).build();
  }

  /**
   * Resolve the run-wide {@code package_suffix} parameter to a normalized suffix (leading/trailing
   * dots and whitespace stripped; absent/blank is an empty no-op). The parameter is the protoc
   * plugin param string (e.g. {@code package_suffix=.rec}) — comma-separated {@code key=value}
   * pairs — of which only {@code package_suffix} is honored. An absent value leaves every file's
   * java package byte-for-byte unchanged (R5).
   */
  @SuppressWarnings("StringSplitter")
  // The comma-split drops trailing empty segments (its "surprising" behavior), which is harmless
  // here: an empty key simply fails the `!key.equals("package_suffix")` guard and is skipped.
  private static String parsePackageSuffix(String parameter) {
    if (parameter == null || parameter.isBlank()) return "";
    String suffix = "";
    for (String pair : parameter.split(",")) {
      int eq = pair.indexOf('=');
      String key = (eq < 0 ? pair : pair.substring(0, eq)).trim();
      if (!key.equals("package_suffix")) continue;
      String value = (eq < 0 ? "" : pair.substring(eq + 1)).trim();
      // Normalize so ".rec" and "rec" are equivalent: strip surrounding dots.
      int s = 0, e = value.length();
      while (s < e && value.charAt(s) == '.') s++;
      while (e > s && value.charAt(e - 1) == '.') e--;
      suffix = value.substring(s, e);
    }
    return suffix;
  }

  private @Nullable FileDescriptorProto findByPath(String path) {
    return table.byName(path);
  }
}
