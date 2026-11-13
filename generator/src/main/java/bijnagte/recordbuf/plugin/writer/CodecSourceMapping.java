package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.Switch;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.codegen.source.While;
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
 * <p>Wires into {@link RecordSourceMapping} by appending {@code .nested(codec)} to the record's
 * member list.
 */
public final class CodecSourceMapping {

  private static final String RT = "bijnagte.recordbuf.runtime";

  private static final TypeRef ENCODER = TypeRef.of(RT + ".Encoder");
  private static final TypeRef DECODER = TypeRef.of(RT + ".Decoder");
  private static final TypeRef UNKNOWN_FIELDS = TypeRef.of(RT + ".UnknownFields");
  private static final TypeRef CODEC_DEFAULTS = TypeRef.of(RT + ".CodecDefaults");
  private static final TypeRef WIRE = TypeRef.of(RT + ".Wire");
  private static final TypeRef BYTES = TypeRef.of(RT + ".Bytes");

  private CodecSourceMapping() {}

  /**
   * Map a message model to a nested {@code Codec} class node. The codec is scoped statically inside
   * the enclosing record (its parent is the record's name, set by the caller via the {@link
   * Type.Class#parent}), and has a private constructor + a static {@code encode} + a static {@code
   * decode}.
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
    // Tree-walker-aware: collect(ci) registers each FQN; resolve() gives the collision-aware short.
    ENCODER.collect(ci);
    String encoderShort = ENCODER.render(ci.resolutionContext());
    DECODER.collect(ci);
    String decoderShort = DECODER.render(ci.resolutionContext());
    UNKNOWN_FIELDS.collect(ci);
    String unknownFieldsShort = UNKNOWN_FIELDS.render(ci.resolutionContext());
    CODEC_DEFAULTS.collect(ci);
    String codecDefaultsShort = CODEC_DEFAULTS.render(ci.resolutionContext());
    WIRE.collect(ci);
    String wireShort = WIRE.render(ci.resolutionContext());
    BYTES.collect(ci);
    String bytesShort = BYTES.render(ci.resolutionContext());
    TypeRef.of("java.util.HashMap").collect(ci);
    TypeRef.of("java.util.ArrayList").collect(ci);
    String codecName = m.name() + "Codec"; // M4-C2b-2b-8: align with old SampleCodec naming
    Type.Class.Builder result =
        Type.Class.named(codecName)
            .public_()
            .static_()
            .final_()
            .javadoc(
                Javadoc.builder()
                    .text("Wire codec for the ", m.name(), " record.")
                    .build()); // M4-C2b-2b-8: <Name>Codec to align with old path
    result.constructor(Constructor.builder().private_().build());
    result.method(
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
    result.method(
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

    return result.build();
  }

  /** The {@code byte[] encode(Message v) throws IOException} method body. */
  private static Method encode(
      Message m,
      TypeCollector ci,
      String encoderShort,
      String decoderShort,
      String unknownFieldsShort,
      String codecDefaultsShort,
      String wireShort,
      String bytesShort,
      @Nullable Function<Field, String> typeResolver) {
    List<Element> body = new ArrayList<>();
    body.add(Element.expr("var enc = @0.start();", encoderShort));
    body.add(Element.of("var out = enc.out();"));

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
        If.condition("!v.unknownFields().isEmpty()")
            .body(Element.expr("@0.writeUnknown(out, v.unknownFields());", wireShort))
            .build());

    body.add(Element.of("return enc.finish();"));

