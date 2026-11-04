package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeResolver;

/**
 * Emits the Layer B structural converter -- a nested {@code Interop} type with
 * {@code static from(X)} and {@code static to(R)} -- for one top-level message.
 *
 * <p>The converter is the opt-in complement to Layer A (a record's {@code Codec}
 * / {@code toByteArray} marshalling hook): byte marshalling stays the record's
 * codec; {@code Interop} is a pure structural {@code X}<->{@code R} copy that
 * touches no wire bytes, so it inherits byte-identity from the codec. It is
 * emitted only when the message opts in with the {@code recordbuf.protobuf_java}
 * option (naming the java_package that hosts the equivalent protobuf-java
 * reference, {@code X}).
 *
 * <h2>Field-by-field rules</h2>
 *
 * <ul>
 *   <li><b>scalar</b> &rarr; {@code x.getF()} / {@code b.setF(v)}; for the proto3
 *   <em>optional</em> (presence) family the local is the boxed wrapper, read via
 *   {@code x.hasF() ? x.getF() : null} and written with a {@code null} guard so an
 *   absent optional does not material a presence bit on the ref.</li>
 *   <li><b>bytes</b> (singular or repeated) &rarr; {@code x.getB().toByteArray()}
 *   / {@code com.google.protobuf.ByteString.copyFrom(b)}.</li>
 *   <li><b>repeated scalar / enum / bytes</b> &rarr; accumulate elements into a
 *   fresh {@link java.util.ArrayList} with a per-element map for bytes.</li>
 *   <li><b>map</b> &rarr; from {@code getMMap().entrySet()} into a fresh
 *   {@link java.util.HashMap} / to {@code putM(...)} over the entry set.
 *   Scalar key / value families only; a message / enum map value is out of scope
 *   (matches the codec).</li>
 *   <li><b>message</b> &rarr; via {@code recSibling.Interop.from / to} on the
 *   sibling record, singular via a {@code has<Name>()} guard, repeated by looping.</li>
 *   <li><b>enum</b> &rarr; mapped by number; record enums have
 *   {@code getNumber()}/{@code forNumber(int)} (see {@link EnumGenerator}),
 *   protobuf-java ref enums expose {@code getNumber()}.</li>
 *   <li><b>oneof</b> (singular members only) &rarr; {@code from} switches on the
 *   reference's {@code get<XxxCase>()} discriminator, mapping the active case to
 *   the nested value record (scalar / bytes / enum) or the member record's own
 *   {@code Interop.from} (a message member); {@code to} pattern-matches the
 *   sealed component back into the builder via its {@code setX}. A null component
 *   (an absent oneof) is the {@code default}/{@code null} arm -- proto3 sets
 *   nothing when the oneof is absent, so the converter writes nothing back.</li>
 * </ul>
 *
 * <h2>Imports</h2>
 *
 * <p>Standard-library and protobuf-java container types that are actually
 * emitted -- {@link java.util.List} / {@link java.util.ArrayList} /
 * {@link java.util.HashMap} and protobuf-java's
 * {@link com.google.protobuf.ByteString} -- go through the {@link TypeImports}
 * collector as short, collision-resolved imports, registered only when the
 * field is present in the message (no blank registration for absent fields).
 * The {@code java.util.Map} reference type from {@code map<K,V>} comes from
 * {@link bijnagte.recordbuf.plugin.types.TypeResolver#resolve} and is
 * collision-resolved the same way.</p>
 *
 * <p>The <em>record</em> sibling types -- the record being converted and any
 * proto message / enum it references -- are emitted in the record's own package
 * (all top-level records from one .proto share one record package), and
 * {@code Interop} is nested inside the record it converts. So the record and its
 * record siblings resolve by their <em>short</em> name in the enclosing
 * scope; no import and no FQN is needed.</p>
 *
 * <p>The <em>protobuf-java</em> reference types, however, live in a different
 * package (the option's {@code java_package}) and may even share a simple name
 * with the record -- e.g. a {@code combined.Message} reference next to the record's
 * own {@code rec.Message}. Short names would be ambiguous there, so the reference
 * types are spelled out fully-qualified at every use. A user may name a protobuf
 * message or enum anything, so a collision with a Java standard type is possible
 * on the <em>proto</em> type -- that's the collision the user controls. The
 * standard library is handled by the import collector; the reference types are
 * FQN'd.</p>
 *
 * <p>Not covered (documented as future work, not silently broken):
 * an <em>optional</em> enum field (not exercised today) and message / enum map
 * values (out of scope for this family, matching the codec's own limitation).
 */
