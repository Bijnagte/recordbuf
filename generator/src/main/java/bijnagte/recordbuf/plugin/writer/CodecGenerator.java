package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeResolver;
import bijnagte.recordbuf.plugin.types.TypeResolver.WireScalar;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import java.util.Comparator;

/**
 * Emits the static {@code Codec} nested class for a {@link Message}. The generated codec uses
 * protobuf-java's {@code CodedOutputStream}/ {@code CodedInputStream}, so users need protobuf-java
 * on their classpath at runtime.
 *
 * <p>Wire encoding is hand-rolled so the codec is independent of protobuf's reflection layer — it
 * doesn't need a {@code MessageLite} impl per record. For nested messages we encode the child bytes
 * first, then splice them in with {@code writeMessageTag + writeRawBytes}.
 *
 * <p>Nullability uses {@code @org.jspecify.annotations.Nullable} (see {@code Field.isNullable()}):
 * a present-able field is {@code null} when absent and is skipped on the wire; it is never wrapped
 * in {@code Optional}.
 *
 * <p>Cross-file types and the runtime helpers (CodecDefaults/Decoder/Encoder/Wire), {@code
 * java.io.IOException}, and {@code java.util.*} each resolve to their short name via the per-file
 * {@link TypeImports} collector, which adds one import each, instead of spelling out
 * fully-qualified names inline. The per-message dispatch stays generated; only the mechanical
 * framing lives in bijnagte.recordbuf.runtime.
 */
public final class CodecGenerator {

  // The runtime package a generated codec calls into. Resolved to short imported
  // names per-file via TypeImports (CodecDefaults/Decoder/Encoder/Wire).
  private static final String RT = "bijnagte.recordbuf.runtime";

  private final TypeResolver types;
  private final TypeImports ci;
  private final String sourceProto;

  public CodecGenerator(TypeResolver types, TypeImports ci, String sourceProto) {
    this.types = types;
    this.ci = ci;
    this.sourceProto = sourceProto;
  }

  /** Emit {@code public static final class Codec { ... }} at current indent. */
  public void writeCodec(JavaWriter w, Message m) {
    // A proto3 map<K,V> is a repeated 2-field entry message (key=1, value=2).
    // The record component is a java.util.Map<K,V>; each entry round-trips via
    // its own nested record + Codec, framed as a length-delimited group exactly
    // like a repeated nested message. Emit one entry record per map field.
    for (Field f : m.fields()) {
      if (f.isMap()) emitMapEntry(w, f);
    }
    // One nested sealed interface per real oneof group is emitted by
    // CodeGenerator.recordAndCodec at the record-body level (a peer of the
    // Builder and Codec, not inside the Codec). This method only emits the
    // Codec class itself. The codec's encode/decode dispatch on m.oneofs()
    // at the call site in emitEncode/emitDecode.

    w.line();
    w.javadoc(codecDoc(m));
    w.line("public static final class Codec {");
    int prev = w.indent();
    w.indent(prev + 1);
    w.line("private Codec() {}");
    w.line();

    // --- encode ---
    w.line(
        "public static byte[] encode("
            + m.name()
            + " v) throws "
            + ci.use("java.io.IOException")
            + " {");
    w.indent(w.indent() + 1);
    emitEncode(w, m);
    w.indent(prev + 1);
    w.line("}");
    w.line();

    // --- decode ---
    w.line(
        "public static "
            + m.name()
            + " decode(byte[] inBytes) throws "
            + ci.use("java.io.IOException")
            + " {");
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
    w.line("var enc = " + ci.use(RT + ".Encoder") + ".start();");
    w.line("var out = enc.out();");
    for (Field f : m.fields().stream().sorted(Comparator.comparingInt(Field::number)).toList()) {
      emitEncodeField(w, m, f);
    }
    // Oneof members: one wire field each, written only when the oneof's interface
    // component is set (the message subtype or the shared Scalar wrapper). The
    // wrapper carries the member's wire field number so the codec can dispatch on it.
    for (Oneof o : m.oneofs()) {
      emitOneofEncode(w, m, o);
    }
    // Re-emit the fields captured on the decode as not modeled by this schema,
    // in the wire order they were seen, so a record read through an older schema
    // survives a round-trip byte-for-byte. A common empty carrier writes nothing,
    // so the guard keeps the empty path from touching the stream at all.
    w.line(
        "if (!v.unknownFields().isEmpty()) "
            + ci.use(RT + ".Wire")
            + ".writeUnknown(out, v.unknownFields());");
    w.line("return enc.finish();");
  }

