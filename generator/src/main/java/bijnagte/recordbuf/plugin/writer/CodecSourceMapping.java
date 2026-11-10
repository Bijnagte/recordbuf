package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Line;
import bijnagte.recordbuf.codegen.source.Member;
import bijnagte.recordbuf.codegen.source.MemberConstructorBuilder;
import bijnagte.recordbuf.codegen.source.MemberMethodBuilder;
import bijnagte.recordbuf.codegen.source.Modifiers;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeClassBuilder;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.WellKnownTypes.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Maps a proto {@link Message} model into a nested {@link Type.Class} {@code Codec} node for the
 * source AST. Handles all field families: scalars, repeated scalars, optional scalars/strings,
 * maps, nested messages, singular/repeated enums, oneofs, and WKT fields.
 *
 * <p>Wires into {@link RecordSourceMapping} by appending {@code new Member.Nested(codec)} to the
 * record's member list.
 */
public final class CodecSourceMapping {

  private static final String RT = "bijnagte.recordbuf.runtime";

  private CodecSourceMapping() {}

  /**
   * Map a message model to a nested {@code Codec} class node. The codec is scoped statically inside
   * the enclosing record (its parent is the record's name, set by the caller via the {@link
   * TypeClassBuilder#parent}), and has a private constructor + a static {@code encode} + a static
   * {@code decode}.
   *
   * @param m the message model
   * @param ci the type collector (used to pre-register all runtime FQN references so the import
   *     block is correct)
   * @return the nested {@code Type.Class} codec node
   */
  public static Type.Class toCodec(
      Message m, TypeCollector ci, @Nullable Function<Field, String> typeResolver) {
    // Backwards compat: no typeResolver = scalar-only.
    return doToCodec(m, ci, typeResolver);
  }

  private static Type.Class doToCodec(
      Message m, TypeCollector ci, @Nullable Function<Field, String> typeResolver) {
    // Pre-resolve all runtime FQN references to short forms so the codec body uses imported
    // short names, not fully-qualified names (satisfying the UnnecessarilyFullyQualified check).
    String encoderShort = ci.use(RT + ".Encoder");
    String decoderShort = ci.use(RT + ".Decoder");
    String unknownFieldsShort = ci.use(RT + ".UnknownFields");
    String codecDefaultsShort = ci.use(RT + ".CodecDefaults");
    String wireShort = ci.use(RT + ".Wire");
    String bytesShort = ci.use(RT + ".Bytes");
    // M4-C2d-7: map fields need java.util.HashMap; repeated fields need java.util.ArrayList.
    ci.use("java.util.HashMap");
    ci.use("java.util.ArrayList");
    List<Member> codecMembers = new ArrayList<>();
    String codecName = m.name() + "Codec"; // M4-C2b-2b-8: align with old SampleCodec naming
    codecMembers.add(
        MemberConstructorBuilder.builder()
            .mods(Modifiers.of("private"))
            .name(codecName)
            .params("()")
            .build());

    codecMembers.add(
        encode(
            m,
            ci,
            encoderShort,
            decoderShort,
            unknownFieldsShort,
            codecDefaultsShort,
            wireShort,
            bytesShort,
            typeResolver));
    codecMembers.add(
        decode(
            m,
            ci,
            encoderShort,
            decoderShort,
            unknownFieldsShort,
            codecDefaultsShort,
            wireShort,
            bytesShort,
            typeResolver));

    return TypeClassBuilder.builder()
        .parent(m.name())
        .mods(Modifiers.of("public", "static", "final"))
        .javadoc(new Javadoc("Wire codec for the " + m.name() + " record."))
        .name(codecName) // M4-C2b-2b-8: <Name>Codec to align with old path
        .members(codecMembers)
        .build();
  }