public final class InteropGenerator {

    // Standard-library / protobuf-java container references, resolved to
    // short, collision-imported names via TypeImports.
    private static final String LIST = "java.util.List";
    private static final String ARRAY_LIST = "java.util.ArrayList";
    private static final String HASH_MAP = "java.util.HashMap";
    private static final String BYTE_STRING = "com.google.protobuf.ByteString";

    private final TypeResolver types;
    private final TypeImports ci;
    private final String sourceName;

    /**
     * @param types     the shared type resolver for this record type
     * @param ci        the shared import collector for this record type
     * @param sourceName   the source .proto file name (for the generated javadoc)
     */
    public InteropGenerator(TypeResolver types, TypeImports ci, String sourceName) {
        this.types = types;
        this.ci = ci;
        this.sourceName = sourceName;
    }

    /**
     * Writes a nested {@code Interop} converter for the opted-in record
     * {@code m}; the equivalent protobuf-java reference's java_package is
     * {@link Message#protobufJava()}.
     */
    public void writeInterop(JavaWriter out, Message m) {
        String refPkg = m.protobufJava();
        // Only opted-in messages reach us, but a null ref package is guarded so
        // NullAway can prove the downstream FQN lookups are non-null.
        if (refPkg == null || refPkg.isEmpty()) return;
        String refType = JavaNames.cap(m.name());

        out.line();
        out.javadoc(
                "Structural X\u003c-\u003eR converter between "
                        + recordFqn(m.name()) + " (record) and "
                        + refFqn(refPkg, refType) + " (protobuf-java), generated from "
                        + sourceName
                        + ". Field-by-field conversion only -- it does NOT decode wire bytes (the "
                        + "Codec does that); byte-identity is inherited because it maps the same "
                        + "components. Referenced messages / enums recurse through their own sibling "
                        + "Interop, by number for enums.");
        out.line("public static final class Interop {");
        out.indent(out.indent() + 1);
        out.line("private Interop() {}");
        out.line();
        emitFrom(out, m, refPkg, refType);
        out.line();
        emitTo(out, m, refPkg, refType);
        out.indent(out.indent() - 1);
        out.line("}");
    }

    // -- from(reference) -> record ------------------------------------------------

    private void emitFrom(JavaWriter w, Message m, String refPkg, String refType) {
        String ref = refFqn(refPkg, refType);
        String rec = recordFqn(m.name());
        w.line("public static " + rec + " from(" + ref + " x) {");
        w.indent(w.indent() + 1);
        for (Field f : m.fields()) {
            fromLocal(w, f);
        }
        int n = m.fields().size();
        // Oneof locals: oneof members are converted -- `from` switches on the
        // reference's getCase() discriminator to reconstruct the sealed component.
        for (Oneof o : m.oneofs()) {
            emitOneofFrom(w, o);
        }
        w.line("return new " + rec + "(");
        for (int i = 0; i < n; i++) {
            // Every field now carries a trailing comma: the carrier is the last arg.
            w.line("    _" + m.fields().get(i).javaName() + ",");
        }
        // Oneof components appended after the fields, before the carrier.
        for (Oneof o : m.oneofs()) {
            w.line("    _" + o.name() + ",");
        }
        // Layer B `from(X)` is a structural X<->R copy that touches no wire bytes, so
        // the record's unknown-fields carrier stays empty: unknown fields do not cross
        // the protobuf-java boundary (a design choice, not a loss -- Layer A handles
        // unknown fields on the record's own codec, not on this converter).
        w.line("    " + ci.use("bijnagte.recordbuf.runtime.UnknownFields") + ".empty()");
        w.line("    );");
        w.indent(w.indent() - 1);
        w.line("}");
    }

