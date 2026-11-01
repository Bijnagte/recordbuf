package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.types.TypeResolver;
import bijnagte.recordbuf.plugin.types.TypeResolver.WireScalar;

/**
 * Emits the static {@code Codec} nested class for a {@link Message}. The
 * generated codec uses protobuf-java's {@code CodedOutputStream}/
 * {@code CodedInputStream}, so users need protobuf-java on their classpath
 * at runtime.
 *
 * <p>Wire encoding is hand-rolled so the codec is independent of protobuf's
 * reflection layer — it doesn't need a {@code MessageLite} impl per record.
 * For nested messages we encode the child bytes first, then splice them in
 * with {@code writeMessageTag + writeRawBytes}.
 *
 * <p>Nullability uses {@code @org.jspecify.annotations.Nullable} (see
 * {@code Field.isNullable()}): a present-able field is {@code null} when
 * absent and is skipped on the wire; it is never wrapped in {@code Optional}.
 */
public final class CodecGenerator {

    private final TypeResolver types;

    public CodecGenerator(TypeResolver types) {
        this.types = types;
    }

    /** Emit {@code public static final class Codec { ... }} at current indent. */
    public void writeCodec(JavaWriter w, Message m) {
        w.line();
        w.line("public static final class Codec {");
        int prev = w.indent();
        w.indent(prev + 1);
        w.line("private Codec() {}");
        w.line();

        // --- encode ---
        w.line("public static byte[] encode(" + m.name() + " v) throws java.io.IOException {");
        w.indent(w.indent() + 1);
        emitEncode(w, m);
        w.indent(prev + 1);
        w.line("}");
        w.line();

        // --- decode ---
        w.line("public static " + m.name() + " decode(byte[] inBytes) throws java.io.IOException {");
        w.indent(w.indent() + 1);
        emitDecode(w, m);
        w.indent(prev + 1);
        w.line("}");
        w.line();
        w.indent(prev);
        w.line("}");
    }

    // --- encode ---

    private void emitEncode(JavaWriter w, Message m) {
        w.line("java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();");
        w.line("com.google.protobuf.CodedOutputStream out = com.google.protobuf.CodedOutputStream.newInstance(baos);");
        for (Field f : m.fields()) {
            emitEncodeField(w, m, f);
        }
        w.line("out.flush();");
        w.line("return baos.toByteArray();");
    }

    private void emitEncodeField(JavaWriter w, Message m, Field f) {
        String getter = "v." + f.javaName() + "()";
        int num = f.number();

        if (f.isRepeated()) {
            switch (f.kind()) {
                case SCALAR  -> emitRepeatedScalar(w, f, num, getter);
                case ENUM    -> emitRepeatedEnum(w, f, num, getter);
                case MESSAGE -> emitRepeatedMessage(w, f, num, getter);
                default      -> w.line("// unsupported repeated kind: " + f.kind());
            }
            return;
        }
        if (f.isMap()) {
            w.line("// TODO(v1): map encoding for " + f.javaName());
            return;
        }
        boolean nullable = f.isNullable();
        switch (f.kind()) {
            case SCALAR  -> emitScalar(w, f, num, getter, nullable);
            case ENUM    -> emitEnum(w, f, num, getter, nullable);
            case MESSAGE -> emitMessage(w, f, num, getter, nullable);
            default      -> { }
        }
    }

    private void emitScalar(JavaWriter w, Field f, int num, String getter, boolean nullable) {
        WireScalar wire = types.wireScalar(f);
        String call = encodeCall(wire);
        if (nullable) {
            // Present-able scalars are boxed; skip the field entirely when null
            // (absent on the wire). Autounboxing feeds the primitive write op.
            w.line("if (" + getter + " != null) out." + call + "(" + num + ", " + getter + ");");
            return;
        }
        switch (wire) {
            case STRING ->
                w.line("if (!" + getter + ".isEmpty()) out." + call + "(" + num + ", " + getter + ");");
            case BYTES  ->
                w.line("if (" + getter + ".length != 0) out." + call + "(" + num + ", " + getter + ");");
            default ->
                w.line("if (" + getter + " != " + defaultValueFor(wire) + ") out." + call + "(" + num + ", " + getter + ");");
        }
    }

    private void emitEnum(JavaWriter w, Field f, int num, String getter, boolean nullable) {
        if (nullable) {
            w.line("if (" + getter + " != null) out.writeEnum(" + num + ", " + getter + ".getNumber());");
            return;
        }
        w.line("if (" + getter + " != null && " + getter + ".getNumber() != 0) out.writeEnum(" + num + ", " + getter + ".getNumber());");
    }