  // --- oneof members ---

  /**
   * Encode one real {@code oneof{...}} group into the stream. The record carries one
   * {@code @Nullable} component of the oneof's nested sealed interface type; a null component means
   * the oneof was absent off the wire, so nothing is written. A non-null component is either a
   * message subtype (the message member's record, permitted by the interface via the K6 registry)
   * or the shared {@code Scalar} wrapper carrying a scalar/enum value plus a wire field number tag;
   * the latter dispatches on the tag to pick the wire primitive.
   */
  private void emitOneofEncode(JavaWriter w, Message m, Oneof o) {
    String iface = o.ifaceName();
    String cmp = o.componentName();
    String getter = "v." + cmp + "()";
    // Exhaustive pattern-matching switch on the sealed oneof interface. Java 21+
    // forbids a default case: the 'permits' clause plus 'case null ->' is
    // exhaustive by construction. Each case encodes its member via the write
    // primitive that matches the member's wire type.
    w.line("switch (" + getter + ") {");
    int li = w.indent() + 1;
    w.indent(li);
    w.line("case null -> {}");
    for (Field f : o.members()) {
      if (f.isMessage()) {
        String elem = ci.use(types.resolveElement(f));
        String bnd = String.valueOf(f.name().charAt(0));
        w.line(
            "case "
                + elem
                + " "
                + bnd
                + " -> out.writeByteArray("
                + f.number()
                + ", "
                + elem
                + ".Codec.encode("
                + bnd
                + "));");
      } else {
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        String bnd = String.valueOf(f.name().charAt(0));
        w.line("case " + recName + " " + bnd + " -> " + oneofScalarEncode(o, f, bnd, "out"));
      }
    }
    w.indent(li - 1);
    w.line("}");
  }

  /**
   * The single encode statement for one non-message oneof member, dispatched from the per-member
   * record binding's {@code value()} accessor. The concrete proto field for that member is looked
   * up on the oneof's raw descriptor so the writer primitive matches the member's wire type. With
   * per-member typed records the accessor already returns the correct Java type (e.g. {@code
   * String}, {@code Integer}), so no cast from {@code Object} is needed. Returns the full statement
   * (including its terminating semicolon) for an arrow.
   */
  private String oneofScalarEncode(Oneof o, Field f, String scalarBinding, String out) {
    var raw = rawMemberField(o, f.number());
    switch (raw.getType()) {
      case TYPE_STRING -> {
        return out + ".writeString(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_BYTES -> {
        return out + ".writeByteArray(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_BOOL -> {
        return out + ".writeBool(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_DOUBLE -> {
        return out + ".writeDouble(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_FLOAT -> {
        return out + ".writeFloat(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_INT32, TYPE_SINT32, TYPE_SFIXED32, TYPE_UINT32, TYPE_FIXED32 -> {
        return out + ".writeInt32(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_INT64, TYPE_SINT64, TYPE_SFIXED64, TYPE_UINT64, TYPE_FIXED64 -> {
        return out + ".writeInt64(" + f.number() + ", " + scalarBinding + ".value());";
      }
      case TYPE_ENUM -> {
        return out + ".writeEnum(" + f.number() + ", " + scalarBinding + ".value().getNumber());";
      }
      case TYPE_MESSAGE, TYPE_GROUP -> {
        // Message members are routed through the identity-subtype path in
        // emitOneofEncode, not through this method; if they ever land
        // here it is a generator logic bug, so fail loudly.
        throw new IllegalStateException(
            "oneof scalar encode: unexpected TYPE_MESSAGE member tag " + f.number());
      }
      default -> throw new IllegalStateException("unsupported oneof scalar type: " + raw.getType());
    }
  }