    private void fromLocal(JavaWriter w, Field f) {
        String cap = JavaNames.cap(f.javaName());
        if (f.isMap()) {
            String type = types.resolve(f);
            w.line(type + " _" + f.javaName() + " = new " + ci.use(HASH_MAP) + "<>();");
            w.line("for (var _e : x.get" + cap + "Map().entrySet()) _" + f.javaName()
                    + ".put(" + mapKeyFrom(f) + ", " + mapValueFrom(f) + ");");
            return;
        }
        if (f.isMessage() && f.isRepeated()) {
            String recSibling = recordSiblingFqn(f);
            w.line(ci.use(LIST) + "<" + recSibling + "> _" + f.javaName()
                    + " = new " + ci.use(ARRAY_LIST) + "<>();");
            w.line("for (var _e : x.get" + cap + "List()) _" + f.javaName()
                    + ".add(" + recSibling + ".Interop.from(_e));");
            return;
        }
        if (f.isMessage()) {
            String recSibling = recordSiblingFqn(f);
            w.line(recSibling + " _" + f.javaName()
                    + " = x.has" + cap + "() ? " + recSibling
                    + ".Interop.from(x.get" + cap + "()) : null;");
            return;
        }
        if (f.kind() == FieldKind.ENUM) {
            w.line(recordSiblingFqn(f) + " _" + f.javaName() + " = " + fromExpr(f) + ";");
            return;
        }
        if (f.isRepeated()) {
            String elem = types.resolveElement(f);
            w.line(ci.use(LIST) + "<" + elem + "> _" + f.javaName()
                    + " = new " + ci.use(ARRAY_LIST) + "<>();");
            if ("bytes".equals(f.typeUrl())) {
                w.line("for (var _e : x.get" + cap + "List()) _" + f.javaName()
                        + ".add(_e.toByteArray());");
            } else {
                w.line("for (var _e : x.get" + cap + "List()) _" + f.javaName() + ".add(_e);");
            }
            return;
        }
        // Singular scalar / bytes (a oneof member's value record reuses fromExpr).
        String type = types.resolve(f);
        if ("bytes".equals(f.typeUrl())) {
            w.line("byte[] _" + f.javaName() + " = " + fromExpr(f) + ";");
        } else if (f.isNullable()) {
            w.line(type + " _" + f.javaName() + " = x.has" + cap + "() ? x.get" + cap + "() : null;");
        } else {
            w.line(type + " _" + f.javaName() + " = " + fromExpr(f) + ";");
        }
    }

    /**
     * The right-hand value expression that reads a singular scalar / bytes /
     * enum member off the protobuf-java reference {@code x}. Shared by a
     * top-level singular field's {@code from} local and a oneof member's
     * nested value record, so the family rule (scalar getter, bytes
     * {@code toByteArray}, enum {@code forNumber(getNumber())}) lives in one
     * place. The presence (proto3-{@code optional}) and message families are
     * resolved at the field level, not here.
     */
    private String fromExpr(Field f) {
        String cap = JavaNames.cap(f.javaName());
        if (f.kind() == FieldKind.ENUM) {
            return recordSiblingFqn(f) + ".forNumber(x.get" + cap + "().getNumber())";
        }
        if ("bytes".equals(f.typeUrl())) {
            return "x.get" + cap + "().toByteArray()";
        }
        return "x.get" + cap + "()";
    }

    /**
     * The single builder-set statement that writes a singular scalar / bytes /
     * enum family onto the protobuf-java reference builder {@code _b} from a
     * source expression {@code src}. Shared by a top-level field's {@code to} (src
     * {@code r.<field>()}) and a oneof member's {@code to} arm (src {@code v.value()}
     * for a scalar/enum value record). The presence (proto3-{@code optional}) and
     * bytes families are guarded/copied here; the message family is handled
     * separately (it always delegates to the sibling record's {@code Interop.to}).
     */
    private String toSet(Field f, String src, String refPkg) {
        String cap = JavaNames.cap(f.javaName());
        if (f.kind() == FieldKind.ENUM) {
            return "_b.set" + cap + "(" + refSiblingFqn(refPkg, f)
                    + ".forNumber(" + src + ".getNumber()));";
        }
        if ("bytes".equals(f.typeUrl())) {
            return "_b.set" + cap + "(" + ci.use(BYTE_STRING) + ".copyFrom(" + src + "));";
        }
        if (f.isNullable()) {
            return "if (" + src + " != null) _b.set" + cap + "(" + src + ");";
        }
        return "_b.set" + cap + "(" + src + ");";
    }