    private void emitMessage(JavaWriter w, Field f, int num, String getter, boolean nullable) {
        // A singular message is always present-able (@Nullable): skip it on the
        // wire when null (proto3 absent). Otherwise encode the child bytes via
        // its own Codec and frame them as one length-delimited byte array
        // (tag + length + raw bytes).
        String nestedCodec = codecFor(f.typeUrl());
        w.line("if (" + getter + " != null) {");
        int ind = w.indent() + 1;
        w.indent(ind);
        w.line("out.writeByteArray(" + num + ", " + nestedCodec + ".encode(" + getter + "));");
        w.indent(ind - 1);
        w.line("}");
    }

    private void emitRepeatedScalar(JavaWriter w, Field f, int num, String getter) {
        WireScalar wire = types.wireScalar(f);
        String op = packedNoTag(wire);
        // proto3 packs repeated scalars as ONE length-delimited group: tag(num, LENGTH_DELIMITED)
        // + a varint length + a tight sequence of the no-tag values, not one tag per element. The
        // length prefix is data-dependent for the varint/zigzag families, so we first buffer the
        // tight value sequence into a fresh CodedOutputStream, then frame its exact bytes.
        w.line("if (!" + getter + ".isEmpty()) {");
        int ind = w.indent() + 1;
        w.indent(ind);
        w.line("java.io.ByteArrayOutputStream _body = new java.io.ByteArrayOutputStream();");
        w.line("com.google.protobuf.CodedOutputStream _o = com.google.protobuf.CodedOutputStream.newInstance(_body);");
        w.line("for (var _x : " + getter + ") _o." + op + "(_x);");
        w.line("_o.flush();");
        // Frame the buffered sequence as one length-delimited group. writeByteArray writes
        // tag + varint length + raw bytes -- the exact framing protobuf-java's packed encode/use.
        w.line("out.writeByteArray(" + num + ", _body.toByteArray());");
        w.indent(ind - 1);
        w.line("}");
    }
    private void emitRepeatedEnum(JavaWriter w, Field f, int num, String getter) {
        w.line("for (var _e : " + getter + ") out.writeEnum(" + num + ", _e.getNumber());");
    }
    private void emitRepeatedMessage(JavaWriter w, Field f, int num, String getter) {
        // Each element gets its own length-delimited byte array.
        String codecRef = codecFor(f.typeUrl());
        w.line("for (var _m : " + getter + ") out.writeByteArray(" + num + ", " + codecRef + ".encode(_m));");
    }

    // --- decode ---

    private void emitDecode(JavaWriter w, Message m) {
        for (Field f : m.fields()) {
            w.line(initDecl(f));
        }
        w.line();
        w.line("com.google.protobuf.CodedInputStream in = com.google.protobuf.CodedInputStream.newInstance(inBytes);");
        w.line("while (!in.isAtEnd()) {");
        int outer = w.indent() + 1;
        w.indent(outer);
        w.line("int tag = in.readTag();");
        w.line("if (tag == 0) break;");
        w.line("int fieldNum = com.google.protobuf.WireFormat.getTagFieldNumber(tag);");
        w.line("switch (fieldNum) {");
        int inner = outer + 1;
        w.indent(inner);
        for (Field f : m.fields()) {
            emitDecodeCase(w, m, f);
        }
        w.line("default -> in.skipField(tag);");
        w.indent(outer);
        w.line("}");
        w.line("}");
        w.line();
        w.line("return new " + m.name() + "(");
        for (int i = 0; i < m.fields().size(); i++) {
            Field f = m.fields().get(i);
            String sep = (i == m.fields().size() - 1) ? "" : ",";
            w.line("    " + f.javaName() + sep);
        }
        w.line(");");
    }

    private void emitDecodeCase(JavaWriter w, Message m, Field f) {
        int num = f.number();
        w.line("case " + num + " -> {");
        int ind = w.indent() + 1;
        w.indent(ind);
        if (f.isMap()) {
            w.line("// TODO(v1): map decode for " + f.javaName());
        } else if (f.isRepeated()) {
            emitRepeatedDecode(w, m, f, num);
        } else {
            emitSingularDecode(w, m, f, num);
        }
        w.indent(ind - 1);
        w.line("}");
    }

    private void emitSingularDecode(JavaWriter w, Message m, Field f, int num) {
        switch (f.kind()) {
            case SCALAR -> {
                w.line(f.javaName() + " = " + decodeCall(types.wireScalar(f)) + ";");
            }
            case ENUM -> {
                String enumName = lastSeg(f.typeUrl());
                w.line(f.javaName() + " = " + enumName + ".forNumber((int) in.readEnum());");
            }
            case MESSAGE -> {
                // The tag was already consumed by readTag(); read the remaining
                // length-delimited byte array and hand it to the child Codec.
                String codecRef = codecFor(f.typeUrl());
                w.line(f.javaName() + " = " + codecRef + ".decode(in.readByteArray());");
            }
            default -> { }
        }
    }

