package bijnagte.recordbuf.plugin;

import bijnagte.recordbuf.plugin.bridge.BridgeContext;
import bijnagte.recordbuf.plugin.bridge.BridgeRegistry;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ModelLoader;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.types.TypeResolver;
import bijnagte.recordbuf.plugin.writer.CodecGenerator;
import bijnagte.recordbuf.plugin.writer.EnumGenerator;
import bijnagte.recordbuf.plugin.writer.InteropGenerator;
import bijnagte.recordbuf.plugin.writer.JavaWriter;
import bijnagte.recordbuf.plugin.writer.OneofGenerator;
import bijnagte.recordbuf.plugin.writer.RecordGenerator;
import bijnagte.recordbuf.plugin.writer.TypeImports;
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
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Drives code generation for a single {@link CodeGeneratorRequest}.
 *
 * <p>For each file in the request's {@code file_to_generate} list we produce one Java source file
 * (the record for that file's top-level types). Dependencies (the other {@code proto_file} entries)
 * are not generated, but are used to build the type-resolution table.
 */
public final class CodeGenerator {

  // The recordbuf runtime package, referenced by generated records for the
  // unknown-field carrier and the ProtobufMessage marker.
  private static final String RT = "bijnagte.recordbuf.runtime";

  // Assigned at the start of generate() from the request's plugin parameter so
  // per-call generation (Main invokes one CodeGenerator per request) stays
  // stateless. Built fresh each call with the parsed package_suffix.
  private FileTable table = new FileTable();

  /**
   * The oneof member-to-interface registry for this invocation. A oneof's MESSAGE members are
   * identity subtypes of the enclosing record's nested oneof interface (K6), so each such member's
   * record must {@code implements} that interface — and that is resolved per-invocation, not by the
   * model. The map is keyed by the member type's Java fully-qualified name and maps it to every
   * interface (by FQN) it must implement. It is built over the model of every protobuf file in the
   * request (not just {@code file_to_generate}) so a message may live in a different file than the
   * oneof that references it. Scalar/enum members never appear as keys: they fold into the
   * enclosing interface's shared {@code Scalar} wrapper rather than being identity subtypes. An
   * absent key means "not a oneof member" and the record implements only the marker.
   */
  private Map<String, Set<String>> oneofRegistry = new LinkedHashMap<>();

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

  /** Run generation; returns a response ready to serialize to the client. */
  public CodeGeneratorResponse generate(CodeGeneratorRequest request) {
    // Pass 1: load ALL files (including dependencies) into the type table.
    // Without this, we wouldn't know the Java FQNs for cross-file types.
    // The table carries the run-wide package_suffix so every file's java package
    // (and every cross-file resolution through it) is suffixed uniformly.
    table = new FileTable(parsePackageSuffix(request.getParameter()));
    table.addAll(request.getProtoFileList());
    // A second pass over every requested file builds the oneof member-to-
    // interface registry so a record can implement the nested interface a
    // message member of another file's oneof belongs to (K6, cross-file).
    this.oneofRegistry = buildOneofRegistry(request.getProtoFileList());
    // Fresh per request so a package-info is de-duplicated WITHIN this request only.
    this.emittedPackageInfos = new LinkedHashSet<>();
    // The bridge context + registry are per-invocation too: the --avro flag is parsed from the
    // same plugin parameter the run-wide package_suffix is, and a fresh registry keeps generation
    // stateless across the one CodeGenerator Main gives each request.
    BridgeContext ctx = BridgeContext.parse(request.getParameter());
    this.bridgeContext = ctx;
    this.bridgeRegistry = BridgeRegistry.defaultRegistry();

    List<CodeGeneratorResponse.File> outFiles = new ArrayList<>();
    for (String fileName : request.getFileToGenerateList()) {
      outFiles.addAll(generateForFile(fileName));
    }

    CodeGeneratorResponse.Builder resp = CodeGeneratorResponse.newBuilder();
    resp.setSupportedFeatures(Feature.FEATURE_PROTO3_OPTIONAL_VALUE);
    for (CodeGeneratorResponse.File f : outFiles) resp.addFile(f);
    return resp.build();
  }

  private List<CodeGeneratorResponse.File> generateForFile(String fileName) {
    FileDescriptorProto fdp = findByPath(fileName);
    if (fdp == null) throw new IllegalStateException("file not found: " + fileName);

    String javaPkg = table.javaPackage(fdp);
    ProtoFile pf = new ModelLoader(table, javaPkg).load(fdp);
    String outDir = javaPkg.replace('.', '/') + "/";
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
          generateFile(
              fdp,
              javaPkg,
              outDir + JavaNames.cap(e.name()) + ".java",
              w -> new EnumGenerator().write(w, e, w.imports())));
    }
    // Each top-level message gets its OWN file (record + nested types + codec).
    for (var m : pf.messages()) {
      files.add(
          generateFile(
              fdp,
              javaPkg,
              outDir + JavaNames.cap(m.name()) + ".java",
              w -> emitMessage(w, m, javaPkg, fdp.getName(), oneofRegistry)));
    }
    // The extension slot: every registered FormatBridge runs over this file and emits its
    // conditional, per-bridge output (e.g. the Avro bridge's XSpecific/XAvro/XSchema + .avsc) only
    // when the bridge is enabled for this file (R1: file > flag > default-on). A disabled bridge
    // contributes nothing, exactly as an absent one would.
    files.addAll(bridgeRegistry.emitAll(pf, bridgeContext));
    return files;
  }

  /**
   * Build the run-wide oneof member-to-interface registry by loading the model of EVERY requested
   * protobuf file (not just {@code file_to_generate}) and recording, for each oneof
   * <em>message</em> member, that its record must implement the enclosing record's nested oneof
   * interface. Scalar/enum members are excluded (they fold into the interface's shared {@code
   * Scalar} wrapper). A member type whose Java FQN is unresolved (e.g. an external type)
   * contributes no entry, so its record implements no oneof interface.
   */
  private Map<String, Set<String>> buildOneofRegistry(List<FileDescriptorProto> files) {
    var registry = new LinkedHashMap<String, Set<String>>();
    for (FileDescriptorProto fdp : files) {
      String javaPkg = table.javaPackage(fdp);
      ProtoFile pf = new ModelLoader(table, javaPkg).load(fdp);
      for (Message m : pf.messages()) collectOneofInterfaces(m, registry);
    }
    return registry;
  }

  /**
   * Record, for a top-level (or nested) message's oneof message members, that each member's record
   * must implement the enclosing record's nested oneof interface. Walks nested messages so a oneof
   * declared on a nested message registers too. A member whose Java FQN is unresolved contributes
   * nothing.
   */
  private void collectOneofInterfaces(Message m, Map<String, Set<String>> registry) {
    String ifaceBase = m.fullName();
    for (Oneof oneof : m.oneofs()) {
      String ifaceFqn = ifaceBase + "." + oneof.ifaceName();
      for (Field member : oneof.members()) {
        if (!member.isMessage()) continue; // scalar/enum fold into the Scalar wrapper
        // The member's proto FQN resolves through the same file table the
        // generator's TypeResolver uses, so the key lines up with the
        // member record's own fullName() at its emission site.
        String memberFqn = table.javaFqn(member.typeUrl());
        if (memberFqn == null) continue; // unresolved external type: no interface to implement
        registry.computeIfAbsent(memberFqn, k -> new LinkedHashSet<>()).add(ifaceFqn);
      }
    }
    for (Message nested : m.nestedMessages()) collectOneofInterfaces(nested, registry);
  }

  /**
   * Generate one Java source file for a single top-level type.
   *
   * <p>Generation runs in <b>two passes over a shared {@link TypeImports} collector</b> so a file's
   * referenced types resolve to short names with a deduplicated, ordered import block — not
   * fully-qualified, repeated names:
   *
   * <ol>
   *   <li>a <i>collect</i> pass runs the body on a throwaway writer, filling the collector with
   *       every type the file references (a collision is a property of the whole set, so it can
   *       only be known after the file is walked in full);
   *   <li>{@link TypeImports#finalizeResolution()} then fixes each name to its short form
   *       (imported) or fully-qualified form (when its short name collides),
   *   <li>a <i>render</i> pass writes the header, package statement, import block, and body into
   *       the real writer with those fixed names.
   * </ol>
   */
  private CodeGeneratorResponse.File generateFile(
      FileDescriptorProto fdp, String javaPkg, String outName, Consumer<JavaWriter> body) {
    TypeImports ci = new TypeImports(javaPkg);
    // Collect pass: fill the collector; the emitted source here is discarded.
    body.accept(new JavaWriter(ci));
    ci.finalizeResolution();
    // Render pass: header + package + resolved import block, then the body.
    JavaWriter w = headered(ci, fdp, javaPkg);
    body.accept(w);
    return emit(outName, w);
  }

  /** The file's header comment, package statement, and resolved import block. */
  private JavaWriter headered(TypeImports ci, FileDescriptorProto fdp, String javaPkg) {
    JavaWriter w = new JavaWriter(ci);
    w.header("Generated by recordbuf -- DO NOT EDIT.");
    w.header("Proto: " + fdp.getName());
    w.line("package " + javaPkg + ";");
    w.emitImports();
    return w;
  }

  private static CodeGeneratorResponse.File emit(String name, JavaWriter w) {
    return CodeGeneratorResponse.File.newBuilder().setName(name).setContent(w.toString()).build();
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
   * {@code package} statement (JSpecify's module convention, so the usual package/import/body order
   * a type file uses doesn't apply to a package-info), then the import, under the generated-file
   * header. The content is a fixed shape, built directly rather than through the {@link JavaWriter}
   * body-emission path, because the annotation precedes the package statement there.
   */
  private static CodeGeneratorResponse.File emitPackageInfo(String javaPkg) {
    String name = javaPkg.replace('.', '/') + "/package-info.java";
    String content =
        "/*\n"
            + " * Generated by recordbuf -- DO NOT EDIT.\n"
            + " */\n"
            + "@NullMarked\n"
            + "package "
            + javaPkg
            + ";\n\nimport org.jspecify.annotations.NullMarked;\n";
    return CodeGeneratorResponse.File.newBuilder().setName(name).setContent(content).build();
  }

  private void emitMessage(
      JavaWriter w,
      Message m,
      String javaPkg,
      String sourceProto,
      Map<String, Set<String>> oneofRegistry) {
    TypeImports ci = w.imports();
    TypeResolver types = new TypeResolver(table, ci);
    CodecGenerator codec = new CodecGenerator(types, ci, sourceProto);
    recordAndCodec(w, types, codec, m, javaPkg, sourceProto, oneofRegistry);
  }

  /**
   * The record's class-level Javadoc as one raw block: the type-level summary (when the proto
   * supplied one) followed by one {@code @param <component> <description>} entry per documented
   * component. Rendered through {@link JavaWriter#javadoc(String)}, which HTML-escapes every line;
   * a blank source line between the summary and the {@code @param} section keeps them visually
   * separated. Returns {@code null} when nothing is documented, so the writer emits no block.
   */
  private static @Nullable String recordJavadoc(
      @Nullable String typeComment, List<String[]> params) {
    List<String> lines = new ArrayList<>();
    if (typeComment != null && !typeComment.isBlank()) {
      lines.add(typeComment.strip());
      lines.add("");
    }
    for (String[] p : params) {
      String[] desc = p[1].strip().lines().toArray(String[]::new);
      lines.add("@param " + p[0] + " " + desc[0]);
      for (int i = 1; i < desc.length; i++) {
        lines.add(desc[i]);
      }
    }
    if (lines.isEmpty()) {
      return null;
    }
    return String.join("\n", lines);
  }

  /**
   * Append a component's {@code @param} entry to {@code params} when its comment is non-blank; a
   * component without a comment contributes no {@code @param}.
   */
  private static void addComponentParam(
      List<String[]> params, String componentName, @Nullable String comment) {
    if (comment != null && !comment.isBlank()) {
      params.add(new String[] {componentName, comment});
    }
  }

  /** The fixed description emitted for a oneof's sealed-interface component. */
  private static String oneofComponentDoc(String oneofName) {
    return "The '"
        + oneofName
        + "' oneof: at most one member is present at a time; null when absent. A member's value is "
        + "either one of the interface's permitted message subtypes or one of the nested value "
        + "records (one per non-message member, carrying its underlying value). A null reference "
        + "means the oneof was absent off the wire.";
  }

  /** The fixed description emitted for the always-on unknown-fields carrier component. */
  private static String carrierComponentDoc(String unknownFieldsFqn) {
    return "Unknown fields seen during this record's decode that its schema does not model, captured "
        + "so they survive a round-trip: re-emitted byte-for-byte on encode. An empty decode carries "
        + "the shared "
        + unknownFieldsFqn
        + ".empty(), so the common case adds no payload.";
  }

  private void recordAndCodec(
      JavaWriter w,
      TypeResolver types,
      CodecGenerator codec,
      Message m,
      String javaPkg,
      String sourceProto,
      Map<String, Set<String>> oneofRegistry) {
    TypeImports ci = w.imports();
    // Class-level Javadoc = the record's type-level summary (if any) plus one @param entry per
    // documented component, in declaration order (fields, oneofs, then the always-on carrier).
    // Record components are documented the idiomatic way -- as @param tags in the record's OWN
    // Javadoc, NOT as a per-component block comment in the component header: per-component
    // /** */ is not a valid Javadoc location, so javac's -Xlint:dangling-doc-comments flags every
    // one and the javadoc tool silently drops per-component comments from the generated HTML. A
    // component whose comment is non-blank yields an @param; an undocumented one yields none
    // (the project runs no doclint, so a missing @param never warns).
    String uf = ci.use(RT + ".UnknownFields");
    List<String[]> componentParams = new ArrayList<>();
    for (var f : m.fields()) addComponentParam(componentParams, f.javaName(), f.comment());
    for (Oneof oneof : m.oneofs()) {
      addComponentParam(componentParams, oneof.componentName(), oneofComponentDoc(oneof.name()));
    }
    addComponentParam(componentParams, "unknownFields", carrierComponentDoc(uf));

    w.javadoc(recordJavadoc(m.comment(), componentParams));
    // A bare @Deprecated (java.lang, never imported) when the proto marked this message deprecated.
    if (m.deprecated()) w.line("@Deprecated");
    w.line("public record " + m.name() + "(");
    // Components: header-only (no per-component Javadoc blocks -- their docs live in the @param
    // tags emitted above). @Nullable marks a presence-tracked component, whose null/boxed value is
    // meaningful and is NEVER normalized to empty; every component carries a trailing comma so the
    // always-present carrier is the record's last component.
    for (var f : m.fields()) {
      String type = f.isNullable() ? types.resolveNullable(f) : types.resolve(f);
      // @Deprecated before @Nullable when a component is deprecated; bare
      // (java.lang.Deprecated is never imported, matching recordbuf's style).
      String prefix = f.deprecated() ? "@Deprecated " : "";
      w.line("    " + prefix + type + " " + f.javaName() + ",");
    }
    // One component per real oneof group: a @Nullable reference to the nested sealed interface that
    // name-spaces its members (a nested type, so no import and no @Nullable-prefixed-FQN special
    // case). Null denotes the absent case.
    for (Oneof oneof : m.oneofs()) {
      w.line("    " + oneofComponentType(oneof, ci) + " " + oneof.componentName() + ",");
    }
    // The always-on unknown-field carrier: the record's LAST component, defaulting to the shared
    // empty() so a message that sees nothing unknown carries an empty carrier with no per-record
    // payload. Captured on decode, re-emitted on encode.
    w.line("    " + uf + " unknownFields");
    // Layer A marshal hook: every record implements ProtobufMessage (a tiny,
    // runtime-only marker) so it can be wired out with zero structural conversion
    // at a gRPC/protobuf-java boundary. Added after the option interfaces, so
    // both render in decl order.
    List<String> impls = new ArrayList<>(m.interfaces());
    // K6: a oneof's MESSAGE members are identity subtypes of this record's nested
    // oneof interface, so a message that is a member of some oneof implements that
    // nested interface. Resolved per-invocation from the registry built over every
    // requested file, keyed by this record's own JVM FQN. Option-declared
    // interfaces precede this; the runtime marker is last.
    Set<String> oneofInterfaces = oneofRegistry.get(m.fullName());
    if (oneofInterfaces != null) impls.addAll(oneofInterfaces);
    impls.add(RT + ".ProtobufMessage");
    w.line(")" + RecordGenerator.implementsClause(ci, impls));
    w.line("{");
    w.indent(w.indent() + 1);
    // Compact constructor: null-safe for List/Map fields.
    addCompactConstructor(w, m, ci);
    // Content equality for records carrying a byte[] component.
    addEqualsHashCode(w, m, ci);
    // Nested messages and enums (share this file's collector + resolver).
    for (var nested : m.nestedMessages()) {
      w.line();
      recordAndCodec(w, types, codec, nested, javaPkg, sourceProto, oneofRegistry);
    }
    for (var nested : m.nestedEnums()) {
      w.line();
      new EnumGenerator().write(w, nested, ci);
    }
    // One nested sealed interface per real oneof group: the members' Java types
    // plus a shared Scalar wrapper, with the builder reference and the codec
    // dispatching off the interface's permitted subtypes. Emitted after nested
    // messages/enums and before the builder, so the codec can still reference
    // both the nested interface and the message itself as the last member.
    for (Oneof oneof : m.oneofs()) {
      w.line();
      new OneofGenerator(types, ci).write(w, m, oneof);
    }
    // Fluent builder so consumers can avoid the positional canonical ctor. Emitted
    // before the codec so the Builder (a later member) can reference it freely; the
    // codec still stays the last member so the type can reference itself.
    addBuilder(w, types, ci, m);
    // Layer A hook: toByteArray() (instance) + fromBytes(byte[]) (static), emitted
    // after the builder and before the codec so the forward reference to the codec
    // (a later member) resolves as it did for the map-entry records.
    emitMarshallingHook(w, ci, m);
    // Layer B opt-in: a nested Interop { from(X); to(R); } structural converter to/from
    // the equivalent protobuf-java reference, emitted only when recordbuf.protobuf_java
    // was declared on this message. It stays off for every other message.
    if (m.protobufJava() != null) {
      new InteropGenerator(types, ci, sourceProto).writeInterop(w, m);
    }
    // Codec as last member so the type can reference itself.
    codec.writeCodec(w, m);
    w.indent(w.indent() - 1);
    w.line("}");
  }

  // --- fluent builder ---
  //
  // Every generated message record (top-level + nested) gains a nested static
  // Builder plus a static builder() factory and an instance toBuilder() copy. The
  // builder is strictly value-only: each component has one fluent setter named after
  // the component, typed as the component's own Java type; build() calls the canonical
  // ctor. No child-builder composition, no container adders, no validation (all
  // deferred to follow-up). See docs/plans/2026-11-02-003 for the design KTDs.

  /**
   * Emit a record's fluent builder: the {@code builder()}/{@code toBuilder()} member methods on the
   * record, then the nested {@code static final class Builder}. Called at the record body's indent
   * level, after the record's own nested types.
   */
  private void addBuilder(JavaWriter w, TypeResolver types, TypeImports ci, Message m) {
    w.line();
    w.javadoc(
        w.indent(),
        "Static factory for a fresh "
            + m.name()
            + " {@link Builder}. Build it fluently, then call {@link Builder#build()}.");
    w.line("public static " + m.name() + ".Builder builder() {");
    int f1 = w.indent() + 1;
    w.indent(f1);
    w.line("return new " + m.name() + ".Builder();");
    w.indent(w.indent() - 1);
    w.line("}");
    w.line();
    w.javadoc(
        w.indent(),
        "Returns a "
            + m.name()
            + " Builder pre-populated with this record's values, with"
            + " defensive copies of any mutable component.");
    w.line("public " + m.name() + ".Builder toBuilder() {");
    int f2 = w.indent() + 1;
    w.indent(f2);
    w.line("return new " + m.name() + ".Builder(this);");
    w.indent(w.indent() - 1);
    w.line("}");

    w.line();
    w.javadoc(
        w.indent(),
        "Fluent, value-only builder for the " + m.name() + " record. Generated by recordbuf.");
    w.line("public static final class Builder {");
    int b = w.indent() + 1;
    w.indent(b);

    // One mutable storage field per component, initialized to its codec default.
    for (var f : m.fields()) {
      w.line(
          "private "
              + componentType(f, types)
              + " "
              + f.javaName()
              + " = "
              + freshDefault(f, types, ci)
              + ";");
    }
    // One nested-sealed-interface reference per oneof, defaulting to null (the
    // absent oneof case). Type is @Nullable <Iface> (a nested type of the record).
    for (Oneof oneof : m.oneofs()) {
      w.line("private " + oneofComponentType(oneof, ci) + " " + oneof.componentName() + " = null;");
    }
    // The always-on carrier, defaulting to the shared empty() so the common
    // path carries no unknown-field payload.
    String uf = ci.use(RT + ".UnknownFields");
    w.line("private " + uf + " unknownFields = " + uf + ".empty();");
    w.line();
    // Fresh builder: the field initializers above supply the codec default values.
    w.line("private Builder() {}");
    w.line();
    // Copy constructor: a defensive per-component copy so toBuilder() never aliases
    // the source record's mutable containers or byte arrays.
    w.line("private Builder(" + m.name() + " x) {");
    int c1 = w.indent() + 1;
    w.indent(c1);
    for (var f : m.fields()) {
      w.line(copyAssignment(f, ci));
    }
    // Oneof references copy by-value (the nested interface is an immutable
    // reference type; null stays null).
    for (Oneof oneof : m.oneofs()) {
      w.line("this." + oneof.componentName() + " = x." + oneof.componentName() + "();");
    }
    // The carrier is immutable; a copy ctor only references it (no defensive copy).
    w.line("this.unknownFields = x.unknownFields();");
    w.indent(w.indent() - 1);
    w.line("}");

    // One fluent setter per component, named after the component, returning the
    // builder so calls chain. The setter's Javadoc mirrors the field's (R6).
    for (var f : m.fields()) {
      w.line();
      w.javadoc(w.indent(), f.comment());
      w.line(
          "public Builder "
              + f.javaName()
              + "("
              + componentType(f, types)
              + " "
              + f.javaName()
              + ") {");
      int s1 = w.indent() + 1;
      w.indent(s1);
      w.line("this." + f.javaName() + " = " + f.javaName() + ";");
      w.line("return this;");
      w.indent(w.indent() - 1);
      w.line("}");
    }
    // The carrier's fluent setter; the fresh builder defaults it to empty().
    w.line();
    w.javadoc(
        w.indent(),
        "Set this record's unknown-fields carrier. Default is the shared "
            + "empty() when nothing was captured during a decode.");
    w.line("public Builder unknownFields(" + uf + " unknownFields) {");
    int s1u = w.indent() + 1;
    w.indent(s1u);
    w.line("this.unknownFields = unknownFields;");
    w.line("return this;");
    w.indent(w.indent() - 1);
    w.line("}");
    // One fluent setter per oneof, taking a member value (an identity subtype
    // or the shared Scalar wrapper) and folding it into the nested interface.
    for (Oneof oneof : m.oneofs()) {
      String type = oneofComponentType(oneof, ci);
      String cmp = oneof.componentName();
      w.line();
      w.line("public Builder " + cmp + "(" + type + " " + cmp + ") {");
      int s1o = w.indent() + 1;
      w.indent(s1o);
      w.line("this." + cmp + " = " + cmp + ";");
      w.line("return this;");
      w.indent(s1o - 1);
      w.line("}");
    }
    w.line();
    // Terminal build(): constructs the record via its canonical ctor.
    w.javadoc(
        w.indent(),
        "Builds a "
            + m.name()
            + " record from the accumulated field values via the canonical"
            + " constructor.");
    w.line("public " + m.name() + " build() {");
    int bd = w.indent() + 1;
    w.indent(bd);
    w.line("return new " + m.name() + "(");
    for (int i = 0; i < m.fields().size(); i++) {
      var f = m.fields().get(i);
      // Always a trailing comma: the unknownFields carrier is the last ctor arg.
      w.line("    this." + f.javaName() + ",");
    }
    // Oneof components follow the fields in the canonical ctor's declaration
    // order (the record declares its oneofs right after its fields).
    for (Oneof oneof : m.oneofs()) {
      w.line("    this." + oneof.componentName() + ",");
    }
    w.line("    this.unknownFields");
    w.line(");");
    w.indent(w.indent() - 1);
    w.line("}");

    w.indent(w.indent() - 1);
    w.line("}");
  }

  /** The component's Java static type, matching the record's own component. */
  private static String componentType(Field f, TypeResolver types) {
    return f.isNullable() ? types.resolveNullable(f) : types.resolve(f);
  }

  /**
   * The record/builder component type for a oneof: {@code @Nullable <Iface>}, a reference to the
   * nested sealed interface that name-spaces the oneof's members. A nested type of the record
   * resolves to its bare short name (no import); the {@code @Nullable} is imported like any other
   * referenced type.
   */
  private static String oneofComponentType(Oneof oneof, TypeImports ci) {
    String ann = ci.use("org.jspecify.annotations.Nullable");
    return "@" + ann + " " + oneof.ifaceName();
  }

  /**
   * Emit the Layer A marshalling hook for a record: an instance {@code toByteArray()} that
   * implements {@code bijnagte.recordbuf.runtime.ProtobufMessage} by delegating to the record's own
   * {@code Codec.encode}, and a {@code static fromBytes(byte[])} (a static cannot ride the marker
   * interface, so the read half is reached by a decoder function held by the runtime {@code
   * ProtobufMarshaller}, never by reflection). Emitted after the builder and before the codec, so
   * the forward reference to the later {@code Codec} member resolves as it does for the map-entry
   * records. The unqualified {@code Codec} resolves to this record's own nested {@code Codec}
   * (innermost scope), correct for both top-level and nested messages.
   */
  private void emitMarshallingHook(JavaWriter w, TypeImports ci, Message m) {
    String io = ci.use("java.io.IOException");
    w.line();
    w.javadoc(
        w.indent(),
        "Wire-encode this record through its generated Codec. The bytes are "
            + "byte-identical to the equivalent protobuf-java message, so this record can "
            + "cross a gRPC / protobuf-java boundary with no structural conversion.");
    w.line("@Override");
    w.line("public byte[] toByteArray() throws " + io + " { return Codec.encode(this); }");
    w.line();
    w.javadoc(
        w.indent(),
        "Decode a record from its wire bytes via the generated Codec. The "
            + "runtime ProtobufMarshaller holds a record type's fromBytes as its decoder.");
    w.line(
        "public static "
            + m.name()
            + " fromBytes(byte[] in) throws "
            + io
            + " { return Codec.decode(in); }");
  }

  /**
   * The fresh builder's storage-field default -- mirrors the codec's decode default (empty
   * containers, codec-default scalars/enum, null for present-able fields).
   */
  private static String freshDefault(Field f, TypeResolver types, TypeImports ci) {
    if (f.isMap()) return "new " + ci.use("java.util.HashMap") + "<>()";
    if (f.isRepeated()) return "new " + ci.use("java.util.ArrayList") + "<>()";
    if (f.isNullable()) return "null";
    // Not nullable, not a container: a singular scalar or enum (a singular message
    // is always present-able, so it reached the null branch above).
    if (f.kind() == FieldKind.ENUM) return types.resolveElement(f) + ".forNumber(0)";
    return freshScalarDefault(types.wireScalarForType(f.typeUrl()));
  }

  private static String freshScalarDefault(TypeResolver.WireScalar wire) {
    return switch (wire) {
      case INT32, UINT32, SINT_32, FIXED32 -> "0";
      case INT64, UINT64, SINT_64, FIXED64 -> "0L";
      case FLOAT -> "0.0f";
      case DOUBLE -> "0.0";
      case BOOL -> "false";
      case STRING -> "\"\"";
      case BYTES -> "new byte[0]";
    };
  }

  /**
   * One copy-constructor assignment per component: a defensive copy for containers and byte arrays,
   * a by-value reference for everything else. Returns the full statement including its terminating
   * semicolon.
   */
  private static String copyAssignment(Field f, TypeImports ci) {
    String lhs = "this." + f.javaName() + " = ";
    String src = "x." + f.javaName() + "()";
    if (f.isMap()) return lhs + "new " + ci.use("java.util.HashMap") + "<>(" + src + ");";
    if (f.isRepeated()) return lhs + "new " + ci.use("java.util.ArrayList") + "<>(" + src + ");";
    if (isByteArrayComponent(f)) {
      // A byte-array component defaults to an empty array when its source is null, matching the
      // codec's decode default (CodecDefaults.bytes() is new byte[0]), so a @NonNull component
      // (the proto3 singular-bytes default) never receives null. A rare @Nullable byte-array field
      // keeps a null default -- null is a legal present-but-absent value -- which stays
      // null-safe. The null branch is only reachable for @Nullable fields; for the common
      // @NonNull byte[] it is a defensive no-op that NullAway cannot prove dead but is harmless.
      String nullDefault = f.isNullable() ? "null" : "new byte[0]";
      return lhs + src + " == null ? " + nullDefault + " : " + src + ".clone();";
    }
    return lhs + src + ";";
  }

  /**
   * Emit a compact constructor that defaults null {@code List}/{@code Map} components to empty
   * unmodifiable copies. (@Nullable components are not normalized: {@code null} is a valid value
   * for a present-but-absent field.
   */
  private void addCompactConstructor(JavaWriter w, Message m, TypeImports ci) {
    boolean needsCompact = m.fields().stream().anyMatch(f -> f.isRepeated() || f.isMap());
    if (!needsCompact) return;
    w.line();
    w.line("public " + m.name() + " {");
    w.indent(w.indent() + 1);
    for (var f : m.fields()) {
      if (f.isRepeated()) {
        w.line(
            f.javaName()
                + " = "
                + f.javaName()
                + " == null ? "
                + ci.use("java.util.List")
                + ".of() : "
                + ci.use("java.util.List")
                + ".copyOf("
                + f.javaName()
                + ");");
      } else if (f.isMap()) {
        w.line(
            f.javaName()
                + " = "
                + f.javaName()
                + " == null ? "
                + ci.use("java.util.Map")
                + ".of() : "
                + ci.use("java.util.Map")
                + ".copyOf("
                + f.javaName()
                + ");");
      }
    }
    w.indent(w.indent() - 1);
    w.line("}");
  }

  /**
   * Emit {@code equals}/{@code hashCode} overrides for a record that carries a {@code byte[]}
   * component. The JDK's implicit record {@code equals} compares components by reference, so a
   * {@code byte[]} component would compare by array identity — decoding yields a fresh array, so
   * round-trip/decode comparison would fail. {@code byte[]} components are therefore compared by
   * content via {@link java.util.Arrays}, while every other component keeps its identity/via-{@code
   * Objects} semantics.
   *
   * <p><b>Known limitation:</b> this only handles a DIRECT {@code byte[]} component. A component
   * that CONTAINS byte arrays -- e.g. {@code List<byte[]>} (a {@code repeated bytes} field) or
   * {@code Map<?,byte[]>} (a bytes-valued map) -- is still compared by the JDK's implicit record
   * {@code equals}, which matches nested {@code byte[]} elements by IDENTITY rather than content,
   * and {@code Arrays.deepHashCode} does not recurse into a {@code List}/ {@code Map}. Wire
   * encode/decode of such fields is correct; only value equality of the boxed container is not
   * content-correct. Fixing this without breaking the {@code equals}/{@code hashCode} contract for
   * the common cases needs a per-field content hash and is left as a follow-up.
   */
  private void addEqualsHashCode(JavaWriter w, Message m, TypeImports ci) {
    if (m.fields().stream().noneMatch(f -> isByteArrayComponent(f))) return;

    w.line();
    w.line("@Override");
    w.line("public boolean equals(Object o) {");
    w.indent(w.indent() + 1);
    w.line("if (this == o) return true;");
    w.line("if (!(o instanceof " + m.name() + " that)) return false;");
    w.line("return " + equalsExpression(m, ci) + ";");
    w.indent(w.indent() - 1);
    w.line("}");

    w.line();
    w.line("@Override");
    w.line("public int hashCode() {");
    w.indent(w.indent() + 1);
    w.line(
        "return "
            + ci.use("java.util.Arrays")
            + ".deepHashCode(new "
            + ci.use("java.lang.Object")
            + "[]{"
            + components(m, "this.")
            + "});");
    w.indent(w.indent() - 1);
    w.line("}");
  }

  /** A record component whose Java type is {@code byte[]} (a non-repeated bytes scalar). */
  private static boolean isByteArrayComponent(Field f) {
    return "bytes".equals(f.typeUrl()) && !f.isRepeated();
  }

  /** One {@code cmp(this.c, that.c)} per field, {@code &&}-joined. */
  private static String equalsExpression(Message m, TypeImports ci) {
    List<String> parts = new ArrayList<>();
    for (var f : m.fields()) {
      String cmp =
          isByteArrayComponent(f)
              ? ci.use("java.util.Arrays") + ".equals"
              : ci.use("java.util.Objects") + ".equals";
      parts.add(cmp + "(this." + f.javaName() + ", that." + f.javaName() + ")");
    }
    return String.join(" && ", parts);
  }

  /** Comma-joined {@code prefix + name} for every field, used by deepHashCode. */
  private static String components(Message m, String prefix) {
    List<String> names = new ArrayList<>();
    for (var f : m.fields()) names.add(prefix + f.javaName());
    return String.join(", ", names);
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
