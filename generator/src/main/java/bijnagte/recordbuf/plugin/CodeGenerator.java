package bijnagte.recordbuf.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.Feature;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.ModelLoader;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;

import org.jspecify.annotations.Nullable;
import bijnagte.recordbuf.plugin.types.TypeResolver;
import bijnagte.recordbuf.plugin.writer.CodecGenerator;
import bijnagte.recordbuf.plugin.writer.EnumGenerator;
import bijnagte.recordbuf.plugin.writer.JavaWriter;
import bijnagte.recordbuf.plugin.writer.RecordGenerator;
import bijnagte.recordbuf.plugin.writer.TypeImports;

/**
 * Drives code generation for a single {@link CodeGeneratorRequest}.
 *
 * <p>For each file in the request's {@code file_to_generate} list we produce
 * one Java source file (the record for that file's top-level types).
 * Dependencies (the other {@code proto_file} entries) are not generated,
 * but are used to build the type-resolution table.
 */
public final class CodeGenerator {

    // The recordbuf runtime package, referenced by generated records for the
    // unknown-field carrier and the ProtobufMessage marker.
    private static final String RT = "bijnagte.recordbuf.runtime";

    // Assigned at the start of generate() from the request's plugin parameter so
    // per-call generation (Main invokes one CodeGenerator per request) stays
    // stateless. Built fresh each call with the parsed package_suffix.
    private FileTable table = new FileTable();

    /** Run generation; returns a response ready to serialize to the client. */
    public CodeGeneratorResponse generate(CodeGeneratorRequest request) {
        // Pass 1: load ALL files (including dependencies) into the type table.
        // Without this, we wouldn't know the Java FQNs for cross-file types.
        // The table carries the run-wide package_suffix so every file's java package
        // (and every cross-file resolution through it) is suffixed uniformly.
        table = new FileTable(parsePackageSuffix(request.getParameter()));
        table.addAll(request.getProtoFileList());

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
        String outDir = "src/main/java/" + javaPkg.replace('.', '/') + "/";
        List<CodeGeneratorResponse.File> files = new ArrayList<>();

        // Each top-level enum gets its OWN file so the public type name matches
        // its filename (Java requires one public type per source file).
        for (var e : pf.enums()) {
            files.add(generateFile(fdp, javaPkg, outDir + JavaNames.cap(e.name()) + ".java",
                    w -> new EnumGenerator().write(w, e, w.imports())));
        }
        // Each top-level message gets its OWN file (record + nested types + codec).
        for (var m : pf.messages()) {
            files.add(generateFile(fdp, javaPkg, outDir + JavaNames.cap(m.name()) + ".java",
                    w -> emitMessage(w, m, javaPkg, fdp.getName())));
        }
        return files;
    }

    /**
     * Generate one Java source file for a single top-level type.
     *
     * <p>Generation runs in <b>two passes over a shared {@link TypeImports}
     * collector</b> so a file's referenced types resolve to short names with a
     * deduplicated, ordered import block — not fully-qualified, repeated names:
     * <ol>
     *   <li>a <i>collect</i> pass runs the body on a throwaway writer, filling the
     *       collector with every type the file references (a collision is a
     *       property of the whole set, so it can only be known after the file is
     *       walked in full);</li>
     *   <li>{@link TypeImports#finalizeResolution()} then fixes each name to its
     *       short form (imported) or fully-qualified form (when its short name
     *       collides),</li>
     *   <li>a <i>render</i> pass writes the header, package statement, import
     *       block, and body into the real writer with those fixed names.</li>
     * </ol>
     */
    private CodeGeneratorResponse.File generateFile(FileDescriptorProto fdp, String javaPkg,
                                                    String outName, Consumer<JavaWriter> body) {
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
        return CodeGeneratorResponse.File.newBuilder()
                .setName(name)
                .setContent(w.toString())
                .build();
    }

    private void emitMessage(JavaWriter w, Message m, String javaPkg, String sourceProto) {
        TypeImports ci = w.imports();
        TypeResolver types = new TypeResolver(table, ci);
        CodecGenerator codec = new CodecGenerator(types, ci, sourceProto);
        recordAndCodec(w, types, codec, m, javaPkg, sourceProto);
    }