    private void emitRepeatedDecode(JavaWriter w, Message m, Field f, int num) {
        switch (f.kind()) {
            case SCALAR -> {
                // Packed: the length-delimited tag is already consumed; read its varint length,
                // push a limit, and pull no-tag values until the sub-buffer is drained. An empty
                // group (length 0) drains nothing, leaving the list empty.
                String read = decodeCall(types.wireScalar(f));
                w.line("int _lim = in.pushLimit(in.readRawVarint32());");
                w.line("while (in.getBytesUntilLimit() > 0) " + f.javaName() + ".add(" + read + ");");
                w.line("in.popLimit(_lim);");
            }
            case ENUM -> {
                String enumName = lastSeg(f.typeUrl());
                w.line(f.javaName() + ".add(" + enumName + ".forNumber((int) in.readEnum()));");
            }
            case MESSAGE -> {
                String codecRef = codecFor(f.typeUrl());
                w.line(f.javaName() + ".add(" + codecRef + ".decode(in.readByteArray()));");
            }
            default -> { }
        }
    }

    // --- helpers ---

    /** The local-declaration line for a decoded field. */
    private String initDecl(Field f) {
        // A present-able field is declared with its boxed/reference type and
        // starts null; every other field keeps a default value.
        if (f.isNullable()) return types.resolve(f) + " " + f.javaName() + " = null;";
        // A repeated field starts as an explicit List<Element> (diamond inference
        // on `var` would leave it as List<Object>); the nested type is the
        // element type from resolve()'s List<Element> head.
        if (f.isRepeated()) return types.resolve(f) + " " + f.javaName() + " = new java.util.ArrayList<>();";
        return "var " + f.javaName() + " = " + initialValue(f) + ";";
    }

    private String encodeCall(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32              -> "writeInt32";
            case SINT_32                    -> "writeSInt32";
            case FIXED32                    -> "writeFixed32";
            case INT64, UINT64              -> "writeInt64";
            case SINT_64                    -> "writeSInt64";
            case FIXED64                    -> "writeFixed64";
            case FLOAT                      -> "writeFloat";
            case DOUBLE                  -> "writeDouble";
            case BOOL                    -> "writeBool";
            case STRING                  -> "writeString";
            case BYTES                   -> "writeByteArray"; // byte[] -> 2-arg byte-array op
        };
    }

    /** The no-tag primitive writer used to pack a tight value sequence, mirroring
     * {@link #encodeCall} but with the tag handled by the outer framing step. */
    private String packedNoTag(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32              -> "writeInt32NoTag";
            case SINT_32                    -> "writeSInt32NoTag";
            case FIXED32                    -> "writeFixed32NoTag";
            case INT64, UINT64              -> "writeInt64NoTag";
            case SINT_64                    -> "writeSInt64NoTag";
            case FIXED64                    -> "writeFixed64NoTag";
            case FLOAT                      -> "writeFloatNoTag";
            case DOUBLE                  -> "writeDoubleNoTag";
            case BOOL                    -> "writeBoolNoTag";
            default                         -> throw new IllegalStateException(wire + " is not a packed numeric type");
        };
    }

    private String decodeCall(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32              -> "in.readInt32()";
            case SINT_32                    -> "in.readSInt32()";
            case FIXED32                    -> "in.readFixed32()";
            case INT64, UINT64              -> "in.readInt64()";
            case SINT_64                    -> "in.readSInt64()";
            case FIXED64                    -> "in.readFixed64()";
            case FLOAT                      -> "in.readFloat()";
            case DOUBLE                  -> "in.readDouble()";
            case BOOL                    -> "in.readBool()";
            case STRING                  -> "in.readString()";
            case BYTES                   -> "in.readByteArray()";
        };
    }

    private String defaultValueFor(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32, SINT_32, FIXED32              -> "0";
            case INT64, UINT64, SINT_64, FIXED64              -> "0L";
            case FLOAT                                                 -> "0.0f";
            case DOUBLE                                                -> "0.0";
            case BOOL                                                  -> "false";
            case STRING, BYTES                                         -> null; // handled specially
        };
    }

    private String initialValue(Field f) {
        if (f.isRepeated()) return "new java.util.ArrayList<>()";
        if (f.isMap())      return "java.util.Map.of()";
        return switch (f.kind()) {
            case SCALAR -> {
                WireScalar wire = types.wireScalar(f);
                yield switch (wire) {
                    case INT32, UINT32, SINT_32, FIXED32     -> "0";
                    case INT64, UINT64, SINT_64, FIXED64     -> "0L";
                    case FLOAT                                 -> "0.0f";
                    case DOUBLE                                        -> "0.0";
                    case BOOL                                          -> "false";
                    case STRING                                        -> "\"\"";
                    case BYTES                                         -> "new byte[0]";
                };
            }
            case ENUM, MESSAGE -> "null";
            default            -> "null";
        };
    }

    private static String codecFor(String protoFqn) {
        return lastSeg(protoFqn) + ".Codec";
    }
    private static String lastSeg(String fqn) {
        if (fqn == null) return null;
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