  /** The {@code byte[] encode(Message v) throws IOException} method body. */
  private static Member.Method encode(
      Message m,
      TypeCollector ci,
      String encoderShort,
      String decoderShort,
      String unknownFieldsShort,
      String codecDefaultsShort,
      String wireShort,
      String bytesShort,
      @Nullable Function<Field, String> typeResolver) {
    List<Line> body = new ArrayList<>();
    body.add(Line.of("var enc = " + encoderShort + ".start();"));
    body.add(Line.of("var out = enc.out();"));

    // Sort by field number for wire-order correctness (OrderTest proves this).
    var sortedFields = m.fields().stream().sorted(Comparator.comparingInt(Field::number)).toList();

    for (Field f : sortedFields) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        WktSourceMapping.emitEncode(f, wkt0, "out", "v", ci, body);
      } else if (f.kind() == FieldKind.MAP) {
        emitMapEncode(f, ci, body);
      } else if (f.kind() == FieldKind.MESSAGE) {
        if (f.label() == FieldLabel.REPEATED) {
          emitRepeatedMessageEncode(f, typeResolver, body);
        } else {
          emitMessageEncode(f, typeResolver, body);
        }
      } else if (f.kind() == FieldKind.ENUM) {
        if (f.label() == FieldLabel.REPEATED) {
          emitRepeatedEnumEncode(f, typeResolver, body);
        } else {
          emitEnumEncode(f, typeResolver, body);
        }
      } else if (f.label() == FieldLabel.REPEATED) {
        emitRepeatedScalarEncode(f, body);
      } else {
        emitScalarEncode(f, body);
      }
    }

    // Oneof members: pattern-matching switch on the sealed-interface component.
    for (Oneof o : m.oneofs()) {
      emitOneofEncode(o, ci, typeResolver, body);
    }

    // M4-C2b-2b-5: re-emit the unknown-fields carrier, if any, preserving wire-order round-trip.
    body.add(
        Line.of(
            "if (!v.unknownFields().isEmpty()) "
                + wireShort
                + ".writeUnknown(out, v.unknownFields());"));

    body.add(Line.of("return enc.finish();"));

    return MemberMethodBuilder.builder()
        .mods(Modifiers.of("public", "static"))
        .returnType("byte[]")
        .name("encode")
        .params("(" + m.name() + " v)")
        .thrown(List.of("java.io.IOException"))
        .body(body)
        .build();
  }

  /** The {@code Message decode(byte[] inBytes) throws IOException} method body. */
  private static Member.Method decode(
      Message m,
      TypeCollector ci,
      String encoderShort,
      String decoderShort,
      String unknownFieldsShort,
      String codecDefaultsShort,
      String wireShort,
      String bytesShort,
      @Nullable Function<Field, String> typeResolver) {
    List<Line> body = new ArrayList<>();
    body.add(Line.of("var dec = " + decoderShort + ".start(inBytes);"));
    body.add(Line.of("var in = dec.in();"));

    // M4-C2b-2b-5: the unknown-fields accumulator, initialised as a fresh builder.
    body.add(Line.of("var unknown = " + unknownFieldsShort + ".builder();"));

    // Per-field local default-init.
    for (Field f : m.fields()) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        body.add(Line.of(WktSourceMapping.localInit(f, wkt0, ci)));
      } else if (f.kind() == FieldKind.MAP) {
        String keyType = boxedScalarShortName(Objects.requireNonNull(f.mapKey()));
        String valueType = boxedScalarShortName(Objects.requireNonNull(f.mapValue()));
        body.add(
            Line.of(
                "Map<" + keyType + ", " + valueType + "> " + f.javaName() + " = new HashMap<>();"));
      } else if (f.kind() == FieldKind.MESSAGE) {
        if (typeResolver != null) {
          // The TYPE short name for the local init.
          String typeShort = f.typeUrl().substring(f.typeUrl().lastIndexOf('.') + 1);
          if (f.label() == FieldLabel.REPEATED) {
            body.add(Line.of("List<" + typeShort + "> " + f.javaName() + " = new ArrayList<>();"));
          } else {
            body.add(Line.of(typeShort + " " + f.javaName() + " = null;"));
          }
        }
      } else if (f.kind() == FieldKind.ENUM) {
        // M4-C2b-2b-9: enum fields — singular → null, repeated → ArrayList.
        // typeResolver gives the enum short name; no typeResolver means unresolvable.
        if (typeResolver != null) {
          String enumShort = typeResolver.apply(f);
          if (f.label() == FieldLabel.REPEATED) {
            body.add(Line.of("List<" + enumShort + "> " + f.javaName() + " = new ArrayList<>();"));
          } else {
            body.add(Line.of(enumShort + " " + f.javaName() + " = null;"));
          }
        } else {
          if (f.label() == FieldLabel.REPEATED) {
            body.add(Line.of("List<?> " + f.javaName() + " = new ArrayList<>();"));
          } else {
            body.add(Line.of("Object " + f.javaName() + " = null;"));
          }
        }
      } else if (f.label() == FieldLabel.REPEATED) {
        emitRepeatedLocalInit(f, body);
      } else if (f.isNullable()) {
        // M4-C2d-1: optional scalar → starts at null, set on tag.
        String localType = boxedScalarShortName(f.typeUrl());
        body.add(Line.of(localType + " " + f.javaName() + " = null;"));
      } else {
        String localType = decodeLocalType(f);
        String defaultExpr = codecDefaultsShort + "." + defaultMethod(f) + "()";
        body.add(Line.of(localType + " " + f.javaName() + " = " + defaultExpr + ";"));
      }
    }

    // Oneof locals: each defaults to null (the absent case). Proto3 oneof semantics: last
    // same-tag member seen wins, so a repeated same-tag member overwrites the prior value.
    for (Oneof o : m.oneofs()) {
      body.add(Line.of(o.ifaceName() + " " + o.componentName() + " = null;"));
    }

    // Tag loop.
    body.add(Line.of("while (true) {"));
    body.add(Line.of("  int tag = " + wireShort + ".readTag(in);"));
    body.add(Line.of("  if (tag == 0) break;"));
    body.add(Line.of("  switch (" + wireShort + ".tagFieldNumber(tag)) {"));
    for (Field f : m.fields()) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        WktSourceMapping.emitDecodeCase(f, wkt0, "in", "Wkt", body);
      } else if (f.kind() == FieldKind.MAP) {
        emitMapDecodeCase(f, ci, body);
      } else if (f.kind() == FieldKind.MESSAGE) {
        if (f.label() == FieldLabel.REPEATED) {
          emitRepeatedMessageDecodeCase(f, typeResolver, body);
        } else {
          emitMessageDecodeCase(f, typeResolver, body);
        }
      } else if (f.kind() == FieldKind.ENUM) {
        if (f.label() == FieldLabel.REPEATED) {
          emitRepeatedEnumDecodeCase(f, typeResolver, body);
        } else {
          emitEnumDecodeCase(f, typeResolver, body);
        }
      } else if (f.label() == FieldLabel.REPEATED) {
        emitRepeatedScalarDecodeCase(f, body);
      } else {
        emitScalarDecodeCase(f, body);
      }
    }
    // Oneof decode cases: each member contributes a case that assigns the oneof local.
    for (Oneof o : m.oneofs()) {
      emitOneofDecodeCase(o, ci, typeResolver, body);
    }
    // M4-C2b-2b-5: the default case captures the unknown field into the builder.
    body.add(Line.of("    default -> unknown.add(in, tag);"));
    body.add(Line.of("  }"));
    body.add(Line.of("}"));

    // Construct the record, passing the carrier as the last argument.
    List<String> allNames = new ArrayList<>();
    for (Field f : m.fields()) allNames.add(f.javaName());
    // M4-C2b-2b-12: oneof components: pass the oneof local (assigned in the tag loop, or null).
    for (var oneof : m.oneofs()) allNames.add(oneof.componentName());
    allNames.add("unknown.build()");
    body.add(Line.of("return new " + m.name() + "(" + joinCommas(allNames) + ");"));

    return MemberMethodBuilder.builder()
        .mods(Modifiers.of("public", "static"))
        .returnType(m.name())
        .name("decode")
        .params("(byte[] inBytes)")
        .thrown(List.of("java.io.IOException"))
        .body(body)
        .build();
  }

  // --- Per-field encode ---

  private static void emitScalarEncode(Field f, List<Line> out) {
    // M4-C2b-2b-13: minimal MAP encode (placeholder — full wiring deferred).
    if (f.kind() == FieldKind.MAP) {
      out.add(Line.of("// M4-C2b-2b-13: MAP encode for field " + f.javaName() + " deferred"));
      return;
    }
    if (f.kind() != FieldKind.SCALAR) {
      out.add(Line.of("// unsupported kind " + f.kind() + " for field " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    String call = encodeCall(wire);
    if (f.isNullable()) {
      // M4-C2d-1: optional scalar / optional string → @Nullable wrapper → skip if null.
      out.add(
          Line.of("if (" + getter + " != null) out." + call + "(" + num + ", " + getter + ");"));
      return;
    }
    switch (wire) {
      case STRING ->
          out.add(
              Line.of(
                  "if (!" + getter + ".isEmpty()) out." + call + "(" + num + ", " + getter + ");"));
      case BYTES ->
          out.add(
              Line.of(
                  "if (!"
                      + getter
                      + ".isEmpty()) out."
                      + call
                      + "("
                      + num
                      + ", "
                      + getter
                      + ".toByteArray());"));
      default ->
          out.add(
              Line.of(
                  "if ("
                      + getter
                      + " != CodecDefaults."
                      + defaultMethod(f)
                      + "()) out."
                      + call
                      + "("
                      + num
                      + ", "
                      + getter
                      + ");"));
    }
  }

  // --- Per-field MESSAGE encode/decode (M4-C2d-5) ---

  /** M4-C2d-5: singular MESSAGE encode — skip null, else writeByteArray(tag, Codec.encode(v)). */
  private static void emitMessageEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2d-5: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    String codecRef = typeResolver.apply(f);
    out.add(Line.of("if (" + getter + " != null) {"));
    out.add(Line.of("  out.writeByteArray(" + num + ", " + codecRef + ".encode(" + getter + "));"));
    out.add(Line.of("}"));
  }

  /** M4-C2d-5: repeated MESSAGE encode — one writeByteArray per element. */
  private static void emitRepeatedMessageEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2d-5: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    String codecRef = typeResolver.apply(f);
    out.add(
        Line.of(
            "for (var _m : "
                + getter
                + ") out.writeByteArray("
                + num
                + ", "
                + codecRef
                + ".encode(_m));"));
  }

  // --- Per-field ENUM encode/decode (M4-C2b-2b-9) ---

  /** M4-C2b-2b-9: singular ENUM encode — skip null, else writeEnum with getNumber(). */
  private static void emitEnumEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2b-2b-9: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    out.add(
        Line.of(
            "if (" + getter + " != null) out.writeEnum(" + num + ", " + getter + ".getNumber());"));
  }

  /** M4-C2b-2b-9: repeated ENUM encode — one writeEnum per element. */
  private static void emitRepeatedEnumEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2b-2b-9: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    out.add(Line.of("for (var _e : " + getter + ") out.writeEnum(" + num + ", _e.getNumber());"));
  }

  /** M4-C2b-2b-9: singular ENUM decode — assign EnumName.forNumber(in.readEnum()). */
  private static void emitEnumDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2b-2b-9: typeResolver null; skip " + f.javaName()));
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String enumName = typeResolver.apply(f);
    out.add(
        Line.of(
            "    case " + num + " -> " + local + " = " + enumName + ".forNumber(in.readEnum());"));
  }

  /** M4-C2b-2b-9: repeated ENUM decode — add EnumName.forNumber(in.readEnum()) to the list. */
  private static void emitRepeatedEnumDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2b-2b-9: typeResolver null; skip " + f.javaName()));
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String enumName = typeResolver.apply(f);
    out.add(
        Line.of(
            "    case "
                + num
                + " -> "
                + local
                + ".add("
                + enumName
                + ".forNumber(in.readEnum()));"));
  }

  // --- Per-field MESSAGE decode case (M4-C2d-5) ---

  /** M4-C2d-5: singular MESSAGE decode — read length-delimited and decode via the codec. */
  private static void emitMessageDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2d-5: typeResolver null; skip " + f.javaName()));
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String codecRef = typeResolver.apply(f);
    out.add(Line.of("    case " + num + " -> {"));
    out.add(Line.of("      " + local + " = " + codecRef + ".decode(in.readByteArray());"));
    out.add(Line.of("    }"));
  }

  /** M4-C2d-5: repeated MESSAGE decode — read length-delimited, add to the list. */
  private static void emitRepeatedMessageDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    if (typeResolver == null) {
      out.add(Line.of("// M4-C2d-5: typeResolver null; skip " + f.javaName()));
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String codecRef = typeResolver.apply(f);
    out.add(Line.of("    case " + num + " -> {"));
    out.add(Line.of("      " + local + ".add(" + codecRef + ".decode(in.readByteArray()));"));
    out.add(Line.of("    }"));
  }

  // --- Per-field decode case ---

  private static void emitScalarDecodeCase(Field f, List<Line> out) {
    if (f.kind() != FieldKind.SCALAR) return;
    int num = f.number();
    String local = f.javaName();
    switch (wireScalar(f)) {
      case STRING -> out.add(Line.of("    case " + num + " -> " + local + " = in.readString();"));
      case BYTES ->
          out.add(
              Line.of(
                  "    case "
                      + num
                      + " -> "
                      + local
                      + " = Bytes.of(in.readBytes().toByteArray());"));
      default ->
          out.add(
              Line.of(
                  "    case " + num + " -> " + local + " = " + decodeCall(wireScalar(f)) + ";"));
    }
  }

  /** M4-C2d-7: MAP encode — iterate the map's entry set, encode each entry via its entry codec. */
  @SuppressWarnings("java:S905")
  private static void emitMapEncode(Field f, TypeCollector ci, List<Line> out) {
    String entryName = mapEntryName(f);
    out.add(Line.of("for (var _e : v." + f.javaName() + "().entrySet()) {"));
    out.add(
        Line.of(
            "  out.writeByteArray("
                + f.number()
                + ", "
                + entryName
                + "."
                + entryName
                + "Codec.encode(new "
                + entryName
                + "(_e.getKey(), _e.getValue())));"));
    out.add(Line.of("}"));
  }

  // --- Helpers ---

  /** M4-C2d-7: the entry record name for a MAP field: <CapitalizedFieldName>Entry. */
  private static String mapEntryName(Field f) {
    String javaName = f.javaName();
    String entryName =
        javaName.isEmpty() ? "" : Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
    return entryName + "Entry";
  }

  /**
   * The local type a decoded field's accumulator starts from. For the scalar primitive types, the
   * type matches the record component type; for {@code Bytes}, the local is the {@code
   * bijnagte.recordbuf.runtime.Bytes} wrapper type.
   */
  private static String decodeLocalType(Field f) {
    if (f.kind() != FieldKind.SCALAR) return "Object";
    return switch (wireScalar(f)) {
      case STRING -> "String";
      case BYTES -> "Bytes";
      case INT32, UINT32, SINT_32, FIXED32 -> "int";
      case INT64, UINT64, SINT_64, FIXED64 -> "long";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case BOOL -> "boolean";
      default -> "Object";
    };
  }

  private static String joinCommas(List<String> names) {
    return String.join(", ", names);
  }

  /** The wire scalar type of a proto scalar field, by its typeUrl. */
  private static WireScalar wireScalar(Field f) {
    return switch (f.typeUrl()) {
      case "int32", "uint32" -> WireScalar.INT32;
      case "sint32" -> WireScalar.SINT_32;
      case "fixed32", "sfixed32" -> WireScalar.FIXED32;
      case "int64", "uint64" -> WireScalar.INT64;
      case "sint64" -> WireScalar.SINT_64;
      case "fixed64", "sfixed64" -> WireScalar.FIXED64;
      case "float" -> WireScalar.FLOAT;
      case "double" -> WireScalar.DOUBLE;
      case "bool" -> WireScalar.BOOL;
      case "string" -> WireScalar.STRING;
      case "bytes" -> WireScalar.BYTES;
      default -> throw new UnsupportedOperationException("wireScalar for typeUrl=" + f.typeUrl());
    };
  }

  /** The {@code CodedOutputStream.*} write primitive for a wire scalar. */
  private static String encodeCall(WireScalar wire) {
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
      case BYTES -> "writeByteArray";
    };
  }

  /** The no-tag write primitive for a packed scalar's element (mirrors{@code CodecGenerator}). */
  private static String packedNoTag(WireScalar wire) {
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

  /** The {@code in.read*()} read call for a wire scalar, without the receiver prefix. */
  private static String decodeCall(WireScalar wire) {
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
      case BYTES -> "in.readBytes().toByteArray()";
    };
  }

  /** The {@code CodecDefaults.*()} method name for a wire scalar's default. */
  private static String defaultMethod(Field f) {
    return switch (wireScalar(f)) {
      case INT32, UINT32, SINT_32, FIXED32 -> "int32";
      case INT64, UINT64, SINT_64, FIXED64 -> "int64";
      case FLOAT -> "float_";
      case DOUBLE -> "double_";
      case BOOL -> "bool";
      case STRING -> "string";
      case BYTES -> "bytes";
    };
  }

  // --- Repeated scalar encode / decode ---

  private static void emitRepeatedScalarEncode(Field f, List<Line> out) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    String readOp = decodeCall(wire);
    // Packed scalar: emit one length-delimited group when the list is non-empty.
    out.add(Line.of("if (!" + getter + ".isEmpty()) {"));
    out.add(Line.of("  var body = Encoder.start();"));
    out.add(Line.of("  var _o = body.out();"));
    out.add(Line.of("  for (var _x : " + getter + ") _o." + packedNoTag(wire) + "(_x);"));
    out.add(Line.of("  out.writeByteArray(" + num + ", body.finish());"));
    out.add(Line.of("}"));
  }

  private static void emitRepeatedScalarDecodeCase(Field f, List<Line> out) {
    int num = f.number();
    String local = f.javaName();
    WireScalar wire = wireScalar(f);
    out.add(Line.of("    case " + num + " -> {"));
    out.add(Line.of("      int _lim = in.pushLimit(in.readRawVarint32());"));
    out.add(
        Line.of(
            "      while (in.getBytesUntilLimit() > 0) "
                + local
                + ".add("
                + decodeCall(wire)
                + ");"));
    out.add(Line.of("      in.popLimit(_lim);"));
    out.add(Line.of("    }"));
  }

  private static void emitRepeatedLocalInit(Field f, List<Line> out) {
    out.add(
        Line.of(
            "List<"
                + boxedScalarShortName(f.typeUrl())
                + "> "
                + f.javaName()
                + " = new ArrayList<>();"));
  }

  /** The short-form boxed Java component type for a repeated scalar's element. */
  private static String boxedScalarShortName(String typeUrl) {
    return switch (typeUrl) {
      case "int32", "uint32", "fixed32", "sfixed32", "sint32" -> "Integer";
      case "int64", "uint64", "fixed64", "sfixed64", "sint64" -> "Long";
      case "float" -> "Float";
      case "double" -> "Double";
      case "bool" -> "Boolean";
      case "string" -> "String";
      default -> "Object";
    };
  }

  /**
   * M4-C2d-7: emit a 2-field entry codec for a map entry record. The entry has exactly two fields:
   * {@code key} (field 1) and {@code value} (field 2). Both are scalar types.
   *
   * @param entryName the entry record's name (e.g. "DataEntry")
   * @param mapFieldNumber the map field's number (for logging/debugging only)
   * @param keyType the boxed Java type of the key component (e.g. "String", "Integer")
   * @param valueType the boxed Java type of the value component (e.g. "Integer", "Long")
   * @param ci the type collector
   * @return the nested {@code <Entry>Codec} class
   */
  @SuppressWarnings("java:S905")
  public static Type.Class emitEntryCodec(String entryName, Field f, TypeCollector ci) {
    String encoderShort = ci.use(RT + ".Encoder");
    String decoderShort = ci.use(RT + ".Decoder");
    String unknownFieldsShort = ci.use(RT + ".UnknownFields");
    String codecDefaultsShort = ci.use(RT + ".CodecDefaults");
    String wireShort = ci.use(RT + ".Wire");
    ci.use(RT + ".Bytes");

    // M4-C2d-12: resolve key/value wire scalars from the Field's type URLs.
    WireScalar keyWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapKey()));
    WireScalar valWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue()));
    String codecName = entryName + "Codec";
    List<Member> codecMembers = new ArrayList<>();

    codecMembers.add(
        MemberConstructorBuilder.builder()
            .mods(Modifiers.of("private"))
            .name(codecName)
            .params("()")
            .build());

    List<Line> encBody = new ArrayList<>();
    encBody.add(Line.of("var enc = " + encoderShort + ".start();"));
    encBody.add(Line.of("var out = enc.out();"));

    // key: field 1 — always field 1 in a map entry; written unconditionally.
    // A bytes-valued key unwraps to the raw array (Bytes.toByteArray()); every other writes
    // through.
    String keyArg = keyWire == WireScalar.BYTES ? "v.key().toByteArray()" : "v.key()";
    encBody.add(Line.of("out." + encodeCall(keyWire) + "(1, " + keyArg + ");"));
    // value: field 2 — always field 2; written unconditionally, same unwrapping rule.
    String valArg = valWire == WireScalar.BYTES ? "v.value().toByteArray()" : "v.value()";
    encBody.add(Line.of("out." + encodeCall(valWire) + "(2, " + valArg + ");"));
    encBody.add(Line.of("return enc.finish();"));

    codecMembers.add(
        MemberMethodBuilder.builder()
            .mods(Modifiers.of("public", "static"))
            .returnType("byte[]")
            .name("encode")
            .params("(" + entryName + " v)")
            .thrown(List.of("java.io.IOException"))
            .body(encBody)
            .build());

    List<Line> decBody = new ArrayList<>();
    decBody.add(Line.of("var dec = " + decoderShort + ".start(inBytes);"));
    decBody.add(Line.of("var in = dec.in();"));
    decBody.add(Line.of("var unknown = " + unknownFieldsShort + ".builder();"));
    emitFieldLocalInit(decBody, "key", keyWire, codecDefaultsShort);
    emitFieldLocalInit(decBody, "value", valWire, codecDefaultsShort);
    decBody.add(Line.of("while (true) {"));
    decBody.add(Line.of("  int tag = " + wireShort + ".readTag(in);"));
    decBody.add(Line.of("  if (tag == 0) break;"));
    decBody.add(Line.of("  switch (" + wireShort + ".tagFieldNumber(tag)) {"));
    emitFieldDecodeCase(decBody, 1, "key", keyWire, wireShort);
    emitFieldDecodeCase(decBody, 2, "value", valWire, wireShort);
    decBody.add(Line.of("  default -> unknown.add(in, tag);"));
    decBody.add(Line.of("  }"));
    decBody.add(Line.of(""));
    decBody.add(Line.of("}"));
    decBody.add(Line.of("return new " + entryName + "(key, value);"));

    codecMembers.add(
        MemberMethodBuilder.builder()
            .mods(Modifiers.of("public", "static"))
            .returnType(entryName)
            .name("decode")
            .params("(byte[] inBytes)")
            .thrown(List.of("java.io.IOException"))
            .body(decBody)
            .build());

    return TypeClassBuilder.builder()
        .mods(Modifiers.of("public", "static", "final"))
        .name(codecName)
        .members(codecMembers)
        .build();
  }

  /** M4-C2d-12: resolve a {@link WireScalar} from a proto scalar type URL string. */
  private static WireScalar wireScalarForTypeUrl(String typeUrl) {
    return switch (typeUrl) {
      case "int32", "uint32" -> WireScalar.INT32;
      case "sint32" -> WireScalar.SINT_32;
      case "fixed32", "sfixed32" -> WireScalar.FIXED32;
      case "int64", "uint64" -> WireScalar.INT64;
      case "sint64" -> WireScalar.SINT_64;
      case "fixed64", "sfixed64" -> WireScalar.FIXED64;
      case "float" -> WireScalar.FLOAT;
      case "double" -> WireScalar.DOUBLE;
      case "bool" -> WireScalar.BOOL;
      case "string" -> WireScalar.STRING;
      case "bytes" -> WireScalar.BYTES;
      default -> throw new UnsupportedOperationException("wireScalar for typeUrl=" + typeUrl);
    };
  }

  /** M4-C2d-12: emit a decode local-initializer line for a map entry's scalar field. */
  private static void emitFieldLocalInit(List<Line> out, String name, WireScalar ws, String cd) {
    if (ws == WireScalar.STRING) {
      out.add(Line.of("String " + name + " = \"\";"));
    } else {
      out.add(
          Line.of(
              localTypeForWire(ws) + " " + name + " = " + cd + "." + defaultForWire(ws) + "();"));
    }
  }

  /** M4-C2d-12: emit a decode case line for a map entry's scalar field. */
  private static void emitFieldDecodeCase(
      List<Line> out, int fieldNum, String name, WireScalar ws, String wire) {
    if (ws == WireScalar.STRING) {
      out.add(Line.of("  case " + fieldNum + " -> " + name + " = in.readString();"));
    } else {
      out.add(Line.of("  case " + fieldNum + " -> " + name + " = " + decodeCall(ws) + ";"));
    }
  }

  /** M4-C2d-12: the Java local type for a decoded map entry scalar. */
  private static String localTypeForWire(WireScalar ws) {
    return switch (ws) {
      case INT32, UINT32, SINT_32, FIXED32 -> "int";
      case INT64, UINT64, SINT_64, FIXED64 -> "long";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case BOOL -> "boolean";
      default -> "String";
    };
  }

  /** M4-C2d-12: the {@code CodecDefaults.<type>()} method name for a wire scalar. */
  private static String defaultForWire(WireScalar ws) {
    return switch (ws) {
      case INT32, UINT32, SINT_32, FIXED32 -> "int32";
      case INT64, UINT64, SINT_64, FIXED64 -> "int64";
      case FLOAT -> "float_";
      case DOUBLE -> "double_";
      case BOOL -> "bool";
      default -> "int32";
    };
  }

  /**
   * M4-C2d-7: decode case for a MAP field — read length-delimited entry, decode via entry codec.
   */
  @SuppressWarnings("java:S905")
  private static void emitMapDecodeCase(Field f, TypeCollector ci, List<Line> out) {
    String entryName = mapEntryName(f);
    String local = f.javaName();
    int num = f.number();
    out.add(Line.of("    case " + num + " -> {"));
    out.add(
        Line.of(
            "      var _entry = "
                + entryName
                + "."
                + entryName
                + "Codec.decode(in.readByteArray());"));
    out.add(Line.of("      " + local + ".put(_entry.key(), _entry.value());"));
    out.add(Line.of("    }"));
  }

  // --- Oneof encode / decode ---

  /**
   * Emit the oneof group encode: a pattern-matching {@code switch} on the sealed-interface
   * component. {@code case null -> {}} skips; message members dispatch through their nested codec;
   * scalar/enum members dispatch through the wire primitive that matches their field type.
   */
  private static void emitOneofEncode(
      Oneof o, TypeCollector ci, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    String iface = o.ifaceName();
    String cmp = o.componentName();
    String getter = "v." + cmp + "()";
    out.add(Line.of("switch (" + getter + ") {"));
    out.add(Line.of("  case null -> {}"));
    for (Field f : o.members()) {
      String bnd = String.valueOf(f.name().charAt(0)) + "_"; // unique binding name
      if (f.isMessage()) {
        String elem = f.typeUrl();
        String type = elem.substring(elem.lastIndexOf('.') + 1);
        // The per-member sealed value record wraps the actual record; pattern-match on it.
        String sealedSub = iface + "." + JavaNames.cap(f.name()) + "Value";
        String codecRef = type + "." + type + "Codec";
        out.add(
            Line.of(
                "  case "
                    + sealedSub
                    + " "
                    + bnd
                    + " -> out.writeByteArray("
                    + f.number()
                    + ", "
                    + codecRef
                    + ".encode("
                    + bnd
                    + ".value()));"));
      } else if (f.kind() == FieldKind.ENUM) {
        // Enum oneof member: write via writeEnum, calling .getNumber() on the enum value.
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        out.add(
            Line.of(
                "  case "
                    + recName
                    + " "
                    + bnd
                    + " -> out.writeEnum("
                    + f.number()
                    + ", "
                    + bnd
                    + ".value().getNumber());"));
      } else {
        // Scalar oneof member: dispatch on wire type.
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        WireScalar wire = wireScalar(f);
        String call = encodeCall(wire);
        out.add(
            Line.of(
                "  case "
                    + recName
                    + " "
                    + bnd
                    + " -> out."
                    + call
                    + "("
                    + f.number()
                    + ", "
                    + bnd
                    + ".value());"));
      }
    }
    out.add(Line.of("}"));
  }

  /**
   * Emit the decode case for each member of the oneof: a {@code case fieldNumber -> { ...}} that
   * assigns the oneof's shared local. A message member decodes via its nested codec; a scalar/enum
   * member decodes via the wire read primitive and wraps in the per-member record.
   */
  private static void emitOneofDecodeCase(
      Oneof o, TypeCollector ci, @Nullable Function<Field, String> typeResolver, List<Line> out) {
    String iface = o.ifaceName();
    String cmp = o.componentName();
    for (Field f : o.members()) {
      int num = f.number();
      out.add(Line.of("    case " + num + " -> {"));
      out.add(Line.of("      " + cmp + " = " + oneofDecodeRhs(o, f, ci, typeResolver) + ";"));
      out.add(Line.of("    }"));
    }
  }

  /**
   * The right-hand side of a oneof member's decode assignment: the per-member wrapper record's
   * constructor call carrying the decoded value.
   */
  private static String oneofDecodeRhs(
      Oneof o, Field f, TypeCollector ci, @Nullable Function<Field, String> typeResolver) {
    String iface = o.ifaceName();
    String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
    if (f.isMessage()) {
      String elem = f.typeUrl();
      String type = elem.substring(elem.lastIndexOf('.') + 1);
      String codecRef = type + "." + type + "Codec";
      return "new " + recName + "(" + codecRef + ".decode(in.readByteArray()))";
    }
    // Scalar or enum member: wrap the decoded value in the per-member record.
    if (f.kind() == FieldKind.ENUM) {
      String enumShort = typeResolver != null ? typeResolver.apply(f) : ci.use(f.typeUrl());
      return "new " + recName + "(" + enumShort + ".forNumber(in.readEnum()))";
    }
    // Scalar member.
    WireScalar wire = wireScalar(f);
    String readCall = decodeCall(wire);
    return switch (wire) {
      case STRING -> "new " + recName + "(in.readString())";
      case BYTES -> {
        ci.use(RT + ".Bytes");
        yield "new " + recName + "(Bytes.of(in.readBytes().toByteArray()))";
      }
      default -> "new " + recName + "(" + readCall + ")";
    };
  }

  /** The wire primitive families, mirroring {@code CodecGenerator}'s private {@code WireScalar}. */
  private enum WireScalar {
    INT32,
    UINT32,
    SINT_32,
    FIXED32,
    INT64,
    UINT64,
    SINT_64,
    FIXED64,
    FLOAT,
    DOUBLE,
    BOOL,
    STRING,
    BYTES
  }
}