    private void recordAndCodec(JavaWriter w, TypeResolver types, CodecGenerator codec,
                                Message m, String javaPkg, String sourceProto) {
        TypeImports ci = w.imports();
        // Type-level Javadoc (clean, HTML-escaped at emit time) followed by a bare
        // @Deprecated when the proto marked this message deprecated. A bare (java.lang,
        // never-imported) annotation matches recordbuf's "clean output" style.
        w.javadoc(m.comment());
        if (m.deprecated()) w.line("@Deprecated");
        w.line("public record " + m.name() + "(");
        for (int i = 0; i < m.fields().size(); i++) {
            var f = m.fields().get(i);
            // A presence-tracked (nullable) component is annotated @Nullable and its
            // null/boxed value is meaningful; it is NEVER normalized to empty.
            String type = f.isNullable() ? types.resolveNullable(f) : types.resolve(f);
            // Every field now carries a trailing comma: the always-present
            // unknownFields carrier is emitted as the last component after the loop.
            // Per-component Javadoc sits one level deeper than the type header's,
            // to match the fields' own 4-space-deeper indentation.
            w.javadoc(w.indent() + 1, f.comment());
            // @Deprecated before @Nullable when a component is deprecated; bare
            // (java.lang.Deprecated is never imported, matching recordbuf's style).
            String prefix = f.deprecated() ? "@Deprecated " : "";
            w.line("    " + prefix + type + " " + f.javaName() + ",");
        }
        // The always-on unknown-field carrier: the record's LAST component, defaulting
        // to the shared empty() so a message that sees nothing unknown carries an empty
        // carrier with no per-record payload. Captured on decode, re-emitted on encode.
        String uf = ci.use(RT + ".UnknownFields");
        w.javadoc(w.indent() + 1, "Unknown fields seen during this record's decode that its schema does "
                + "not model, captured so they survive a round-trip: re-emitted byte-for-byte on "
                + "encode. An empty decode carries the shared " + uf
                + ".empty(), so the common case adds no payload.");
        w.line("    " + uf + " unknownFields");
        // Layer A marshal hook: every record implements ProtobufMessage (a tiny,
        // runtime-only marker) so it can be wired out with zero structural conversion
        // at a gRPC/protobuf-java boundary. Added after the option interfaces, so
        // both render in decl order.
        List<String> impls = new ArrayList<>(m.interfaces());
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
            recordAndCodec(w, types, codec, nested, javaPkg, sourceProto);
        }
        for (var nested : m.nestedEnums()) {
            w.line();
            new EnumGenerator().write(w, nested, ci);
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
            new bijnagte.recordbuf.plugin.writer.InteropGenerator(types, ci, sourceProto)
                    .writeInterop(w, m);
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
     * Emit a record's fluent builder: the {@code builder()}/{@code toBuilder()} member
     * methods on the record, then the nested {@code static final class Builder}. Called
     * at the record body's indent level, after the record's own nested types.
     */
    private void addBuilder(JavaWriter w, TypeResolver types, TypeImports ci, Message m) {
        w.line();
        w.javadoc(w.indent(), "Static factory for a fresh " + m.name()
                + " {@link Builder}. Build it fluently, then call {@link Builder#build()}.");
        w.line("public static " + m.name() + ".Builder builder() {");
        int f1 = w.indent() + 1;
        w.indent(f1);
        w.line("return new " + m.name() + ".Builder();");
        w.indent(w.indent() - 1);
        w.line("}");
        w.line();
        w.javadoc(w.indent(), "Returns a " + m.name()
                + " Builder pre-populated with this record's values, with"
                + " defensive copies of any mutable component.");
        w.line("public " + m.name() + ".Builder toBuilder() {");
        int f2 = w.indent() + 1;
        w.indent(f2);
        w.line("return new " + m.name() + ".Builder(this);");
        w.indent(w.indent() - 1);
        w.line("}");

        w.line();
        w.javadoc(w.indent(), "Fluent, value-only builder for the " + m.name()
                + " record. Generated by recordbuf.");
        w.line("public static final class Builder {");
        int b = w.indent() + 1;
        w.indent(b);

        // One mutable storage field per component, initialized to its codec default.
        for (var f : m.fields()) {
            w.line("private " + componentType(f, types) + " " + f.javaName()
                    + " = " + freshDefault(f, types, ci) + ";");
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
        // The carrier is immutable; a copy ctor only references it (no defensive copy).
        w.line("this.unknownFields = x.unknownFields();");
        w.indent(w.indent() - 1);
        w.line("}");

        // One fluent setter per component, named after the component, returning the
        // builder so calls chain. The setter's Javadoc mirrors the field's (R6).
        for (var f : m.fields()) {
            w.line();
            w.javadoc(w.indent(), f.comment());
            w.line("public Builder " + f.javaName() + "(" + componentType(f, types)
                    + " " + f.javaName() + ") {");
            int s1 = w.indent() + 1;
            w.indent(s1);
            w.line("this." + f.javaName() + " = " + f.javaName() + ";");
            w.line("return this;");
            w.indent(w.indent() - 1);
            w.line("}");
        }
        // The carrier's fluent setter; the fresh builder defaults it to empty().
        w.line();
        w.javadoc(w.indent(), "Set this record's unknown-fields carrier. Default is the shared "
                + "empty() when nothing was captured during a decode.");
        w.line("public Builder unknownFields(" + uf + " unknownFields) {");
        int s1u = w.indent() + 1;
        w.indent(s1u);
        w.line("this.unknownFields = unknownFields;");
        w.line("return this;");
        w.indent(w.indent() - 1);
        w.line("}");
        w.line();
        // Terminal build(): constructs the record via its canonical ctor.
        w.javadoc(w.indent(), "Builds a " + m.name()
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
     * Emit the Layer A marshalling hook for a record: an instance {@code
     * toByteArray()} that implements {@code bijnagte.recordbuf.runtime.ProtobufMessage}
     * by delegating to the record's own {@code Codec.encode}, and a {@code static
     * fromBytes(byte[])} (a static cannot ride the marker interface, so the read
     * half is reached by a decoder function held by the runtime
     * {@code ProtobufMarshaller}, never by reflection). Emitted after the builder
     * and before the codec, so the forward reference to the later {@code Codec}
     * member resolves as it does for the map-entry records. The unqualified
     * {@code Codec} resolves to this record's own nested {@code Codec} (innermost
     * scope), correct for both top-level and nested messages.
     */
    private void emitMarshallingHook(JavaWriter w, TypeImports ci, Message m) {
        String io = ci.use("java.io.IOException");
        w.line();
        w.javadoc(w.indent(), "Wire-encode this record through its generated Codec. The bytes are "
                + "byte-identical to the equivalent protobuf-java message, so this record can "
                + "cross a gRPC / protobuf-java boundary with no structural conversion.");
        w.line("@Override");
        w.line("public byte[] toByteArray() throws " + io + " { return Codec.encode(this); }");
        w.line();
        w.javadoc(w.indent(), "Decode a record from its wire bytes via the generated Codec. The "
                + "runtime ProtobufMarshaller holds a record type's fromBytes as its decoder.");
        w.line("public static " + m.name() + " fromBytes(byte[] in) throws " + io
                + " { return Codec.decode(in); }");
    }


    /** The fresh builder's storage-field default -- mirrors the codec's decode default
     *  (empty containers, codec-default scalars/enum, null for present-able fields). */
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

    /** One copy-constructor assignment per component: a defensive copy for containers
     *  and byte arrays, a by-value reference for everything else. Returns the full
     *  statement including its terminating semicolon. */
    private static String copyAssignment(Field f, TypeImports ci) {
        String lhs = "this." + f.javaName() + " = ";
        String src = "x." + f.javaName() + "()";
        if (f.isMap()) return lhs + "new " + ci.use("java.util.HashMap") + "<>(" + src + ");";
        if (f.isRepeated()) return lhs + "new " + ci.use("java.util.ArrayList") + "<>(" + src + ");";
        if (isByteArrayComponent(f))
            return lhs + src + " == null ? null : " + src + ".clone();";
        return lhs + src + ";";
    }

    /** Emit a compact constructor that defaults null {@code List}/{@code Map}
     *  components to empty unmodifiable copies. (@Nullable components are not
     *  normalized: {@code null} is a valid value for a present-but-absent field. */
    private void addCompactConstructor(JavaWriter w, Message m, TypeImports ci) {
        boolean needsCompact = m.fields().stream().anyMatch(f -> f.isRepeated() || f.isMap());
        if (!needsCompact) return;
        w.line();
        w.line("public " + m.name() + " {");
        w.indent(w.indent() + 1);
        for (var f : m.fields()) {
            if (f.isRepeated()) {
                w.line(f.javaName() + " = " + f.javaName() + " == null ? "
                        + ci.use("java.util.List") + ".of() : "
                        + ci.use("java.util.List") + ".copyOf(" + f.javaName() + ");");
            } else if (f.isMap()) {
                w.line(f.javaName() + " = " + f.javaName() + " == null ? "
                        + ci.use("java.util.Map") + ".of() : "
                        + ci.use("java.util.Map") + ".copyOf(" + f.javaName() + ");");
            }
        }
        w.indent(w.indent() - 1);
        w.line("}");
    }

    /**
     * Emit {@code equals}/{@code hashCode} overrides for a record that carries a
     * {@code byte[]} component. The JDK's implicit record {@code equals} compares
     * components by reference, so a {@code byte[]} component would compare by
     * array identity — decoding yields a fresh array, so round-trip/decode
     * comparison would fail. {@code byte[]} components are therefore compared
     * by content via {@link java.util.Arrays}, while every other component keeps
     * its identity/via-{@code Objects} semantics.
     *
     * <p><b>Known limitation:</b> this only handles a DIRECT {@code byte[]}
     * component. A component that CONTAINS byte arrays -- e.g. {@code List<byte[]>}
     * (a {@code repeated bytes} field) or {@code Map<?,byte[]>} (a bytes-valued
     * map) -- is still compared by the JDK's implicit record {@code equals}, which
     * matches nested {@code byte[]} elements by IDENTITY rather than content, and
     * {@code Arrays.deepHashCode} does not recurse into a {@code List}/
     * {@code Map}. Wire encode/decode of such fields is correct; only value
     * equality of the boxed container is not content-correct. Fixing this without
     * breaking the {@code equals}/{@code hashCode} contract for the common cases
     * needs a per-field content hash and is left as a follow-up.
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
        w.line("return " + ci.use("java.util.Arrays") + ".deepHashCode(new "
                + ci.use("java.lang.Object") + "[]{" + components(m, "this.") + "});");
        w.indent(w.indent() - 1);
        w.line("}");
    }

    /** A record component whose Java type is {@code byte[]} (a non-repeated bytes scalar). */
    private static boolean isByteArrayComponent(bijnagte.recordbuf.plugin.model.Field f) {
        return "bytes".equals(f.typeUrl()) && !f.isRepeated();
    }

    /** One {@code cmp(this.c, that.c)} per field, {@code &&}-joined. */
    private static String equalsExpression(Message m, TypeImports ci) {
        List<String> parts = new ArrayList<>();
        for (var f : m.fields()) {
            String cmp = isByteArrayComponent(f) ? ci.use("java.util.Arrays") + ".equals"
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
     * Resolve the run-wide {@code package_suffix} parameter to a normalized
     * suffix (leading/trailing dots and whitespace stripped; absent/blank is an
     * empty no-op). The parameter is the protoc plugin param string
     * (e.g. {@code package_suffix=.rec}) — comma-separated {@code key=value}
     * pairs — of which only {@code package_suffix} is honored. An absent value
     * leaves every file's java package byte-for-byte unchanged (R5).
     */
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