    return Method.named("encode")
        .public_()
        .static_()
        .returnType("byte[]")
        .param(m.name(), "v")
        .thrown("java.io.IOException")
        .body(body)
        .build();
  }

  /** The {@code Message decode(byte[] inBytes) throws IOException} method body. */
  private static Method decode(
      Message m,
      TypeCollector ci,
      String encoderShort,
      String decoderShort,
      String unknownFieldsShort,
      String codecDefaultsShort,
      String wireShort,
      String bytesShort,
      @Nullable Function<Field, String> typeResolver) {
    List<Element> body = new ArrayList<>();
    body.add(Element.expr("var dec = @0.start(inBytes);", decoderShort));
    body.add(Element.of("var in = dec.in();"));

    // M4-C2b-2b-5: the unknown-fields accumulator, initialised as a fresh builder.
    body.add(Element.expr("var unknown = @0.builder();", unknownFieldsShort));

    // Per-field local default-init.
    for (Field f : m.fields()) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        body.add(WktSourceMapping.localInitElement(f, wkt0, ci));
      } else if (f.kind() == FieldKind.MAP) {
        String keyType = boxedScalarShortName(Objects.requireNonNull(f.mapKey()));
        String valueType = boxedScalarShortName(Objects.requireNonNull(f.mapValue()));
        body.add(
            Element.expr("Map<@0, @1> @2 = new HashMap<>();", keyType, valueType, f.javaName()));
      } else if (f.kind() == FieldKind.MESSAGE) {
        if (typeResolver != null) {
          // The TYPE short name for the local init.
          String typeShort = f.typeUrl().substring(f.typeUrl().lastIndexOf('.') + 1);
          if (f.label() == FieldLabel.REPEATED) {
            body.add(Element.expr("List<@0> @1 = new ArrayList<>();", typeShort, f.javaName()));
          } else {
            body.add(Element.expr("@0 @1 = null;", typeShort, f.javaName()));
          }
        }
      } else if (f.kind() == FieldKind.ENUM) {
        // M4-C2b-2b-9: enum fields — singular → null, repeated → ArrayList.
        // typeResolver gives the enum short name; no typeResolver means unresolvable.
        if (typeResolver != null) {
          String enumShort = typeResolver.apply(f);
          if (f.label() == FieldLabel.REPEATED) {
            body.add(Element.expr("List<@0> @1 = new ArrayList<>();", enumShort, f.javaName()));
          } else {
            body.add(Element.expr("@0 @1 = null;", enumShort, f.javaName()));
          }
        } else {
          if (f.label() == FieldLabel.REPEATED) {
            body.add(Element.expr("List<?> @0 = new ArrayList<>();", f.javaName()));
          } else {
            body.add(Element.expr("Object @0 = null;", f.javaName()));
          }
        }
      } else if (f.label() == FieldLabel.REPEATED) {
        emitRepeatedLocalInit(f, body);
      } else if (f.isNullable()) {
        // M4-C2d-1: optional scalar → starts at null, set on tag.
        String localType = boxedScalarShortName(f.typeUrl());
        body.add(Element.expr("@0 @1 = null;", localType, f.javaName()));
      } else {
        String localType = decodeLocalType(f);
        String defaultExpr = codecDefaultsShort + "." + defaultMethod(f) + "()";
        body.add(Element.expr("@0 @1 = @2", localType, f.javaName(), defaultExpr));
      }
    }

    // Oneof locals: each defaults to null (the absent case). Proto3 oneof semantics: last
    // same-tag member seen wins, so a repeated same-tag member overwrites the prior value.
    for (Oneof o : m.oneofs()) {
      body.add(Element.expr("@0 @1 = null;", o.ifaceName(), o.componentName()));
    }

    // Tag loop: a while-loop wrapping a pattern-matching switch of tag-case arms.
    List<Element> tagLoop = new ArrayList<>();
    tagLoop.add(Element.expr("int tag = @0.readTag(in);", wireShort));
    tagLoop.add(If.condition("tag == 0").body(Element.of("break;")).build());
    Switch.Builder switchB = Switch.on("@0.tagFieldNumber(tag)", wireShort);
    for (Field f : m.fields()) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        WktSourceMapping.emitDecodeCase(f, wkt0, "in", "Wkt", switchB);
      } else if (f.kind() == FieldKind.MAP) {
        emitMapDecodeCase(f, ci, switchB);
      } else if (f.kind() == FieldKind.MESSAGE) {
        if (f.label() == FieldLabel.REPEATED) {
          emitRepeatedMessageDecodeCase(f, typeResolver, switchB);
        } else {
          emitMessageDecodeCase(f, typeResolver, switchB);
        }
      } else if (f.kind() == FieldKind.ENUM) {
        if (f.label() == FieldLabel.REPEATED) {
          emitRepeatedEnumDecodeCase(f, typeResolver, switchB);
        } else {
          emitEnumDecodeCase(f, typeResolver, switchB);
        }
      } else if (f.label() == FieldLabel.REPEATED) {
        emitRepeatedScalarDecodeCase(f, switchB);
      } else {
        emitScalarDecodeCase(f, switchB);
      }
    }
    // Oneof decode cases: each member contributes a case that assigns the oneof local.
    for (Oneof o : m.oneofs()) {
      emitOneofDecodeCase(o, ci, typeResolver, switchB);
    }
    // M4-C2b-2b-5: the default case captures the unknown field into the builder.
    switchB.defaultBody(Element.of("unknown.add(in, tag);"));
    tagLoop.add(switchB.build());
    body.add(While.condition("true").body(tagLoop).build());

    // Construct the record, passing the carrier as the last argument.
    List<String> allNames = new ArrayList<>();
    for (Field f : m.fields()) allNames.add(f.javaName());
    // M4-C2b-2b-12: oneof components: pass the oneof local (assigned in the tag loop, or null).
    for (var oneof : m.oneofs()) allNames.add(oneof.componentName());
    allNames.add("unknown.build()");
    body.add(Element.expr("return new @0(@1)", m.name(), joinCommas(allNames)));

    return Method.named("decode")
        .public_()
        .static_()
        .returnType(m.name())
        .param("byte[]", "inBytes")
        .thrown("java.io.IOException")
        .body(body)
        .build();
  }

  // --- Per-field encode ---

  private static void emitScalarEncode(Field f, List<Element> out) {
    // M4-C2b-2b-13: minimal MAP encode (placeholder — full wiring deferred).
    if (f.kind() == FieldKind.MAP) {
      out.add(Element.comment("M4-C2b-2b-13: MAP encode for field " + f.javaName() + " deferred"));
      return;
    }
    if (f.kind() != FieldKind.SCALAR) {
      out.add(Element.comment("unsupported kind " + f.kind() + " for field " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    String call = encodeCall(wire);
    if (f.isNullable()) {
      // M4-C2d-1: optional scalar / optional string → @Nullable wrapper → skip if null.
      out.add(
          If.condition("@0 != null", getter)
              .body(Element.expr("out.@0(@1, @2)", call, num, getter))
              .build());
      return;
    }
    switch (wire) {
      case STRING ->
          out.add(
              If.condition("!@0.isEmpty()", getter)
                  .body(Element.expr("out.@0(@1, @2)", call, num, getter))
                  .build());
      case BYTES ->
          out.add(
              If.condition("!@0.isEmpty()", getter)
                  .body(Element.expr("out.@0(@1, @2.toByteArray())", call, num, getter))
                  .build());
      default ->
          out.add(
              If.condition("@0 != CodecDefaults.@1()", getter, defaultMethod(f))
                  .body(Element.expr("out.@0(@1, @2)", call, num, getter))
                  .build());
    }
  }

  // --- Per-field MESSAGE encode/decode (M4-C2d-5) ---

  /** M4-C2d-5: singular MESSAGE encode — skip null, else writeByteArray(tag, Codec.encode(v)). */
  private static void emitMessageEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Element> out) {
    if (typeResolver == null) {
      out.add(Element.of("// M4-C2d-5: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    String codecRef = typeResolver.apply(f);
    // Singular MESSAGE encode: skip when null, else write the nested codec bytes.
    out.add(
        If.condition("@0 != null", getter)
            .body(Element.expr("out.writeByteArray(@0, @1.encode(@2))", num, codecRef, getter))
            .build());
  }

  /** M4-C2d-5: repeated MESSAGE encode — one writeByteArray per element. */
  private static void emitRepeatedMessageEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Element> out) {
    if (typeResolver == null) {
      out.add(Element.of("// M4-C2d-5: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    String codecRef = typeResolver.apply(f);
    out.add(
        For.enhanced("var _m", getter)
            .body(Element.expr("out.writeByteArray(@0, @1.encode(_m))", num, codecRef))
            .build());
  }

  // --- Per-field ENUM encode/decode (M4-C2b-2b-9) ---

  /** M4-C2b-2b-9: singular ENUM encode — skip null, else writeEnum with getNumber(). */
  private static void emitEnumEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Element> out) {
    if (typeResolver == null) {
      out.add(Element.of("// M4-C2b-2b-9: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    out.add(
        If.condition("@0 != null", getter)
            .body(Element.expr("out.writeEnum(@0, @1.getNumber())", num, getter))
            .build());
  }

  /** M4-C2b-2b-9: repeated ENUM encode — one writeEnum per element. */
  private static void emitRepeatedEnumEncode(
      Field f, @Nullable Function<Field, String> typeResolver, List<Element> out) {
    if (typeResolver == null) {
      out.add(Element.of("// M4-C2b-2b-9: typeResolver null; skip " + f.javaName()));
      return;
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    out.add(
        For.enhanced("var _e", getter)
            .body(Element.expr("out.writeEnum(@0, _e.getNumber())", num))
            .build());
  }

  /** M4-C2b-2b-9: singular ENUM decode — assign EnumName.forNumber(in.readEnum()). */
  private static void emitEnumDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, Switch.Builder sw) {
    if (typeResolver == null) {
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String enumName = typeResolver.apply(f);
    sw.caseArm(
        String.valueOf(num), Element.expr("@0 = @1.forNumber(in.readEnum())", local, enumName));
  }

  /** M4-C2b-2b-9: repeated ENUM decode — add EnumName.forNumber(in.readEnum()) to the list. */
  private static void emitRepeatedEnumDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, Switch.Builder sw) {
    if (typeResolver == null) {
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String enumName = typeResolver.apply(f);
    sw.caseArm(
        String.valueOf(num), Element.expr("@0.add(@1.forNumber(in.readEnum()))", local, enumName));
  }

  // --- Per-field MESSAGE decode case (M4-C2d-5) ---

  /** M4-C2d-5: singular MESSAGE decode — read length-delimited and decode via the codec. */
  private static void emitMessageDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, Switch.Builder sw) {
    if (typeResolver == null) {
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String codecRef = typeResolver.apply(f);
    sw.caseArm(
        String.valueOf(num), Element.expr("@0 = @1.decode(in.readByteArray())", local, codecRef));
  }

  /** M4-C2d-5: repeated MESSAGE decode — read length-delimited, add to the list. */
  private static void emitRepeatedMessageDecodeCase(
      Field f, @Nullable Function<Field, String> typeResolver, Switch.Builder sw) {
    if (typeResolver == null) {
      return;
    }
    int num = f.number();
    String local = f.javaName();
    String codecRef = typeResolver.apply(f);
    sw.caseArm(
        String.valueOf(num),
        Element.expr("@0.add(@1.decode(in.readByteArray()))", local, codecRef));
  }

  // --- Per-field decode case ---

  private static void emitScalarDecodeCase(Field f, Switch.Builder sw) {
    if (f.kind() != FieldKind.SCALAR) return;
    int num = f.number();
    String local = f.javaName();
    String pattern = String.valueOf(num);
    switch (wireScalar(f)) {
      case STRING -> sw.caseArm(pattern, Element.expr("@0 = in.readString()", local));
      case BYTES ->
          sw.caseArm(pattern, Element.expr("@0 = Bytes.of(in.readBytes().toByteArray())", local));
      default -> sw.caseArm(pattern, Element.expr("@0 = @1", local, decodeCall(wireScalar(f))));
    }
  }

  /** M4-C2d-7: MAP encode — iterate the map's entry set, encode each entry via its entry codec. */
  @SuppressWarnings("java:S905")
  private static void emitMapEncode(Field f, TypeCollector ci, List<Element> out) {
    String entryName = mapEntryName(f);
    out.add(
        For.enhanced("var _e", "v.@0().entrySet()", f.javaName())
            .body(
                Element.expr(
                    "out.writeByteArray(@0, @1.@1Codec.encode(new @1(_e.getKey(), _e.getValue())))",
                    f.number(),
                    entryName))
            .build());
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

  private static void emitRepeatedScalarEncode(Field f, List<Element> out) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    String readOp = decodeCall(wire);
    // Packed scalar: emit one length-delimited group when the list is non-empty.
    out.add(
        If.condition("!@0.isEmpty()", getter)
            .body(
                Element.expr("var body = @0.start()", ENCODER),
                Element.expr("var _o = @0.out()", "body"),
                For.enhanced("var _x", getter)
                    .body(Element.expr("_o.@0(_x)", packedNoTag(wire)))
                    .build(),
                Element.expr("out.writeByteArray(@0, body.finish())", num))
            .build());
  }

  private static void emitRepeatedScalarDecodeCase(Field f, Switch.Builder sw) {
    int num = f.number();
    String local = f.javaName();
    WireScalar wire = wireScalar(f);
    sw.caseArm(
        String.valueOf(num),
        Element.of("int _lim = in.pushLimit(in.readRawVarint32());"),
        Element.expr("while (in.getBytesUntilLimit() > 0) @0.add(@1)", local, decodeCall(wire)),
        Element.of("in.popLimit(_lim);"));
  }

  private static void emitRepeatedLocalInit(Field f, List<Element> out) {
    out.add(
        Element.expr(
            "List<@0> @1 = new ArrayList<>()", boxedScalarShortName(f.typeUrl()), f.javaName()));
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
    ENCODER.collect(ci);
    String encoderShort = ENCODER.render(ci.resolutionContext());
    DECODER.collect(ci);
    String decoderShort = DECODER.render(ci.resolutionContext());
    UNKNOWN_FIELDS.collect(ci);
    String unknownFieldsShort = UNKNOWN_FIELDS.render(ci.resolutionContext());
    CODEC_DEFAULTS.collect(ci);
    String codecDefaultsShort = CODEC_DEFAULTS.render(ci.resolutionContext());
    WIRE.collect(ci);
    String wireShort = WIRE.render(ci.resolutionContext());
    BYTES.collect(ci);

    // M4-C2d-12: resolve key/value wire scalars from the Field's type URLs.
    WireScalar keyWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapKey()));
    WireScalar valWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue()));
    String codecName = entryName + "Codec";
    Type.Class.Builder result = Type.Class.named(codecName).public_().static_().final_();

    result.constructor(Constructor.builder().private_().build());

    List<Element> encBody = new ArrayList<>();
    encBody.add(Element.expr("var enc = @0.start();", encoderShort));
    encBody.add(Element.of("var out = enc.out();"));

    // key: field 1 — always field 1 in a map entry; written unconditionally.
    // A bytes-valued key unwraps to the raw array (Bytes.toByteArray()); every other writes
    // through.
    String keyArg = keyWire == WireScalar.BYTES ? "v.key().toByteArray()" : "v.key()";
    encBody.add(Element.expr("out.@0(1, @1);", encodeCall(keyWire), keyArg));
    // value: field 2 — always field 2; written unconditionally, same unwrapping rule.
    String valArg = valWire == WireScalar.BYTES ? "v.value().toByteArray()" : "v.value()";
    encBody.add(Element.expr("out.@0(2, @1);", encodeCall(valWire), valArg));
    encBody.add(Element.of("return enc.finish();"));

    result.method(
        Method.named("encode")
            .public_()
            .static_()
            .returnType("byte[]")
            .param(entryName, "v")
            .thrown("java.io.IOException")
            .body(encBody)
            .build());

    List<Element> decBody = new ArrayList<>();
    decBody.add(Element.expr("var dec = @0.start(inBytes);", decoderShort));
    decBody.add(Element.of("var in = dec.in();"));
    decBody.add(Element.expr("var unknown = @0.builder();", unknownFieldsShort));
    emitFieldLocalInit(decBody, "key", keyWire, codecDefaultsShort);
    emitFieldLocalInit(decBody, "value", valWire, codecDefaultsShort);
    List<Element> tagLoop = new ArrayList<>();
    tagLoop.add(Element.expr("int tag = @0.readTag(in);", wireShort));
    tagLoop.add(If.condition("tag == 0").body(Element.of("break;")).build());
    Switch.Builder switchB = Switch.on("@0.tagFieldNumber(tag)", wireShort);
    emitFieldDecodeCase(switchB, 1, "key", keyWire, wireShort);
    emitFieldDecodeCase(switchB, 2, "value", valWire, wireShort);
    switchB.defaultBody(Element.of("unknown.add(in, tag);"));
    tagLoop.add(switchB.build());
    tagLoop.add(Element.of(""));
    decBody.add(While.condition("true").body(tagLoop).build());
    decBody.add(Element.expr("return new @0(key, value);", entryName));

    result.method(
        Method.named("decode")
            .public_()
            .static_()
            .returnType(entryName)
            .param("byte[]", "inBytes")
            .thrown("java.io.IOException")
            .body(decBody)
            .build());

    return result.build();
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
  private static void emitFieldLocalInit(List<Element> out, String name, WireScalar ws, String cd) {
    if (ws == WireScalar.STRING) {
      out.add(Element.expr("String @0 = \"\"", name));
    } else {
      out.add(Element.expr("@0 @1 = @2.@3()", localTypeForWire(ws), name, cd, defaultForWire(ws)));
    }
  }

  /** M4-C2d-12: emit a decode case line for a map entry's scalar field. */
  private static void emitFieldDecodeCase(
      Switch.Builder sw, int fieldNum, String name, WireScalar ws, String wire) {
    if (ws == WireScalar.STRING) {
      sw.caseArm(String.valueOf(fieldNum), Element.expr("@0 = in.readString()", name));
    } else {
      sw.caseArm(String.valueOf(fieldNum), Element.expr("@0 = @1", name, decodeCall(ws)));
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
  private static void emitMapDecodeCase(Field f, TypeCollector ci, Switch.Builder sw) {
    String entryName = mapEntryName(f);
    String local = f.javaName();
    int num = f.number();
    sw.caseArm(
        String.valueOf(num),
        Element.expr("var _entry = @0.@0Codec.decode(in.readByteArray())", entryName),
        Element.expr("@0.put(_entry.key(), _entry.value())", local));
  }

  // --- Oneof encode / decode ---

  /**
   * Emit the oneof group encode: a pattern-matching {@code switch} on the sealed-interface
   * component. {@code case null -> {}} skips; message members dispatch through their nested codec;
   * scalar/enum members dispatch through the wire primitive that matches their field type.
   */
  private static void emitOneofEncode(
      Oneof o,
      TypeCollector ci,
      @Nullable Function<Field, String> typeResolver,
      List<Element> out) {
    String iface = o.ifaceName();
    String cmp = o.componentName();
    String getter = "v." + cmp + "()";
    Switch.Builder sw = Switch.on(getter);
    sw.caseArm("null");
    for (Field f : o.members()) {
      String bnd = String.valueOf(f.name().charAt(0)) + "_"; // unique binding name
      if (f.isMessage()) {
        String elem = f.typeUrl();
        String type = elem.substring(elem.lastIndexOf('.') + 1);
        String sealedSub = iface + "." + JavaNames.cap(f.name()) + "Value";
        String codecRef = type + "." + type + "Codec";
        sw.caseArm(
            sealedSub + " " + bnd,
            Element.expr(
                "out.writeByteArray(@0, @1.encode(@2.value()))", f.number(), codecRef, bnd));
      } else if (f.kind() == FieldKind.ENUM) {
        // Enum oneof member: write via writeEnum, calling .getNumber() on the enum value.
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        sw.caseArm(
            recName + " " + bnd,
            Element.expr("out.writeEnum(@0, @1.value().getNumber())", f.number(), bnd));
      } else {
        // Scalar oneof member: dispatch on wire type.
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        WireScalar wire = wireScalar(f);
        String call = encodeCall(wire);
        sw.caseArm(
            recName + " " + bnd, Element.expr("out.@0(@1, @2.value())", call, f.number(), bnd));
      }
    }
    out.add(sw.build());
  }

  /**
   * Emit the decode case for each member of the oneof: a {@code case fieldNumber -> { ...}} that
   * assigns the oneof's shared local. A message member decodes via its nested codec; a scalar/enum
   * member decodes via the wire read primitive and wraps in the per-member record.
   */
  private static void emitOneofDecodeCase(
      Oneof o,
      TypeCollector ci,
      @Nullable Function<Field, String> typeResolver,
      Switch.Builder sw) {
    String cmp = o.componentName();
    for (Field f : o.members()) {
      sw.caseArm(
          String.valueOf(f.number()),
          Element.expr("@0 = @1", cmp, oneofDecodeRhs(o, f, ci, typeResolver)));
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
      // Tree-walker deferred: ci.use(f.typeUrl()) is the interop fallback for when
      // typeResolver is null. The tree-walker approach will handle this in a future unit.
      String enumShort = typeResolver != null ? typeResolver.apply(f) : ci.use(f.typeUrl());
      return "new " + recName + "(" + enumShort + ".forNumber(in.readEnum()))";
    }
    // Scalar member.
    WireScalar wire = wireScalar(f);
    String readCall = decodeCall(wire);
    return switch (wire) {
      case STRING -> "new " + recName + "(in.readString())";
      case BYTES -> {
        BYTES.collect(ci);
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