    // -- to(record) -> reference --------------------------------------------------

    private void emitTo(JavaWriter w, Message m, String refPkg, String refType) {
        String ref = refFqn(refPkg, refType);
        String rec = recordFqn(m.name());
        w.line("public static " + ref + " to(" + rec + " r) {");
        w.indent(w.indent() + 1);
        w.line("var _b = " + ref + ".newBuilder();");
        for (Field f : m.fields()) {
            toStmt(w, f, refPkg);
        }
        // Oneof components set the active case onto the reference builder; a null
        // component (an absent oneof) sets nothing, matching proto3's omission.
        for (Oneof o : m.oneofs()) {
            emitOneofTo(w, o, refPkg);
        }
        w.line("return _b.build();");
        w.indent(w.indent() - 1);
        w.line("}");
    }

    // -- oneof conversion: the case->value switch (from) and the pattern-matched
    // back-conversion (to) -------------------------------

    /**
     * {@code from} arm for a oneof: a {@code switch} on the protobuf-java
     * reference's {@code get<Cap(oneofName)>Case()} discriminator whose per-member
     * constants are unqualified in the expression. Each case maps the active field
     * to the nested value record (scalar / bytes / enum via {@link #fromExpr}) or to
     * the member record's own {@code Interop.from} (a message member). The {@code default}
     * arm is {@code null} (an absent oneof), so the local is always assigned; the
     * component is {@code @Nullable}, so that never trips NullAway on an unbox.
     */
    private void emitOneofFrom(JavaWriter w, Oneof o) {
        String iface = o.ifaceName();
        int li = w.indent() + 1;
        w.line("@Nullable " + iface + " _" + o.name() + " = switch (x.get"
                + iface + "Case()) {");
        w.indent(li);
        for (Field f : o.members()) {
            if (f.isMessage()) {
                w.line("case " + JavaNames.snakeUpper(f.name()) + " -> "
                        + recordSiblingFqn(f) + ".Interop.from(x.get"
                        + JavaNames.cap(f.javaName()) + "());");
                continue;
            }
            w.line("case " + JavaNames.snakeUpper(f.name()) + " -> new " + iface + "."
                    + JavaNames.cap(f.name()) + "Value(" + fromExpr(f) + ");");
        }
        w.line("default -> null;");
        w.indent(li - 1);
        w.line("};");
    }

    /**
     * {@code to} arm for a oneof: a pattern-matching {@code switch} over the sealed
     * component ({@link Oneof#ifaceName()}'s permitted subtypes — the per-member
     * scalar/enum value records and, by the K6 registry, the message-member records),
     * mapping each back to its proto field via {@code setX}; a message member
     * delegates to its own {@code Interop.to}. The arms enumerate every permitted
     * subtype, so the switch is exhaustive (no {@code default}). A leading {@code null}
     * arm (the absent oneof) sets nothing, matching proto3's omission.
     */
    private void emitOneofTo(JavaWriter w, Oneof o, String refPkg) {
        String iface = o.ifaceName();
        int li = w.indent() + 1;
        w.line("switch (r." + o.componentName() + "()) {");
        w.indent(li);
        w.line("case null -> {}");
        for (Field f : o.members()) {
            if (f.isMessage()) {
                String recSibling = recordSiblingFqn(f);
                w.line("case " + recSibling + " v -> _b.set" + JavaNames.cap(f.javaName())
                        + "(" + recSibling + ".Interop.to(v));");
                continue;
            }
            w.line("case " + iface + "." + JavaNames.cap(f.name()) + "Value v -> "
                    + toSet(f, "v.value()", refPkg));
        }
        w.indent(li - 1);
        w.line("}");
    }

