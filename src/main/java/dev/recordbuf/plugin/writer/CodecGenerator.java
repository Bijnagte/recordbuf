package dev.recordbuf.plugin.writer;

import dev.recordbuf.plugin.model.Field;
import dev.recordbuf.plugin.model.Message;
import dev.recordbuf.plugin.types.TypeResolver;
import dev.recordbuf.plugin.types.TypeResolver.WireScalar;

/**
 * Emits the static {@code Codec} nested class for a {@link Message}. The
 * generated codec uses protobuf-java's {@code CodedOutputStream}/
 * {@code CodedInputStream}, so users need protobuf-java on their classpath
 * at runtime.
 *
 * <p>Wire encoding is hand-rolled so the codec is independent of protobuf's
 * reflection layer — it doesn't need a {@code MessageLite} impl per record.
 * For nested messages we encode the child bytes first, then
 * {@code writeMessageTag + writeRawBytes} to splice them in.
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
        w.line("public static byte[] encode(" + m.name() + " v) {");
        w.indent(w.indent() + 1);
        emitEncode(w, m);
        w.indent(prev + 1);
        w.line("}");
        w.line();

        // --- decode ---
        w.line("public static " + m.name() + " decode(byte[] in) {");
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
        String acc = "v." + f.javaName() + "()";
        int num = f.number();

        if (f.isRepeated()) {
            switch (f.kind()) {
                case SCALAR  -> emitRepeatedScalar(w, f, num, acc);
                case ENUM    -> emitRepeatedEnum(w, f, num, acc);
                case MESSAGE -> emitRepeatedMessage(w, f, num, acc);
                default      -> w.line("// unsupported repeated kind: " + f.kind());
            }
            return;
        }
        if (f.isMap()) {
            w.line("// TODO(v1): map encoding for " + f.javaName());
            return;
        }
        boolean optional = f.isOptionalWrapped();
        switch (f.kind()) {
            case SCALAR  -> emitScalar(w, f, num, acc, optional);
            case ENUM    -> emitEnum(w, f, num, acc, optional);
            case MESSAGE -> emitMessage(w, f, num, acc, optional);
            default      -> { }
        }
    }

    private void emitScalar(JavaWriter w, Field f, int num, String getter, boolean optional) {
        WireScalar wire = types.wireScalar(f);
        String call = encodeCall(wire) + "(" + getter + ")";
        if (optional) {
            w.line("if (" + getter + ".isPresent()) out." + call + ";");
            return;
        }
        switch (wire) {
            case STRING ->
                w.line("if (!" + getter + ".isEmpty()) out." + encodeCall(wire) + "(" + getter + ");");
            case BYTES  ->
                w.line("if (" + getter + ".length != 0) out." + encodeCall(wire) + "(" + getter + ");");
            default ->
                w.line("if (" + getter + " != " + defaultValueFor(wire) + ") out." + encodeCall(wire) + "(" + getter + ");");
        }
    }

    private void emitEnum(JavaWriter w, Field f, int num, String getter, boolean optional) {
        if (optional) {
            w.line("if (" + getter + ".isPresent()) out.writeEnum(" + num + ", " + getter + ".get().getNumber());");
            return;
        }
        w.line("if (" + getter + " != null && " + getter + ".getNumber() != 0) out.writeEnum(" + num + ", " + getter + ".getNumber());");
    }

    private void emitMessage(JavaWriter w, Field f, int num, String getter, boolean optional) {
        String nestedCodec = codecFor(f.typeUrl());
        String localName = nestedTypeVar(f);
        if (optional) {
            w.line("if (" + getter + ".isPresent()) {");
            w.indent(w.indent() + 1);
            w.line(localName + "_b = " + nestedCodec + ".encode(" + getter + ".get());");
            w.line("out.writeMessageTag(" + num + ", " + localName + "_b.length);");
            w.line("out.writeRawBytes(" + localName + "_b);");
            w.indent(w.indent() - 1);
            w.line("}");
            return;
        }
        w.line("if (" + getter + " != null) {");
        w.indent(w.indent() + 1);
        w.line(localName + "_b = " + nestedCodec + ".encode(" + getter + ");");
        w.line("out.writeMessageTag(" + num + ", " + localName + "_b.length);");
        w.line("out.writeRawBytes(" + localName + "_b);");
        w.indent(w.indent() - 1);
        w.line("}");
    }

    private void emitRepeatedScalar(JavaWriter w, Field f, int num, String getter) {
        String elem = types.resolveElement(f);
        WireScalar wire = types.wireScalar(f);
        w.line("for (" + elem + " _x : " + getter + ") out." + encodeCall(wire) + "(_x);");
    }
    private void emitRepeatedEnum(JavaWriter w, Field f, int num, String getter) {
        w.line("for (var _e : " + getter + ") out.writeEnum(" + num + ", _e.getNumber());");
    }
    private void emitRepeatedMessage(JavaWriter w, Field f, int num, String getter) {
        String codecRef = codecFor(f.typeUrl());
        String localVar = "_msg";
        w.line("for (var " + localVar + " : " + getter + ") {");
        w.indent(w.indent() + 1);
        w.line("byte[] " + localVar + "_b = " + codecRef + ".encode(" + localVar + ");");
        w.line("out.writeMessageTag(" + num + ", " + localVar + "_b.length);");
        w.line("out.writeRawBytes(" + localVar + "_b);");
        w.indent(w.indent() - 1);
        w.line("}");
    }

    // --- decode ---

    private void emitDecode(JavaWriter w, Message m) {
        for (Field f : m.fields()) {
            w.line("var " + f.javaName() + " = " + initialValue(f) + ";");
        }
        w.line();
        w.line("com.google.protobuf.CodedInputStream in = com.google.protobuf.CodedInputStream.newInstance(in);");
        w.line("int lim = in.pushLimit(in.getBufferedByteCount());");
        w.line("com.google.protobuf.UnsafeUtil.checkAccess();"); // no-op, keep import alive
        w.line("while (!in.isAtEnd()) {");
        w.indent(w.indent() + 1);
        w.line("int tag = in.readTag();");
        w.line("if (tag == 0) break;");
        w.line("int fieldNum = com.google.protobuf.WireFormat.getTagFieldNumber(tag);");
        w.line("switch (fieldNum) {");
        w.indent(w.indent() + 1);
        for (Field f : m.fields()) {
            emitDecodeCase(w, m, f);
        }
        w.line("default: in.skipField(tag);");
        w.indent(w.indent() - 1);
        w.line("}");
        w.line("}");
        w.line("in.popLimit(lim);");
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
        w.indent(w.indent() + 1);
        if (f.isMap()) {
            w.line("// TODO(v1): map decode for " + f.javaName());
        } else if (f.isRepeated()) {
            switch (f.kind()) {
                case SCALAR -> {
                    WireScalar wire = types.wireScalar(f);
                    w.line(f.javaName() + ".add(" + decodeCall(wire) + ");");
                }
                case ENUM -> {
                    String enumName = lastSeg(f.typeUrl());
                    w.line(f.javaName() + ".add(" + enumName + ".forNumber((int) in.readEnum()));");
                }
                case MESSAGE -> {
                    String codecRef = codecFor(f.typeUrl());
                    w.line(f.javaName() + ".add(" + codecRef + ".decode(in.readMessage(" + num + ")));");
                }
                default -> { }
            }
        } else {
            boolean optional = f.isOptionalWrapped();
            switch (f.kind()) {
                case SCALAR -> {
                    WireScalar wire = types.wireScalar(f);
                    String read = decodeCall(wire);
                    if (optional) w.line(f.javaName() + " = java.util.Optional.of(" + read + ");");
                    else          w.line(f.javaName() + " = " + read + ";");
                }
                case ENUM -> {
                    String enumName = lastSeg(f.typeUrl());
                    String read = enumName + ".forNumber((int) in.readEnum())";
                    if (optional) w.line(f.javaName() + " = java.util.Optional.of(" + read + ");");
                    else          w.line(f.javaName() + " = " + read + ";");
                }
                case MESSAGE -> {
                    String codecRef = codecFor(f.typeUrl());
                    String read = codecRef + ".decode(in.readMessage(" + num + "))";
                    if (optional) w.line(f.javaName() + " = java.util.Optional.of(" + read + ");");
                    else          w.line(f.javaName() + " = " + read + ";");
                }
                default -> { }
            }
        }
        w.indent(w.indent() - 1);
        w.line("}");
    }

    // --- helpers ---

    private String encodeCall(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32, FIXED32  -> "writeInt32";
            case SINT32, SINT_32         -> "writeSInt32";
            case INT64, UINT64, FIXED64  -> "writeInt64";
            case SINT64, SINT_64         -> "writeSInt64";
            case FLOAT                   -> "writeFloat";
            case DOUBLE                  -> "writeDouble";
            case BOOL                    -> "writeBool";
            case STRING                  -> "writeString";
            case BYTES                   -> "writeBytes";
        };
    }

    private String decodeCall(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32, FIXED32  -> "in.readInt32()";
            case SINT32, SINT_32         -> "in.readSInt32()";
            case INT64, UINT64, FIXED64  -> "in.readInt64()";
            case SINT64, SINT_64         -> "in.readSInt64()";
            case FLOAT                   -> "in.readFloat()";
            case DOUBLE                  -> "in.readDouble()";
            case BOOL                    -> "in.readBool()";
            case STRING                  -> "in.readString()";
            case BYTES                   -> "in.readBytes()";
        };
    }

    private String defaultValueFor(WireScalar wire) {
        return switch (wire) {
            case INT32, UINT32, SINT32, SINT_32, FIXED32              -> "0";
            case INT64, UINT64, SINT64, SINT_64, FIXED64              -> "0L";
            case FLOAT                                                 -> "0.0f";
            case DOUBLE                                                -> "0.0";
            case BOOL                                                  -> "false";
            case STRING, BYTES                                         -> null; // handled specially
        };
    }

    private String initialValue(Field f) {
        if (f.isRepeated())       return "new java.util.ArrayList<>();";
        if (f.isOptionalWrapped()) return "java.util.Optional.empty()";
        if (f.isMap())            return "java.util.Map.of()";
        return switch (f.kind()) {
            case SCALAR -> {
                WireScalar wire = types.wireScalar(f);
                yield switch (wire) {
                    case INT32, UINT32, SINT32, SINT_32, FIXED32     -> "0";
                    case INT64, UINT64, SINT64, SINT_64, FIXED64     -> "0L";
                    case FLOAT                                         -> "0.0f";
                    case DOUBLE                                        -> "0.0";
                    case BOOL                                          -> "false";
                    case STRING                                        -> "\"\"";
                    case BYTES                                         -> "byte[0]";
                };
            }
            case ENUM, MESSAGE -> "null";
            default            -> "null";
        };
    }

    private static String codecFor(String protoFqn) {
        return lastSeg(protoFqn) + ".Codec";
    }
    private static String nestedTypeVar(Field f) {
        return f.javaName() + "_bytes";
    }
    private static String lastSeg(String fqn) {
        if (fqn == null) return null;
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