  private void emitEncodeField(JavaWriter w, Message m, Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();

    if (f.isRepeated()) {
      switch (f.kind()) {
        case SCALAR -> emitRepeatedScalar(w, f, num, getter);
        case ENUM -> emitRepeatedEnum(w, f, num, getter);
        case MESSAGE -> emitRepeatedMessage(w, f, num, getter);
        default -> w.line("// unsupported repeated kind: " + f.kind());
      }
      return;
    }
    if (f.isMap()) {
      emitMapField(w, m, f);
      return;
    }
    boolean nullable = f.isNullable();
    switch (f.kind()) {
      case SCALAR -> emitScalar(w, f, num, getter, nullable);
      case ENUM -> emitEnum(w, f, num, getter, nullable);
      case MESSAGE -> emitMessage(w, f, num, getter, nullable);
      default -> {}
    }
  }

  /**
   * The nested {@code Codec}'s Javadoc: a short note naming the enclosing record and the source
   * {@code .proto}, matching recordbuf's clean style (two lines of plain text, so no HTML escaping
   * is needed).
   */
  private String codecDoc(Message m) {
    return "Wire codec for the "
        + m.name()
        + " record, generated from "
        + sourceProto
        + ".\n"
        + "Reads and writes messages in the protobuf wire format.";
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
      case BYTES ->
          w.line("if (" + getter + ".length != 0) out." + call + "(" + num + ", " + getter + ");");
      default ->
          w.line(
              "if ("
                  + getter
                  + " != "
                  + ci.use(RT + ".CodecDefaults")
                  + "."
                  + defaultMethod(wire)
                  + "()) out."
                  + call
                  + "("
                  + num
                  + ", "
                  + getter
                  + ");");
    }
  }

  private void emitEnum(JavaWriter w, Field f, int num, String getter, boolean nullable) {
    if (nullable) {
      w.line("if (" + getter + " != null) out.writeEnum(" + num + ", " + getter + ".getNumber());");
      return;
    }
    w.line(
        "if ("
            + getter
            + " != null && "
            + getter
            + ".getNumber() != 0) out.writeEnum("
            + num
            + ", "
            + getter
            + ".getNumber());");
  }

  private void emitMessage(JavaWriter w, Field f, int num, String getter, boolean nullable) {
    // A singular message is always present-able (@Nullable): skip it on the
    // wire when null (proto3 absent). Otherwise encode the child bytes via
    // its own Codec and frame them as one length-delimited byte array
    // (tag + length + raw bytes).
    String nestedCodec = types.resolveElement(f) + ".Codec";
    w.line("if (" + getter + " != null) {");
    int ind = w.indent() + 1;
    w.indent(ind);
    w.line("out.writeByteArray(" + num + ", " + nestedCodec + ".encode(" + getter + "));");
    w.indent(ind - 1);
    w.line("}");
  }

  // --- map fields ---

  /**
   * The nested record name for a proto3 map field's entry: {@code Cap(field)}Entry (a sibling of
   * the record's own Codec), mirroring protobuf-java's entry type.
   */
  private static String entryName(Field f) {
    return JavaNames.cap(f.javaName()) + "Entry";
  }

  /**
   * Encode: one length-delimited entry per map put, framed like a repeated nested message --
   * writeByteArray(tag, entryBytes).
   */
  private void emitMapField(JavaWriter w, Message m, Field f) {
    String getter = "v." + f.javaName() + "()";
    String entry = entryName(f);
    w.line("for (var _e : " + getter + ".entrySet()) {");
    int ind = w.indent() + 1;
    w.indent(ind);
    w.line(
        "out.writeByteArray("
            + f.number()
            + ", "
            + entry
            + ".Codec.encode(new "
            + entry
            + "(_e.getKey(), _e.getValue())));");
    w.indent(ind - 1);
    w.line("}");
  }

  /**
   * Decode: each occurrence of the map's tag is one length-delimited entry; decode it via the entry
   * Codec and fold key/value into the map. Repeated tags accumulate across the decode loop.
   */
  private void emitMapDecode(JavaWriter w, Message m, Field f, int num) {
    String entry = entryName(f);
    String tmp = "_" + f.javaName();
    w.line("var " + tmp + " = " + entry + ".Codec.decode(in.readByteArray());");
    w.line(f.javaName() + ".put(" + tmp + ".key(), " + tmp + ".value());");
  }

  /**
   * Emit the nested entry record + its Codec for one map field. The entry is a 2-field message
   * (key=1, value=2). Both key and value are written unconditionally -- a map entry is a nested
   * message whose members are always present (see {@link #emitEntryScalar}), unlike a proto3
   * singular scalar which omits its default. The record is nested in the parent record next to its
   * Codec.
   */
  private void emitMapEntry(JavaWriter w, Field f) {
    String name = entryName(f);
    String keyType = types.mapComponentType(f.mapKey());
    String valueType = types.mapComponentType(f.mapValue());
    WireScalar keyWire = types.wireScalarForType(f.mapKey());
    WireScalar valWire = types.wireScalarForType(f.mapValue());

    w.line();
    w.line("public record " + name + "(" + keyType + " key, " + valueType + " value) {");
    w.indent(w.indent() + 1);
    emitEntryCodec(w, name, keyWire, valWire);
    w.indent(w.indent() - 1);
    w.line("}");
  }

  /** The Codec for a map entry record: the 2-field entry message (key=1, value=2). */
  private void emitEntryCodec(JavaWriter w, String name, WireScalar keyWire, WireScalar valWire) {
    w.line();
    w.line("public static final class Codec {");
    int prev = w.indent();
    w.indent(prev + 1);
    w.line("private Codec() {}");
    w.line();

    // --- encode ---
    w.line(
        "public static byte[] encode("
            + name
            + " v) throws "
            + ci.use("java.io.IOException")
            + " {");
    int enc = w.indent() + 1;
    w.indent(enc);
    w.line("var enc = " + ci.use(RT + ".Encoder") + ".start();");
    w.line("var out = enc.out();");
    emitEntryScalar(w, keyWire, 1, "v.key()");
    emitEntryScalar(w, valWire, 2, "v.value()");
    w.line("return enc.finish();");
    w.indent(enc - 1);
    w.line("}");
    w.line();

    // --- decode ---
    w.line(
        "public static "
            + name
            + " decode(byte[] inBytes) throws "
            + ci.use("java.io.IOException")
            + " {");
    int dec = w.indent() + 1;
    w.indent(dec);
    w.line("var key = " + ci.use(RT + ".CodecDefaults") + "." + defaultMethod(keyWire) + "();");
    w.line("var value = " + ci.use(RT + ".CodecDefaults") + "." + defaultMethod(valWire) + "();");
    w.line();
    w.line("var in = " + ci.use(RT + ".Decoder") + ".start(inBytes).in();");
    w.line("while (!in.isAtEnd()) {");
    int outer = w.indent() + 1;
    w.indent(outer);
    w.line("int tag = " + ci.use(RT + ".Wire") + ".readTag(in);");
    w.line("if (tag == 0) break;");
    w.line("int fieldNum = " + ci.use(RT + ".Wire") + ".tagFieldNumber(tag);");
    w.line("switch (fieldNum) {");
    int inner = outer + 1;
    w.indent(inner);
    w.line("case 1 -> { key = " + decodeCall(keyWire) + "; }");
    w.line("case 2 -> { value = " + decodeCall(valWire) + "; }");
    w.line("default -> " + ci.use(RT + ".Wire") + ".skip(in, tag);");
    w.indent(outer);
    w.line("}");
    w.indent(dec);
    w.line("}");
    w.line();
    w.line("return new " + name + "(key, value);");
    w.indent(dec - 1);
    w.line("}");
    w.line();
    w.indent(prev);
    w.line("}");
  }

  /**
   * Encode one entry key/value field. A proto3 map entry is a nested message whose key and value
   * are always present, so both are written UNCONDITIONALLY (default values like 0/false/empty are
   * NOT skipped -- matching protobuf-java's map-entry writeTo, which differs from proto3
   * singular-scalar default-skipping).
   */
  private void emitEntryScalar(JavaWriter w, WireScalar wire, int num, String getter) {
    w.line("out." + encodeCall(wire) + "(" + num + ", " + getter + ");");
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
    // proto3 packs a repeated scalar as ONE length-delimited group framed through
    // the runtime's Encoder: buffer the tight no-tag value sequence, then frame it.
    w.line("var body = " + ci.use(RT + ".Encoder") + ".start();");
    w.line("var _o = body.out();");
    w.line("for (var _x : " + getter + ") _o." + op + "(_x);");
    // Frame the buffered sequence as one length-delimited group. writeByteArray writes
    // tag + varint length + raw bytes -- the exact framing protobuf-java's packed encode/use.
    w.line("out.writeByteArray(" + num + ", body.finish());");
    w.indent(ind - 1);
    w.line("}");
  }

  private void emitRepeatedEnum(JavaWriter w, Field f, int num, String getter) {
    w.line("for (var _e : " + getter + ") out.writeEnum(" + num + ", _e.getNumber());");
  }

  private void emitRepeatedMessage(JavaWriter w, Field f, int num, String getter) {
    // Each element gets its own length-delimited byte array.
    String codecRef = types.resolveElement(f) + ".Codec";
    w.line(
        "for (var _m : "
            + getter
            + ") out.writeByteArray("
            + num
            + ", "
            + codecRef
            + ".encode(_m));");
  }

  // --- decode ---

  private void emitDecode(JavaWriter w, Message m) {
    for (Field f : m.fields()) {
      w.line(initDecl(f));
    }
    // One local per oneof, defaulting to null (the absent case). Each member of a
    // oneof is a distinct case in the decode switch and assigns the SAME local,
    // so a repeated same-tag member would overwrite a prior one (proto3 oneof
    // semantics: last one seen wins).
    for (Oneof o : m.oneofs()) {
      w.line(o.ifaceName() + " " + o.componentName() + " = null;");
    }
    // A builder that captures any field the schema does not model, on demand,
    // into a byte-exact carrier the record will hold as its unknownFields component.
    w.line("var unknown = " + ci.use(RT + ".UnknownFields") + ".builder();");
    w.line();
    w.line("var in = " + ci.use(RT + ".Decoder") + ".start(inBytes).in();");
    w.line("while (!in.isAtEnd()) {");
    int outer = w.indent() + 1;
    w.indent(outer);
    w.line("int tag = " + ci.use(RT + ".Wire") + ".readTag(in);");
    w.line("if (tag == 0) break;");
    w.line("int fieldNum = " + ci.use(RT + ".Wire") + ".tagFieldNumber(tag);");
    w.line("switch (fieldNum) {");
    int inner = outer + 1;
    w.indent(inner);
    for (Field f : m.fields()) {
      emitDecodeCase(w, m, f);
    }
    for (Oneof o : m.oneofs()) {
      emitOneofDecodeCases(w, m, o);
    }
    // A field the schema does not model: capture its raw bytes on demand so it rides
    // back out on encode and can be inspected without the newer schema. Replaces the
    // old Wire.skip, which discarded unknown fields.
    w.line("default -> " + ci.use(RT + ".Wire") + ".capture(in, unknown, tag);");
    w.indent(outer);
    w.line("}");
    w.indent(outer - 1);
    w.line("}");
    w.line();
    w.line("return new " + m.name() + "(");
    for (int i = 0; i < m.fields().size(); i++) {
      Field f = m.fields().get(i);
      // Always a trailing comma: the unknownFields carrier (unknown.build()) is
      // the last constructor argument.
      w.line("    " + f.javaName() + ",");
    }
    // The record's canonical ctor places the oneof components between its fields and
    // the unknownFields carrier, matching the declaration order in the record header.
    for (Oneof o : m.oneofs()) {
      w.line("    " + o.componentName() + ",");
    }
    w.line("    unknown.build()");
    w.line(");");
  }

  /**
   * Each oneof member becomes a case in the decode switch that assigns the oneof's shared local to
   * a freshly decoded value: a message member decodes via its own Codec; a scalar/enum member
   * decodes via {@code in.read<Wire>()} or the enum's {@code forNumber}, wrapped in the oneof's
   * {@code Scalar} wrapper. Multiple members of one oneof share the local, so proto3's "last seen
   * wins" semantics fall out of a normal switch.
   */
  private void emitOneofDecodeCases(JavaWriter w, Message m, Oneof o) {
    String iface = o.ifaceName();
    String cmp = o.componentName();
    for (Field f : o.members()) {
      int num = f.number();
      w.line("case " + num + " -> {");
      int ind = w.indent() + 1;
      w.indent(ind);
      if (f.isMessage()) {
        w.line(cmp + " = " + types.resolveElement(f) + ".Codec.decode(in.readByteArray());");
      } else {
        // The per-member nested record carries just the value; no tag needed.
        w.line(
            cmp
                + " = new "
                + iface
                + "."
                + JavaNames.cap(f.name())
                + "Value("
                + oneofMemberDecodeValue(o, f)
                + ");");
      }
      w.indent(ind - 1);
      w.line("}");
    }
  }

  /**
   * The right-hand value expression for decoding a scalar/enum oneof member in the switch: the
   * result of {@code in.read<Wire>()} for a scalar (autoboxed into the {@code Object} value of
   * {@code Scalar}) or the enum's {@code forNumber} for an enum member. The raw descriptor's member
   * field (looked up by the member's wire tag among the enclosing message's fields) tells us the
   * scalar's wire type.
   */
  private String oneofMemberDecodeValue(Oneof o, Field f) {
    var raw = rawMemberField(o, f.number());
    switch (raw.getType()) {
      case TYPE_STRING -> {
        return "in.readString()";
      }
      case TYPE_BYTES -> {
        return "in.readByteArray()";
      }
      case TYPE_BOOL -> {
        return "in.readBool()";
      }
      case TYPE_DOUBLE -> {
        return "in.readDouble()";
      }
      case TYPE_FLOAT -> {
        return "in.readFloat()";
      }
      case TYPE_INT32, TYPE_SINT32, TYPE_SFIXED32, TYPE_UINT32, TYPE_FIXED32 -> {
        return "in.readInt32()";
      }
      case TYPE_INT64, TYPE_SINT64, TYPE_SFIXED64, TYPE_UINT64, TYPE_FIXED64 -> {
        return "in.readInt64()";
      }
      case TYPE_ENUM -> {
        String enumName = types.resolveElement(f);
        // readEnum() already yields an int and forNumber takes an int, so no (int) cast.
        return enumName + ".forNumber(in.readEnum())";
      }
      case TYPE_MESSAGE, TYPE_GROUP -> {
        // Message members are routed through the message-branch in
        // emitOneofDecodeCases, not through oneofMemberDecodeValue.
        throw new IllegalStateException(
            "oneof decode: unexpected TYPE_MESSAGE member tag " + f.number());
      }
      default -> throw new IllegalStateException("unsupported oneof scalar type: " + raw.getType());
    }
  }

  /**
   * The raw {@code FieldDescriptorProto} for this oneof member, looked up by its wire tag among the
   * enclosing message's raw fields.
   */
  private static FieldDescriptorProto rawMemberField(Oneof o, int number) {
    var parent = o.parentDescriptor();
    for (int i = 0; i < parent.getFieldCount(); i++) {
      var f = parent.getField(i);
      if (f.getNumber() == number) return f;
    }
    throw new IllegalStateException(
        "no oneof member with tag " + number + " in raw descriptor for oneof " + o.name());
  }

  private void emitDecodeCase(JavaWriter w, Message m, Field f) {
    int num = f.number();
    w.line("case " + num + " -> {");
    int ind = w.indent() + 1;
    w.indent(ind);
    if (f.isMap()) {
      emitMapDecode(w, m, f, num);
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
        String enumName = types.resolveElement(f);
        // readEnum() already yields an int and forNumber takes an int, so no (int) cast.
        w.line(f.javaName() + " = " + enumName + ".forNumber(in.readEnum());");
      }
      case MESSAGE -> {
        // The tag was already consumed by readTag(); read the remaining
        // length-delimited byte array and hand it to the child Codec.
        String codecRef = types.resolveElement(f) + ".Codec";
        w.line(f.javaName() + " = " + codecRef + ".decode(in.readByteArray());");
      }
      default -> {}
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
        String enumName = types.resolveElement(f);
        // readEnum() already yields an int and forNumber takes an int, so no (int) cast.
        w.line(f.javaName() + ".add(" + enumName + ".forNumber(in.readEnum()));");
      }
      case MESSAGE -> {
        String codecRef = types.resolveElement(f) + ".Codec";
        w.line(f.javaName() + ".add(" + codecRef + ".decode(in.readByteArray()));");
      }
      default -> {}
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
    if (f.isRepeated())
      return types.resolve(f)
          + " "
          + f.javaName()
          + " = new "
          + ci.use("java.util.ArrayList")
          + "<>();";
    // A map field decodes into a MUTABLE, typed map so the decode loop can
    // put entries into it; the record's compact constructor then copies it to
    // an immutable Map.copyOf(...).
    if (f.isMap())
      return types.resolve(f)
          + " "
          + f.javaName()
          + " = new "
          + ci.use("java.util.HashMap")
          + "<>();";
    return "var "
        + f.javaName()
        + " = "
        + ci.use(RT + ".CodecDefaults")
        + "."
        + defaultMethod(f)
        + "();";
  }

  private String encodeCall(WireScalar wire) {
    return switch (wire) {
      case INT32, UINT32 -> "writeInt32";
      case SINT_32 -> "writeSInt32";
      case FIXED32 -> "writeFixed32";
      case INT64, UINT64 -> "writeInt64";
      case SINT_64 -> "writeSInt64";
      case FIXED64 -> "writeFixed64";
      case FLOAT -> "writeFloat";
      case DOUBLE -> "writeDouble";
      case BOOL -> "writeBool";
      case STRING -> "writeString";
      case BYTES -> "writeByteArray"; // byte[] -> 2-arg byte-array op
    };
  }

  /**
   * The no-tag primitive writer used to pack a tight value sequence, mirroring {@link #encodeCall}
   * but with the tag handled by the outer framing step.
   */
  private String packedNoTag(WireScalar wire) {
    return switch (wire) {
      case INT32, UINT32 -> "writeInt32NoTag";
      case SINT_32 -> "writeSInt32NoTag";
      case FIXED32 -> "writeFixed32NoTag";
      case INT64, UINT64 -> "writeInt64NoTag";
      case SINT_64 -> "writeSInt64NoTag";
      case FIXED64 -> "writeFixed64NoTag";
      case FLOAT -> "writeFloatNoTag";
      case DOUBLE -> "writeDoubleNoTag";
      case BOOL -> "writeBoolNoTag";
      default -> throw new IllegalStateException(wire + " is not a packed numeric type");
    };
  }

  private String decodeCall(WireScalar wire) {
    return switch (wire) {
      case INT32, UINT32 -> "in.readInt32()";
      case SINT_32 -> "in.readSInt32()";
      case FIXED32 -> "in.readFixed32()";
      case INT64, UINT64 -> "in.readInt64()";
      case SINT_64 -> "in.readSInt64()";
      case FIXED64 -> "in.readFixed64()";
      case FLOAT -> "in.readFloat()";
      case DOUBLE -> "in.readDouble()";
      case BOOL -> "in.readBool()";
      case STRING -> "in.readString()";
      case BYTES -> "in.readByteArray()";
    };
  }

  /**
   * The {@code CodecDefaults} method returning a decoded field's default local value for a field of
   * the given type.
   */
  private String defaultMethod(Field f) {
    return defaultMethod(types.wireScalar(f));
  }

  /** The {@link #RT CodecDefaults} method name for a wire scalar's default value. */
  private static String defaultMethod(WireScalar wire) {
    return switch (wire) {
      case INT32, UINT32, SINT_32, FIXED32 -> "int32";
      case INT64, UINT64, SINT_64, FIXED64 -> "int64";
      case FLOAT -> "float_";
      case DOUBLE -> "double_";
      case BOOL -> "bool";
      case STRING -> "string";
      case BYTES -> "bytes";
    };
  }
}