    private void toStmt(JavaWriter w, Field f, String refPkg) {
        String cap = JavaNames.cap(f.javaName());
        if (f.isMap()) {
            w.line("for (var _e : r." + f.javaName() + "().entrySet()) _b.put" + cap + "("
                    + mapKeyTo(f) + ", " + mapValueTo(f) + ");");
            return;
        }
        if (f.isMessage() && f.isRepeated()) {
            String recSibling = recordSiblingFqn(f);
            w.line("for (var _e : r." + f.javaName() + "()) _b.add" + cap + "("
                    + recSibling + ".Interop.to(_e));");
            return;
        }
        if (f.isMessage()) {
            String recSibling = recordSiblingFqn(f);
            w.line("if (r." + f.javaName() + "() != null) {");
            w.indent(w.indent() + 1);
            w.line("_b.set" + cap + "(" + recSibling + ".Interop.to(r." + f.javaName() + "()));");
            w.indent(w.indent() - 1);
            w.line("}");
            return;
        }
        if (f.isRepeated()) {
            // protobuf-java sets a repeated field with addAllX(List<T>) for
            // scalars / enums; byte[] repeated elements must each be copied to ByteString.
            if ("bytes".equals(f.typeUrl())) {
                w.line("for (byte[] _e : r." + f.javaName() + "()) _b.add" + cap + "("
                        + ci.use(BYTE_STRING) + ".copyFrom(_e));");
            } else {
                w.line("_b.addAll" + cap + "(r." + f.javaName() + "());");
            }
            return;
        }
        // Singular scalar / bytes / enum (a oneof member's `to` reuses toSet, src v.value()).
        w.line(toSet(f, "r." + f.javaName() + "()", refPkg));
    }

    // -- helpers ------------------------------------------------------------------

    /** The record being converted is the enclosing type of this nested
     *  {@code Interop}, so it resolves by its own short name in scope -- no FQN. */
    private String recordFqn(String name) {
        return name;
    }

    private String refFqn(String refPkg, String refType) {
        return refPkg.isEmpty() ? refType : refPkg + "." + refType;
    }

    /** A message / enum field's sibling record lives in the record's own package --
     *  all top-level records in a .proto share one record package, and this record
     *  is emitted in that same package -- so the sibling resolves by its short name
     *  ({@code cap(shortName)}); no FQN or import is needed. */
    private String recordSiblingFqn(Field f) {
        return JavaNames.cap(lastSegment(f.typeUrl()));
    }

    private String refSiblingFqn(String refPkg, Field f) {
        return refFqn(refPkg, JavaNames.cap(lastSegment(f.typeUrl())));
    }

    private static String lastSegment(String fqn) {
        // f.typeUrl() is non-null only for MESSAGE / ENUM fields; a null would
        // mean "no type", which this converter never hits.
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    // Map key / value exprs. For scalar key / value families entrySet gives the
    // Java boxed key / value, which maps cleanly. byte[] and message / enum
    // values would need a conversion -- out of scope for this family
    // (matches the code).
    private String mapKeyFrom(Field f) {
        return "bytes".equals(f.mapKey()) ? "_e.getKey().toByteArray()" : "_e.getKey()";
    }

    private String mapValueFrom(Field f) {
        String t = f.mapValue();
        if (t == null) return "_e.getValue()";
        if ("bytes".equals(t)) return "_e.getValue().toByteArray()";
        if ("message".equals(t) || "enum".equals(t)) {
            throw new UnsupportedOperationException(
                    "map values of kind '" + t + "' are out of scope for Layer B Interop");
        }
        return "_e.getValue()";
    }

    private String mapKeyTo(Field f) {
        return "bytes".equals(f.mapKey())
                ? ci.use(BYTE_STRING) + ".copyFrom(_e.getKey())"
                : "_e.getKey()";
    }

    private String mapValueTo(Field f) {
        String t = f.mapValue();
        if (t == null) return "_e.getValue()";
        if ("bytes".equals(t)) return ci.use(BYTE_STRING) + ".copyFrom(_e.getValue())";
        if ("message".equals(t) || "enum".equals(t)) {
            throw new UnsupportedOperationException(
                    "map values of kind '" + t + "' are out of scope for Layer B Interop");
        }
        return "_e.getValue()";
    }
}
