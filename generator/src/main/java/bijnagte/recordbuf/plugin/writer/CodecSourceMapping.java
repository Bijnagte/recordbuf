package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.Case;
import bijnagte.recordbuf.codegen.source.ClassSpec;
import bijnagte.recordbuf.codegen.source.CommaList;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.LocalVar;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.Switch;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.codegen.source.While;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import bijnagte.recordbuf.plugin.types.WellKnownTypes.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maps a proto {@link Message} model into a nested {@link ClassSpec} {@code Codec} node for the
 * source AST. Handles all field families: scalars, repeated scalars, optional scalars/strings,
 * maps, nested messages, singular/repeated enums, oneofs, and WKT fields.
 *
 * <p>Wires into {@link RecordSourceMapping} by appending {@code .nested(codec)} to the record's
 * member list.
 */
public final class CodecSourceMapping {

  private CodecSourceMapping() {}

  /**
   * Map a message model to a nested {@code Codec} class node. The codec is scoped statically inside
   * the enclosing record (attached via {@link RecordSpec#nested}), and has a private constructor +
   * a static {@code encode} + a static {@code decode}.
   *
   * @param m the message model
   * @return the nested {@code ClassSpec} codec node
   */
  public static ClassSpec toCodec(Message m) {
    return doToCodec(m, false);
  }

  /**
   * The nested codec for a {@code recordbuf.jackson} record (J6): on decode the record's canonical
   * constructor is passed a {@code null} extras argument (normalized to a fresh empty map) — a
   * decoded record has an empty extras map. The {@code jsonExtras} component is JSON-only: the
   * encode never writes it to the wire, so a property name can never occupy (or collide with) a
   * field number; the binary unknown-field carrier round-trips exactly as before.
   */
  public static ClassSpec toCodec(Message m, boolean jackson) {
    return doToCodec(m, jackson);
  }

  private static ClassSpec doToCodec(Message m, boolean jackson) {
    // Every type the codec body references (encoder/decoder/unknownFields/wire/codecDefaults,
    // plus the HashMap/ArrayList/Bytes constructors and CodecDefaults.<x>() default calls) is now
    // a TypeRef positional arg of a RawExpr, so the renderer's collect walk registers + resolves
    // it; no explicit collect/render is needed here.
    String recName = m.type().shortName();
    String codecName = recName + "Codec";
    TypeRef.Named codecType = m.type().sub(codecName);
    return ClassSpec.named(codecType)
        .public_()
        .static_()
        .final_()
        .javadoc(GeneratedDocs.type(null, "Wire codec for the " + recName + " record."))
        .constructor(Constructor.builder().private_().build())
        .method(encode(m))
        .method(encodeTo(m))
        .method(writeBody(m))
        .method(getSerializedSize(m))
        .method(scopedDecode(m.type(), codecType))
        .method(decodeBody(m, jackson))
        .build();
  }

  /**
   * The {@code byte[] encode(Message v)} method: pre-allocated buffer, delegates to {@code
   * writeBody}.
   */
  private static Method encode(Message m) {
    return Method.named("encode")
        .public_()
        .static_()
        .returnType(TypeRef.BYTES)
        .param(m.type(), "v")
        .thrown(TypeRef.IO_EXCEPTION)
        .body(
            Element.expr("int size = getSerializedSize(v)"),
            Element.expr("byte[] buf = new byte[size]"),
            Element.expr("var out = @0.newInstance(buf)", TypeRefs.CODED_OUTPUT_STREAM),
            Element.expr("writeBody(out, v)"),
            Element.ret("buf"))
        .build();
  }

  /**
   * The {@code void encodeTo(CodedOutputStream out, int fieldNum, Message v)} method: writes the
   * tag, length prefix, and body directly to {@code out} without allocating an intermediate {@code
   * byte[]}. Used by the parent codec for nested message fields.
   */
  private static Method encodeTo(Message m) {
    return Method.named("encodeTo")
        .public_()
        .static_()
        .param(TypeRefs.CODED_OUTPUT_STREAM, "out")
        .param(TypeRef.Primitive.INT, "fieldNum")
        .param(m.type(), "v")
        .thrown(TypeRef.IO_EXCEPTION)
        .annotation(Annotation.suppressWarnings("deprecation"))
        .body(
            Element.expr("int size = getSerializedSize(v)"),
            Element.expr("out.writeRawVarint32((fieldNum << 3) | 2)"),
            Element.expr("out.writeRawVarint32(size)"),
            Element.expr("writeBody(out, v)"))
        .build();
  }

  /**
   * The private {@code writeBody(CodedOutputStream out, Message v)} method: writes all fields
   * directly to {@code out}. Shared by {@link #encode} and {@link #encodeTo}.
   */
  private static Method writeBody(Message m) {
    var method =
        Method.named("writeBody")
            .private_()
            .static_()
            .param(TypeRefs.CODED_OUTPUT_STREAM, "out")
            .param(m.type(), "v")
            .thrown(TypeRef.IO_EXCEPTION);

    // Write slots in ascending field-number order.
    List<EncodeSlot> slots = new ArrayList<>();
    for (Field f : m.fields()) {
      slots.add(new EncodeSlot(f.number(), regularFieldEncode(f)));
    }
    for (Oneof o : m.oneofs()) {
      slots.addAll(oneofEncodeSlots(o));
    }
    slots.sort(Comparator.comparingInt(EncodeSlot::number));
    for (EncodeSlot slot : slots) {
      method.body(slot.element());
    }

    // M4-C2b-2b-5: re-emit the unknown-fields carrier, if any, preserving wire-order round-trip.
    Element unknownArm =
        If.condition("!v.unknownFields().isEmpty()")
            .body(Element.expr("v.unknownFields().writeTo(out)"))
            .build();
    method.body(unknownArm);
    return method.build();
  }

  /** The {@code int getSerializedSize(Message v)} method body. */
  private static Method getSerializedSize(Message m) {
    var method =
        Method.named("getSerializedSize")
            .public_()
            .static_()
            .returnType(TypeRef.Primitive.INT)
            .param(m.type(), "v")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(Element.expr("int size = 0"));

    List<EncodeSlot> slots = new ArrayList<>();
    for (Field f : m.fields()) {
      slots.add(new EncodeSlot(f.number(), sizeFieldEncode(f)));
    }
    for (Oneof o : m.oneofs()) {
      slots.addAll(oneofSizeSlots(o));
    }
    slots.sort(Comparator.comparingInt(EncodeSlot::number));
    for (EncodeSlot slot : slots) {
      method.body(slot.element());
    }

    method.body(Element.expr("size += v.unknownFields().getSerializedSize()"), Element.ret("size"));
    return method.build();
  }

  /**
   * Size-computation slots for oneof members: mirrors {@link #oneofEncodeSlots} but emits {@code
   * size += CodedOutputStream.computeXxxSize(...)} instead of {@code out.writeXxx(...)}.
   */
  private static List<EncodeSlot> oneofSizeSlots(Oneof o) {
    String iface = o.ifaceName();
    String getter = "v." + o.componentName() + "()";
    List<EncodeSlot> slots = new ArrayList<>();
    for (Field f : o.members()) {
      String bnd = String.valueOf(f.name().charAt(0)) + "_";
      String recName = iface + "." + Oneof.valueRecordName(f);
      Entry wktMember = WktSourceMapping.resolve(f);
      boolean deferred = f.kind() == FieldKind.MAP;
      String cond = deferred ? "@0 instanceof @1" : "@0 instanceof @1 " + bnd;
      Element size;
      if (deferred) {
        size = Element.comment("oneof size for " + f.name() + " deferred");
      } else if (wktMember != null) {
        // A WKT member writes through the same expression the encode arm uses (Wkt.<method>,
        // Wkt.wrap, or DynamicWkt.encode), so its size is tag + length + those encoded bytes.
        size =
            Element.expr(
                "size += Wire.lenDelimSize(@0, @1)",
                f.number(),
                WktSourceMapping.sizeValue(f, wktMember, bnd + ".value()"));
      } else {
        size =
            switch (f.kind()) {
              case FieldKind.MESSAGE ->
                  Element.expr(
                      "size += Wire.lenDelimSize(@0, @1.@1Codec.getSerializedSize("
                          + bnd
                          + ".value()))",
                      f.number(),
                      codecShortName(f));
              case FieldKind.ENUM ->
                  Element.expr(
                      "size += @0.computeInt32Size(@1, " + bnd + ".value().getNumber())",
                      TypeRefs.CODED_OUTPUT_STREAM,
                      f.number());
              case FieldKind.SCALAR -> {
                WireScalar wire = wireScalar(f);
                if (wire == WireScalar.BYTES) {
                  yield Element.expr(
                      "size += @0.computeTagSize(@1) + @0.computeInt32Size(1, "
                          + bnd
                          + ".value().size()) - 1 + "
                          + bnd
                          + ".value().size()",
                      TypeRefs.CODED_OUTPUT_STREAM,
                      f.number());
                }
                String call = sizeCall(wire);
                yield Element.expr(
                    "size += @0.@1(@2, @3)",
                    TypeRefs.CODED_OUTPUT_STREAM,
                    call,
                    f.number(),
                    bnd + ".value()");
              }
              default -> Element.comment("unsupported oneof size kind " + f.kind());
            };
      }
      slots.add(new EncodeSlot(f.number(), If.condition(cond, getter, recName).body(size).build()));
    }
    return slots;
  }

  /**
   * The public {@code Message decode(byte[] inBytes) throws IOException} method. It hands the
   * {@code decodeBody} tag loop to {@code DecodeScope}, which bounds nesting depth and nested
   * bytes.
   */
  private static Method scopedDecode(TypeRef returnType, TypeRef.Named codecType) {
    return Method.named("decode")
        .public_()
        .static_()
        .returnType(returnType)
        .param(TypeRef.BYTES, "inBytes")
        .thrown(TypeRef.IO_EXCEPTION)
        .body(Element.ret("@0.decode(inBytes, @1::decodeBody)", TypeRefs.DECODE_SCOPE, codecType))
        .build();
  }

  /** The private {@code decodeBody} the scoped {@code decode} runs: one level's tag loop. */
  private static Method decodeBody(Message m, boolean jackson) {
    var method =
        Method.named("decodeBody")
            .private_()
            .static_()
            .returnType(m.type())
            .param(TypeRef.BYTES, "inBytes")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(
                Element.expr("var dec = @0.start(inBytes)", TypeRefs.DECODER),
                Element.expr("var in = dec.in()"))

            // M4-C2b-2b-5: the unknown-fields accumulator, initialised as a fresh builder.
            .body(Element.expr("var unknown = @0.builder()", TypeRefs.UNKNOWN_FIELDS));

    // Per-field local default-init.
    for (Field f : m.fields()) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        method.body(WktSourceMapping.localInitElement(f, wkt0));
      } else {
        switch (f.kind()) {
          case FieldKind.MAP -> {
            method.body(
                Element.expr("@0 @1 = new @2<>()", f.type(), f.javaName(), TypeRefs.HASH_MAP));
          }
          case FieldKind.MESSAGE -> {
            if (f.label() == FieldLabel.REPEATED) {
              method.body(
                  LocalVar.builder(f.type(), f.javaName())
                      .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)));
            } else {
              method.body(LocalVar.builder(f.type(), f.javaName()).assigned(RawExpr.of("null")));
            }
          }
          case FieldKind.ENUM -> {
            // M4-C2b-2b-9: enum fields — singular → null, repeated → ArrayList.
            if (f.label() == FieldLabel.REPEATED) {
              method.body(
                  LocalVar.builder(f.type(), f.javaName())
                      .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)));
            } else {
              method.body(LocalVar.builder(f.type(), f.javaName()).assigned(RawExpr.of("null")));
            }
          }
          case FieldKind.SCALAR -> {
            if (f.label() == FieldLabel.REPEATED) {
              method.body(emitRepeatedLocalInit(f));
            } else if (f.isNullable()) {
              // M4-C2d-1: optional scalar → starts at null, set on tag.
              TypeRef localType = TypeRef.boxedFor(f.typeUrl());
              method.body(Element.expr("@0 @1 = null", localType, f.javaName()));
            } else {
              TypeRef localTypeRef = decodeLocalType(f);
              method.body(
                  Element.expr(
                      "@0 @1 = @2.@3()",
                      localTypeRef,
                      f.javaName(),
                      TypeRefs.CODEC_DEFAULTS,
                      defaultMethod(f)));
            }
          }
        }
      }
    }

    // Oneof locals: each defaults to null (the absent case). Proto3 oneof semantics: last
    // same-tag member seen wins, so a repeated same-tag member overwrites the prior value.
    TypeRef.Named recordRef = m.type();
    for (Oneof o : m.oneofs()) {
      method.body(Element.expr("@0 @1 = null", o.iface(), o.componentName()));
    }

    // Tag loop: a while-loop wrapping a pattern-matching switch of tag-case arms.

    Switch.Builder switchB = Switch.on("@0.tagFieldNumber(tag)", TypeRefs.WIRE);
    for (Field f : m.fields()) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        switchB.caseArm(WktSourceMapping.emitDecodeCase(f, wkt0, "in"));
      } else {
        switch (f.kind()) {
          case FieldKind.MAP -> switchB.caseArm(emitMapDecodeCase(f));
          case FieldKind.MESSAGE -> {
            Case c =
                f.label() == FieldLabel.REPEATED
                    ? emitRepeatedMessageDecodeCase(f)
                    : emitMessageDecodeCase(f);
            if (c != null) switchB.caseArm(c);
          }
          case FieldKind.ENUM -> {
            Case c =
                f.label() == FieldLabel.REPEATED
                    ? emitRepeatedEnumDecodeCase(f)
                    : emitEnumDecodeCase(f);
            if (c != null) switchB.caseArm(c);
          }
          case FieldKind.SCALAR -> {
            if (f.label() == FieldLabel.REPEATED) {
              switchB.caseArm(emitRepeatedScalarDecodeCase(f));
            } else {
              switchB.caseArm(emitScalarDecodeCase(f));
            }
          }
        }
      }
    }
    // Oneof decode cases: each member contributes a case that assigns the oneof local.
    for (Oneof o : m.oneofs()) {
      switchB.caseArm(emitOneofDecodeCase(o, recordRef));
    }
    // the default case captures the unknown field into the builder.
    switchB.defaultBody(Element.expr("unknown.add(in, tag)"));

    method.body(
        While.condition("true")
            .body(
                LocalVar.builder(TypeRef.Primitive.INT, "tag")
                    .assigned("@0.readTag(in)", TypeRefs.WIRE),
                If.condition("tag == 0").body(Element.of("break;")).build(),
                switchB.build())
            .build());

    // Construct the record, passing the carrier as the last argument.
    List<String> allNames = new ArrayList<>();
    for (Field f : m.fields()) allNames.add(f.javaName());
    // oneof components: pass the oneof local (assigned in the tag loop, or null).
    for (var oneof : m.oneofs()) allNames.add(oneof.componentName());
    if (jackson) allNames.add("null");
    allNames.add("unknown.build()");
    return method.body(Element.ret("new @0(@1)", m.type(), CommaList.of(allNames))).build();
  }

  // --- Per-field encode ---

  private static Element emitScalarEncode(Field f) {
    // M4-C2b-2b-13: minimal MAP encode (placeholder — full wiring deferred).
    if (f.kind() == FieldKind.MAP) {
      return Element.comment("M4-C2b-2b-13: MAP encode for field " + f.javaName() + " deferred");
    }
    if (f.kind() != FieldKind.SCALAR) {
      return Element.comment("unsupported kind " + f.kind() + " for field " + f.javaName());
    }
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    String call = encodeCall(wire);
    if (f.isNullable()) {
      // M4-C2d-1: optional scalar / optional string → @Nullable wrapper → skip if null.
      return If.condition("@0 != null", getter)
          .body(Element.expr("out.@0(@1, @2)", call, num, getter))
          .build();
    }
    Element encoded =
        switch (wire) {
          case STRING ->
              If.condition("!@0.isEmpty()", getter)
                  .body(Element.expr("out.@0(@1, @2)", call, num, getter))
                  .build();
          case BYTES ->
              If.condition("!@0.isEmpty()", getter)
                  .body(Element.expr("out.@0(@1, @2.toByteArray())", call, num, getter))
                  .build();
          default ->
              If.condition("@0 != CodecDefaults.@1()", getter, defaultMethod(f))
                  .body(Element.expr("out.@0(@1, @2)", call, num, getter))
                  .build();
        };
    return encoded;
  }

  // --- Per-field MESSAGE encode/decode (M4-C2d-5) ---

  /** M4-C2d-5: singular MESSAGE encode — skip null, else encodeTo(out, tag, Codec, v). */
  private static Element emitMessageEncode(Field f) {
    return If.condition("v.@0() != null", f.javaName())
        .body(Element.expr("@1.encodeTo(out, @0, v.@2())", f.number(), codecRef(f), f.javaName()))
        .build();
  }

  /** M4-C2d-5: repeated MESSAGE encode — one writeByteArray per element. */
  private static Element emitRepeatedMessageEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    return For.enhanced("var _m", getter)
        .body(Element.expr("@1.encodeTo(out, @0, _m)", f.number(), codecRef(f)))
        .build();
  }

  // --- Per-field ENUM encode/decode (M4-C2b-2b-9) ---

  /** M4-C2b-2b-9: singular ENUM encode — skip null, else writeEnum with getNumber(). */
  private static Element emitEnumEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    return If.condition("@0 != null", getter)
        .body(Element.expr("out.writeEnum(@0, @1.getNumber())", num, getter))
        .build();
  }

  /** M4-C2b-2b-9: repeated ENUM encode — one writeEnum per element. */
  private static Element emitRepeatedEnumEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    return For.enhanced("var _e", getter)
        .body(Element.expr("out.writeEnum(@0, _e.getNumber())", num))
        .build();
  }

  /** M4-C2b-2b-9: singular ENUM decode — assign EnumName.forNumber(in.readEnum()). */
  @Nullable
  private static Case emitEnumDecodeCase(Field f) {
    int num = f.number();
    String local = f.javaName();
    return Case.of(
        String.valueOf(num), Element.expr("@0 = @1.forNumber(in.readEnum())", local, codecRef(f)));
  }

  /** M4-C2b-2b-9: repeated ENUM decode — add EnumName.forNumber(in.readEnum()) to the list. */
  @Nullable
  private static Case emitRepeatedEnumDecodeCase(Field f) {
    int num = f.number();
    String local = f.javaName();
    return Case.of(
        String.valueOf(num),
        Element.expr("@0.add(@1.forNumber(in.readEnum()))", local, codecRef(f)));
  }

  // --- Per-field MESSAGE decode case (M4-C2d-5) ---

  /** M4-C2d-5: singular MESSAGE decode — read length-delimited and decode via the codec. */
  @Nullable
  private static Case emitMessageDecodeCase(Field f) {
    return Case.of(
        String.valueOf(f.number()),
        Element.expr("@0 = @1.decode(in.readByteArray())", f.javaName(), codecRef(f)));
  }

  /** M4-C2d-5: repeated MESSAGE decode — read length-delimited, add to the list. */
  @Nullable
  private static Case emitRepeatedMessageDecodeCase(Field f) {
    return Case.of(
        String.valueOf(f.number()),
        Element.expr("@0.add(@1.decode(in.readByteArray()))", f.javaName(), codecRef(f)));
  }

  /**
   * The typed reference a codec body expression uses for a message/enum field, derived from the
   * field's typed model: a message field references the {@code <Element>Codec} nested inside the
   * element's type (a field's {@code type} is {@code List<Element>} for a repeated field, so the
   * element is its generic argument — NOT the {@code List} itself), and an enum field references
   * the type itself. The renderer resolves the reference (import, collision, in-scope drop); no
   * name string is built here.
   */
  private static TypeRef codecRef(Field f) {
    TypeRef element = f.type();
    if (f.label() == FieldLabel.REPEATED && element instanceof TypeRef.Named n) {
      // A repeated field's type is List<Element> with the element as its generic argument.
      // (A hand-built test field may omit it; then the element IS the type.)
      element = n.generics().isEmpty() ? element : n.generics().get(0);
    }
    if (f.kind() == FieldKind.MESSAGE && element instanceof TypeRef.Named n) {
      // The codec is nested inside the element's own type: <Element>.<Element>Codec.
      return n.sub(n.shortName() + "Codec");
    }
    return element;
  }

  // --- Per-field decode case ---

  private static Case emitScalarDecodeCase(Field f) {
    int num = f.number();
    String local = f.javaName();
    String pattern = String.valueOf(num);
    return switch (wireScalar(f)) {
      case STRING -> Case.of(pattern, Element.expr("@0 = in.readString()", local));
      case BYTES ->
          Case.of(
              pattern,
              Element.expr("@0 = @1.of(in.readBytes().toByteArray())", local, TypeRefs.BYTES));
      default -> Case.of(pattern, Element.expr("@0 = @1", local, decodeCall(wireScalar(f))));
    };
  }

  /** M4-C2d-7: MAP encode — iterate the map's entry set, encode each entry via its entry codec. */
  private static Element emitMapEncode(Field f) {
    String entryName = mapEntryName(f);
    return For.enhanced("var _e", "v.@0().entrySet()", f.javaName())
        .body(
            Element.expr(
                "@1.@1Codec.encodeTo(out, @0, new @1(_e.getKey(), _e.getValue()))",
                f.number(),
                entryName))
        .build();
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
  private static TypeRef decodeLocalType(Field f) {
    if (f.kind() != FieldKind.SCALAR) return TypeRef.OBJECT;
    return switch (wireScalar(f)) {
      case STRING -> TypeRef.STRING;
      case BYTES -> TypeRefs.BYTES;
      case INT32, UINT32, SINT_32, FIXED32 -> TypeRef.Primitive.INT;
      case INT64, UINT64, SINT_64, FIXED64 -> TypeRef.Primitive.LONG;
      case FLOAT -> TypeRef.Primitive.FLOAT;
      case DOUBLE -> TypeRef.Primitive.DOUBLE;
      case BOOL -> TypeRef.Primitive.BOOLEAN;
      default -> TypeRef.OBJECT;
    };
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

  /** The {@code CodedOutputStream.computeXxxSize} static method name for a wire scalar. */
  private static String sizeCall(WireScalar wire) {
    return switch (wire) {
      case INT32, UINT32 -> "computeInt32Size";
      case SINT_32 -> "computeSInt32Size";
      case FIXED32 -> "computeFixed32Size";
      case INT64, UINT64 -> "computeInt64Size";
      case SINT_64 -> "computeSInt64Size";
      case FIXED64 -> "computeFixed64Size";
      case FLOAT -> "computeFloatSize";
      case DOUBLE -> "computeDoubleSize";
      case BOOL -> "computeBoolSize";
      case STRING -> "computeStringSize";
      case BYTES -> "computeBytesSize";
    };
  }

  /**
   * Size-computation element for a regular (non-oneof) field: mirrors {@link #regularFieldEncode}
   * but emits {@code size += CodedOutputStream.computeXxxSize(...)} instead of {@code
   * out.writeXxx(...)}.
   */
  private static Element sizeFieldEncode(Field f) {
    Entry wktEntry = WktSourceMapping.resolve(f);
    if (wktEntry != null) {
      return sizeWktEncode(f, wktEntry);
    }
    return switch (f.kind()) {
      case FieldKind.MAP -> sizeMapEncode(f);
      case FieldKind.MESSAGE ->
          f.label() == FieldLabel.REPEATED ? sizeRepeatedMessageEncode(f) : sizeMessageEncode(f);
      case FieldKind.ENUM ->
          f.label() == FieldLabel.REPEATED ? sizeRepeatedEnumEncode(f) : sizeEnumEncode(f);
      case FieldKind.SCALAR ->
          f.label() == FieldLabel.REPEATED ? sizeRepeatedScalarEncode(f) : sizeScalarEncode(f);
    };
  }

  /** Size computation for a WKT field: mirrors {@code WktSourceMapping.emitEncode}. */
  private static Element sizeWktEncode(Field f, Entry entry) {
    int num = f.number();
    String getter = "v." + f.javaName() + "()";
    if (entry.nodeType() != null) {
      // A dynamic WKT writes through DynamicWkt.encode("<fqn>", value) (the same expression the
      // encode arm uses), so its size is tag + length + those encoded bytes.
      if (f.label() == FieldLabel.REPEATED) {
        return For.enhanced("var e", getter)
            .body(
                Element.expr(
                    "size += Wire.lenDelimSize(@0, @1.length)",
                    num,
                    WktSourceMapping.encodeValue(f, entry, "e")))
            .build();
      }
      return If.condition("@0 != null", getter)
          .body(
              Element.expr(
                  "size += Wire.lenDelimSize(@0, @1.length)",
                  num,
                  WktSourceMapping.encodeValue(f, entry, getter)))
          .build();
    }
    String methodName = WktSourceMapping.sizeMethod(f.typeUrl());
    if (f.label() == FieldLabel.REPEATED) {
      if (entry.hasZeroDefault()) {
        String def = Objects.requireNonNull(entry.zeroDefault());
        return For.enhanced("var e", getter)
            .body(
                If.condition("!e.equals(@0)", def)
                    .body(
                        Element.expr(
                            "size += Wire.lenDelimSize(@0, @1.@2(e))",
                            num,
                            TypeRefs.WKT,
                            methodName))
                    .build())
            .build();
      }
      String kind = WktSourceMapping.wrapKind(f.typeUrl());
      return For.enhanced("var e", getter)
          .body(
              Element.expr(
                  "size += Wire.lenDelimSize(@0, @1.wrapSize(@1.Wrap.@2, e))",
                  num,
                  TypeRefs.WKT,
                  kind))
          .build();
    }
    String sizeExpr;
    if (entry.hasZeroDefault()) {
      sizeExpr = "size += Wire.lenDelimSize(@1, @2.@3(@4))";
      Object[] args = {TypeRefs.CODED_OUTPUT_STREAM, num, TypeRefs.WKT, methodName, getter};
      String def = Objects.requireNonNull(entry.zeroDefault());
      return If.condition("@0 != null && !@0.equals(@1)", getter, def)
          .body(Element.expr(sizeExpr, args))
          .build();
    }
    sizeExpr = "size += Wire.lenDelimSize(@1, @2.wrapSize(@2.Wrap.@3, @4))";
    String kind = WktSourceMapping.wrapKind(f.typeUrl());
    return If.condition("@0 != null", getter)
        .body(Element.expr(sizeExpr, TypeRefs.CODED_OUTPUT_STREAM, num, TypeRefs.WKT, kind, getter))
        .build();
  }

  /** Size computation for a MAP field: mirrors {@link #emitMapEncode}. */
  private static Element sizeMapEncode(Field f) {
    String entryName = mapEntryName(f);
    return For.enhanced("var _e", "v.@0().entrySet()", f.javaName())
        .body(
            Element.expr(
                "size += Wire.lenDelimSize(@0, @1.@1Codec.getSerializedSize(new @1(_e.getKey(), _e.getValue())))",
                f.number(),
                entryName))
        .build();
  }

  private static Element sizeScalarEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    String call = sizeCall(wire);
    if (f.isNullable()) {
      return If.condition("@0 != null", getter)
          .body(
              Element.expr(
                  "size += @0.@1(@2, @3)", TypeRefs.CODED_OUTPUT_STREAM, call, num, getter))
          .build();
    }
    return switch (wire) {
      case STRING ->
          If.condition("!@0.isEmpty()", getter)
              .body(
                  Element.expr(
                      "size += @0.@1(@2, @3)", TypeRefs.CODED_OUTPUT_STREAM, call, num, getter))
              .build();
      case BYTES ->
          If.condition("!@0.isEmpty()", getter)
              .body(Element.expr("size += Wire.lenDelimSize(@0, @1.size())", num, getter))
              .build();
      default ->
          If.condition("@0 != CodecDefaults.@1()", getter, defaultMethod(f))
              .body(
                  Element.expr(
                      "size += @0.@1(@2, @3)", TypeRefs.CODED_OUTPUT_STREAM, call, num, getter))
              .build();
    };
  }

  private static Element sizeMessageEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    return If.condition("@0 != null", getter)
        .body(
            Element.expr(
                "size += Wire.lenDelimSize(@0, @1.@1Codec.getSerializedSize(@2))",
                f.number(),
                codecShortName(f),
                getter))
        .build();
  }

  private static Element sizeRepeatedMessageEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    return For.enhanced("var _m", getter)
        .body(
            Element.expr(
                "size += Wire.lenDelimSize(@0, @1.@1Codec.getSerializedSize(_m))",
                f.number(),
                codecShortName(f)))
        .build();
  }

  private static Element sizeEnumEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    return If.condition("@0 != null", getter)
        .body(
            Element.expr(
                "size += @0.computeInt32Size(@1, @2.getNumber())",
                TypeRefs.CODED_OUTPUT_STREAM,
                f.number(),
                getter))
        .build();
  }

  private static Element sizeRepeatedEnumEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    return For.enhanced("var _e", getter)
        .body(
            Element.expr(
                "size += @0.computeInt32Size(@1, _e.getNumber())",
                TypeRefs.CODED_OUTPUT_STREAM,
                f.number()))
        .build();
  }

  private static Element sizeRepeatedScalarEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    if (wire == WireScalar.BYTES) {
      // One tagged field per element (not packed).
      return For.enhanced("var _x", getter)
          .body(Element.expr("size += Wire.lenDelimSize(@0, _x.size())", num))
          .build();
    }
    if (wire == WireScalar.STRING) {
      // One tagged field per element (not packed).
      return For.enhanced("var _x", getter)
          .body(
              Element.expr(
                  "size += @0.computeStringSize(@1, _x)", TypeRefs.CODED_OUTPUT_STREAM, num))
          .build();
    }
    // Packed scalar: one length-delimited group — tag + varint(bodySize) + bodySize.
    // Fixed-size types: bodySize = count * fixedSize (no per-element iteration needed).
    // Varint types: bodySize = sum of per-element varint sizes.
    String fixedSize =
        switch (wire) {
          case FIXED32, FLOAT -> "4";
          case FIXED64, DOUBLE -> "8";
          case BOOL -> "1";
          default -> null;
        };
    if (fixedSize != null) {
      return If.condition("!@0.isEmpty()", getter)
          .body(
              Element.expr(
                  "size += @0.computeTagSize(@1) + @0.computeInt32Size(1, @2.size() * @3) - 1"
                      + " + @2.size() * @3",
                  TypeRefs.CODED_OUTPUT_STREAM,
                  num,
                  getter,
                  fixedSize))
          .build();
    }
    // Varint types: iterate to sum per-element varint sizes.
    Element bodySizeExpr =
        switch (wire) {
          case INT32, UINT32 ->
              Element.expr(
                  "_bodySize += @0.computeInt32Size(1, _x) - 1", TypeRefs.CODED_OUTPUT_STREAM);
          case SINT_32 ->
              Element.expr(
                  "_bodySize += @0.computeSInt32Size(1, _x) - 1", TypeRefs.CODED_OUTPUT_STREAM);
          case INT64, UINT64 ->
              Element.expr(
                  "_bodySize += @0.computeInt64Size(1, _x) - 1", TypeRefs.CODED_OUTPUT_STREAM);
          case SINT_64 ->
              Element.expr(
                  "_bodySize += @0.computeSInt64Size(1, _x) - 1", TypeRefs.CODED_OUTPUT_STREAM);
          default -> throw new IllegalStateException("not a packed varint scalar: " + wire);
        };
    return If.condition("!@0.isEmpty()", getter)
        .body(
            Element.expr("int _bodySize = 0"),
            For.enhanced("var _x", getter).body(bodySizeExpr).build(),
            Element.expr("size += Wire.lenDelimSize(@0, _bodySize)", num))
        .build();
  }

  /** Short name for the codec of a message field's element type. */
  private static String codecShortName(Field f) {
    TypeRef element = f.type();
    if (f.label() == FieldLabel.REPEATED && element instanceof TypeRef.Named n) {
      element = n.generics().isEmpty() ? element : n.generics().get(0);
    }
    if (element instanceof TypeRef.Named n) {
      return n.shortName();
    }
    return element.toString();
  }

  /**
   * Size computation for a packed scalar body: fixed-size types use count * N, varint types sum
   * per-element sizes.
   */
  private static Element packedBodySizeExpr(WireScalar wire, String getter) {
    return switch (wire) {
      case FIXED32, FLOAT -> Element.expr("int bodySize = @0.size() * 4", getter);
      case FIXED64, DOUBLE -> Element.expr("int bodySize = @0.size() * 8", getter);
      case BOOL -> Element.expr("int bodySize = @0.size()", getter);
      case INT32 ->
          Element.expr(
              "int bodySize = 0; for (var _x : @0) bodySize += @1.computeInt32Size(1, _x) - 1;",
              getter,
              TypeRefs.CODED_OUTPUT_STREAM);
      case UINT32 ->
          Element.expr(
              "int bodySize = 0; for (var _x : @0) bodySize += @1.computeUInt32SizeNoTag(_x);",
              getter,
              TypeRefs.CODED_OUTPUT_STREAM);
      case SINT_32 ->
          Element.expr(
              "int bodySize = 0; for (var _x : @0) bodySize += @1.computeSInt32Size(1, _x) - 1;",
              getter,
              TypeRefs.CODED_OUTPUT_STREAM);
      case INT64 ->
          Element.expr(
              "int bodySize = 0; for (var _x : @0) bodySize += @1.computeInt64Size(1, _x) - 1;",
              getter,
              TypeRefs.CODED_OUTPUT_STREAM);
      case UINT64 ->
          Element.expr(
              "int bodySize = 0; for (var _x : @0) bodySize += @1.computeUInt64SizeNoTag(_x);",
              getter,
              TypeRefs.CODED_OUTPUT_STREAM);
      case SINT_64 ->
          Element.expr(
              "int bodySize = 0; for (var _x : @0) bodySize += @1.computeUInt64SizeNoTag((_x << 1) ^ (_x >> 63));",
              getter,
              TypeRefs.CODED_OUTPUT_STREAM);
      default -> throw new IllegalStateException(wire + " is not a packed numeric type");
    };
  }

  /** The no-tag write primitive for a packed scalar's element (mirrors{@code CodecGenerator}). */
  private static String packedNoTag(WireScalar wire) {
    return switch (wire) {
      case INT32 -> "writeInt32NoTag";
      case UINT32 -> "writeUInt32NoTag";
      case SINT_32 -> "writeSInt32NoTag";
      case FIXED32 -> "writeFixed32NoTag";
      case INT64 -> "writeInt64NoTag";
      case UINT64 -> "writeUInt64NoTag";
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

  private static Element emitRepeatedScalarEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    // Length-delimited scalars are never packed: one tagged field per element, the empty string /
    // bytes included (unlike a singular field, a repeated element has no default to elide).
    if (wire == WireScalar.STRING || wire == WireScalar.BYTES) {
      String element = wire == WireScalar.BYTES ? "_x.toByteArray()" : "_x";
      return For.enhanced("var _x", getter)
          .body(Element.expr("out.@0(@1, " + element + ")", encodeCall(wire), num))
          .build();
    }
    // Packed scalar: emit one length-delimited group when the list is non-empty.
    return If.condition("!@0.isEmpty()", getter)
        .body(
            packedBodySizeExpr(wire, getter),
            Element.expr("byte[] bodyBuf = new byte[bodySize]"),
            Element.expr("var _o = @0.newInstance(bodyBuf)", TypeRefs.CODED_OUTPUT_STREAM),
            For.enhanced("var _x", getter)
                .body(Element.expr("_o.@0(_x)", packedNoTag(wire)))
                .build(),
            Element.expr("out.writeByteArray(@0, bodyBuf)", num))
        .build();
  }

  private static Case emitRepeatedScalarDecodeCase(Field f) {
    int num = f.number();
    String local = f.javaName();
    WireScalar wire = wireScalar(f);
    // Length-delimited scalars: each tag occurrence carries exactly one element.
    if (wire == WireScalar.STRING) {
      return Case.of(String.valueOf(num), Element.expr("@0.add(in.readString())", local));
    }
    if (wire == WireScalar.BYTES) {
      return Case.of(
          String.valueOf(num),
          Element.expr("@0.add(@1.of(in.readBytes().toByteArray()))", local, TypeRefs.BYTES));
    }
    return Case.of(
        String.valueOf(num),
        LocalVar.builder(TypeRef.Primitive.INT, "_lim")
            .assigned("in.pushLimit(in.readRawVarint32())"),
        Element.expr("while (in.getBytesUntilLimit() > 0) @0.add(@1)", local, decodeCall(wire)),
        Element.expr("in.popLimit(_lim)"));
  }

  private static Element emitRepeatedLocalInit(Field f) {
    TypeRef element =
        wireScalar(f) == WireScalar.BYTES ? TypeRefs.BYTES : TypeRef.boxedFor(f.typeUrl());
    return LocalVar.builder(TypeRefs.LIST.withGenerics(element), f.javaName())
        .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST));
  }

  /**
   * M4-C2d-7: emit a 2-field entry codec for a map entry record. The entry has exactly two fields:
   * {@code key} (field 1, a scalar) and {@code value} (field 2). The value is a scalar, a message
   * (length-delimited, through its record's codec) or an enum (a varint through {@code forNumber}).
   * A missing key or value decodes to the type's default: the scalar default, a default message
   * (the codec's decode of no bytes), or the enum's zero constant.
   *
   * @param entryRef the entry record's fully-typed name (e.g. {@code Sample.DataEntry})
   * @return the nested {@code <Entry>Codec} class
   */
  public static ClassSpec emitEntryCodec(TypeRef.Named entryRef, Field f) {
    // M4-C2d-12: resolve the key wire scalar from the Field's type URL.
    WireScalar keyWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapKey()));
    FieldKind valueKind = Objects.requireNonNull(f.mapValueKind());
    TypeRef valueType = f.mapValueType();
    String codecName = entryRef.shortName() + "Codec";

    // key: field 1 — always field 1 in a map entry; written unconditionally.
    // A bytes-valued key unwraps to the raw array (Bytes.toByteArray()); every other writes
    // through.
    String keyArg = keyWire == WireScalar.BYTES ? "v.key().toByteArray()" : "v.key()";

    return ClassSpec.named(entryRef.sub(codecName))
        .javadoc(
            GeneratedDocs.type(
                null, "Auto-generated codec for the " + entryRef.shortName() + " map entry."))
        .public_()
        .static_()
        .final_()
        .constructor(Constructor.builder().private_().build())
        .method(
            Method.named("encode")
                .public_()
                .static_()
                .returnType(TypeRef.BYTES)
                .param(entryRef, "v")
                .thrown(TypeRef.IO_EXCEPTION)
                .body(
                    Element.expr("int size = getSerializedSize(v)"),
                    Element.expr("byte[] buf = new byte[size]"),
                    Element.expr("var out = @0.newInstance(buf)", TypeRefs.CODED_OUTPUT_STREAM),
                    Element.expr("writeBody(out, v)"),
                    Element.ret("buf"))
                .build())
        .method(
            Method.named("encodeTo")
                .public_()
                .static_()
                .param(TypeRefs.CODED_OUTPUT_STREAM, "out")
                .param(TypeRef.Primitive.INT, "fieldNum")
                .param(entryRef, "v")
                .thrown(TypeRef.IO_EXCEPTION)
                .annotation(Annotation.suppressWarnings("deprecation"))
                .body(
                    Element.expr("int size = getSerializedSize(v)"),
                    Element.expr("out.writeRawVarint32((fieldNum << 3) | 2)"),
                    Element.expr("out.writeRawVarint32(size)"),
                    Element.expr("writeBody(out, v)"))
                .build())
        .method(
            Method.named("writeBody")
                .private_()
                .static_()
                .param(TypeRefs.CODED_OUTPUT_STREAM, "out")
                .param(entryRef, "v")
                .thrown(TypeRef.IO_EXCEPTION)
                .body(
                    Element.expr("out.@0(1, @1)", encodeCall(keyWire), keyArg),
                    entryValueEncode(f, valueKind))
                .build())
        .method(
            Method.named("getSerializedSize")
                .public_()
                .static_()
                .returnType(TypeRef.Primitive.INT)
                .param(entryRef, "v")
                .thrown(TypeRef.IO_EXCEPTION)
                .body(mapEntrySizeBody(f, keyWire, keyArg, valueKind))
                .build())
        // A map entry is not a nesting level (protobuf-java counts only the value message), so its
        // decode is the plain body, not a DecodeScope delegate.
        .method(
            Method.named("decode")
                .public_()
                .static_()
                .returnType(entryRef)
                .param(TypeRef.BYTES, "inBytes")
                .thrown(TypeRef.IO_EXCEPTION)
                .body(
                    Element.expr("var dec = @0.start(inBytes)", TypeRefs.DECODER),
                    Element.expr("var in = dec.in()"),
                    Element.expr("var unknown = @0.builder()", TypeRefs.UNKNOWN_FIELDS),
                    emitFieldLocalInit("key", keyWire, TypeRefs.CODEC_DEFAULTS),
                    entryValueLocalInit(f, valueKind, valueType),
                    While.condition("true")
                        .body(
                            LocalVar.builder(TypeRef.Primitive.INT, "tag")
                                .assigned("@0.readTag(in)", TypeRefs.WIRE),
                            If.condition("tag == 0").body(Element.of("break;")).build(),
                            Switch.on("@0.tagFieldNumber(tag)", TypeRefs.WIRE)
                                .caseArms(
                                    emitFieldDecodeCase(1, "key", keyWire),
                                    entryValueDecodeCase(f, valueKind, valueType))
                                .defaultBody(Element.expr("unknown.add(in, tag)"))
                                .build(),
                            Element.of(""))
                        .build(),
                    entryResult(f, valueKind, entryRef))
                .build())
        .build();
  }

  /** Direct size computation for a map entry: key at field 1, value at field 2. */
  private static Element mapEntrySizeBody(
      Field f, WireScalar keyWire, String keyArg, FieldKind valueKind) {
    String keySize =
        keyWire == WireScalar.BYTES
            ? "@0.computeByteArraySize(1, " + keyArg + ")"
            : "@0." + sizeCall(keyWire) + "(1, " + keyArg + ")";
    return switch (valueKind) {
      case MESSAGE ->
          Element.expr(
              "return "
                  + keySize
                  + " + @0.computeTagSize(2) + @0.computeInt32Size(1, "
                  + "@1.getSerializedSize(v.value())) - 1 + @1.getSerializedSize(v.value())",
              TypeRefs.CODED_OUTPUT_STREAM,
              entryValueCodec(f));
      case ENUM ->
          Element.expr(
              "return " + keySize + " + @0.computeInt32Size(2, v.value().getNumber())",
              TypeRefs.CODED_OUTPUT_STREAM);
      default -> {
        WireScalar valueWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue()));
        String valueSize =
            valueWire == WireScalar.BYTES
                ? "@0.computeByteArraySize(2, v.value().toByteArray())"
                : "@0." + sizeCall(valueWire) + "(2, v.value())";
        yield Element.expr("return " + keySize + " + " + valueSize, TypeRefs.CODED_OUTPUT_STREAM);
      }
    };
  }

  /** The entry encoder's write of the value (field 2), which is written unconditionally. */
  private static Element entryValueEncode(Field f, FieldKind valueKind) {
    return switch (valueKind) {
      case MESSAGE -> Element.expr("@0.encodeTo(out, 2, v.value())", entryValueCodec(f));
      case ENUM -> Element.expr("out.writeEnum(2, v.value().getNumber())");
      default -> {
        WireScalar wire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue()));
        // A bytes value unwraps to the raw array, as a bytes key does.
        String arg = wire == WireScalar.BYTES ? "v.value().toByteArray()" : "v.value()";
        yield Element.expr("out.@0(2, @1)", encodeCall(wire), arg);
      }
    };
  }

  /** The entry decoder's local for the value, holding the type's default until field 2 is read. */
  private static Element entryValueLocalInit(Field f, FieldKind valueKind, TypeRef valueType) {
    return switch (valueKind) {
      // A missing message value is defaulted when the entry is built, so no default message is
      // decoded for an entry that carries one.
      case MESSAGE -> Element.expr("@0 value = null", valueType);
      case ENUM -> Element.expr("@0 value = @0.forNumber(0)", valueType);
      default ->
          emitFieldLocalInit(
              "value",
              wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue())),
              TypeRefs.CODEC_DEFAULTS);
    };
  }

  /** The entry decoder's switch arm for the value (field 2). */
  private static Case entryValueDecodeCase(Field f, FieldKind valueKind, TypeRef valueType) {
    return switch (valueKind) {
      case MESSAGE ->
          Case.of("2", Element.expr("value = @0.decode(in.readByteArray())", entryValueCodec(f)));
      case ENUM -> Case.of("2", Element.expr("value = @0.forNumber(in.readEnum())", valueType));
      default ->
          emitFieldDecodeCase(
              2, "value", wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue())));
    };
  }

  /**
   * The entry decoder's return: a message value that was never read becomes the default message.
   */
  private static Element entryResult(Field f, FieldKind valueKind, TypeRef.Named entryRef) {
    if (valueKind == FieldKind.MESSAGE) {
      return Element.ret(
          "new @0(key, value != null ? value : @1.decode(new byte[0]))",
          entryRef.shortName(),
          entryValueCodec(f));
    }
    return Element.ret("new @0(key, value)", entryRef.shortName());
  }

  /**
   * The {@code <Value>Codec} nested inside a message value's record. A well-known-type value is
   * unsupported: it has no record of its own to carry a codec.
   */
  private static TypeRef entryValueCodec(Field f) {
    String token = Objects.requireNonNull(f.mapValue());
    if (WellKnownTypes.resolve(token) != null) {
      throw new UnsupportedOperationException(
          "map value of well-known type " + token + " is not supported");
    }
    TypeRef.Named value = (TypeRef.Named) f.mapValueType();
    return value.sub(value.shortName() + "Codec");
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
  private static Element emitFieldLocalInit(String name, WireScalar ws, TypeRef cd) {
    if (ws == WireScalar.STRING) {
      return Element.expr("String @0 = \"\"", name);
    } else if (ws == WireScalar.BYTES) {
      return Element.expr("@0 @1 = @2.bytes()", TypeRefs.BYTES, name, cd);
    } else {
      return Element.expr("@0 @1 = @2.@3()", localTypeForWire(ws), name, cd, defaultForWire(ws));
    }
  }

  /** M4-C2d-12: emit a decode case line for a map entry's scalar field. */
  private static Case emitFieldDecodeCase(int fieldNum, String name, WireScalar ws) {
    return Case.of(
        String.valueOf(fieldNum),
        switch (ws) {
          case STRING -> Element.expr("@0 = in.readString()", name);
          case BYTES ->
              Element.expr("@0 = @1.of(in.readBytes().toByteArray())", name, TypeRefs.BYTES);
          default -> Element.expr("@0 = @1", name, decodeCall(ws));
        });
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
  private static Case emitMapDecodeCase(Field f) {
    String entryName = mapEntryName(f);
    String local = f.javaName();
    int num = f.number();
    return Case.of(
        String.valueOf(num),
        Element.expr("var _entry = @0.@0Codec.decode(in.readByteArray())", entryName),
        Element.expr("@0.put(_entry.key(), _entry.value())", local));
  }

  // --- Oneof encode / decode ---

  /** One field's (or one oneof member's) encode statement, keyed by its field number. */
  private record EncodeSlot(int number, Element element) {}

  /** The encode statement of a regular (non-oneof) field. */
  private static Element regularFieldEncode(Field f) {
    Entry wktEntry = WktSourceMapping.resolve(f);
    if (wktEntry != null) {
      return WktSourceMapping.emitEncode(f, wktEntry, "out", "v");
    }
    return switch (f.kind()) {
      case FieldKind.MAP -> emitMapEncode(f);
      case FieldKind.MESSAGE ->
          f.label() == FieldLabel.REPEATED ? emitRepeatedMessageEncode(f) : emitMessageEncode(f);
      case FieldKind.ENUM ->
          f.label() == FieldLabel.REPEATED ? emitRepeatedEnumEncode(f) : emitEnumEncode(f);
      case FieldKind.SCALAR ->
          f.label() == FieldLabel.REPEATED ? emitRepeatedScalarEncode(f) : emitScalarEncode(f);
    };
  }

  /**
   * One encode slot per oneof member: {@code if (v.<oneof>() instanceof <Wrapper> x_) { write }}.
   * Guarded writes (rather than one {@code switch} over the component) let each member sit at its
   * own field number among the regular fields, so the wire order matches protobuf-java's. Message
   * members dispatch through their nested codec; scalar / enum / well-known-type members through
   * the wire primitive (or Wkt bridge) that matches their field type.
   */
  private static List<EncodeSlot> oneofEncodeSlots(Oneof o) {
    String iface = o.ifaceName();
    String getter = "v." + o.componentName() + "()";
    List<EncodeSlot> slots = new ArrayList<>();
    for (Field f : o.members()) {
      String bnd = String.valueOf(f.name().charAt(0)) + "_";
      String recName = iface + "." + Oneof.valueRecordName(f);
      Entry wktMember = WktSourceMapping.resolve(f);
      Element write;
      if (wktMember != null) {
        // A well-known-type member: its native value through the Wkt / DynamicWkt bridge, written
        // whenever set (a oneof member has presence -- no default elision).
        String wm = WktSourceMapping.writeMethod(f.typeUrl());
        if (wktMember.nodeType() != null) {
          write =
              Element.expr(
                  "out.writeByteArray(@0, @1.encode(\"" + f.typeUrl() + "\", @2.value()))",
                  f.number(),
                  TypeRefs.DYNAMIC_WKT,
                  bnd);
        } else if (wm != null) {
          write = Element.expr("@0.@1(out, @2, @3.value())", TypeRefs.WKT, wm, f.number(), bnd);
        } else {
          write =
              Element.expr(
                  "@0.writeWrap(out, @1, @0.Wrap.@2, @3.value())",
                  TypeRefs.WKT,
                  f.number(),
                  WktSourceMapping.wrapKind(f.typeUrl()),
                  bnd);
        }
      } else if (f.isMessage()) {
        write = Element.expr("@1.encodeTo(out, @0, @2.value())", f.number(), codecRef(f), bnd);
      } else if (f.kind() == FieldKind.ENUM) {
        write = Element.expr("out.writeEnum(@0, @1.value().getNumber())", f.number(), bnd);
      } else {
        WireScalar wire = wireScalar(f);
        write =
            Element.expr(
                "out.@0(@1, @2.value()" + (wire == WireScalar.BYTES ? ".toByteArray()" : "") + ")",
                encodeCall(wire),
                f.number(),
                bnd);
      }
      slots.add(
          new EncodeSlot(
              f.number(),
              If.condition("@0 instanceof @1 @2", getter, recName, bnd).body(write).build()));
    }
    return slots;
  }

  /**
   * Emit the decode case for each member of the oneof: a {@code case fieldNumber -> { ...}} that
   * assigns the oneof's shared local. A message member decodes via its nested codec; a scalar/enum
   * member decodes via the wire read primitive and wraps in the per-member record.
   */
  private static List<Case> emitOneofDecodeCase(Oneof o, TypeRef.Named recordRef) {
    String cmp = o.componentName();
    List<Case> arms = new ArrayList<>();
    for (Field f : o.members()) {
      arms.add(
          Case.of(
              String.valueOf(f.number()),
              Element.expr("@0 = @1", cmp, oneofDecodeRhs(o, f, recordRef))));
    }
    return arms;
  }

  /**
   * The right-hand side of a oneof member's decode assignment: the per-member wrapper record's
   * constructor call carrying the decoded value.
   */
  private static RawExpr oneofDecodeRhs(Oneof o, Field f, TypeRef.Named recordRef) {
    String iface = o.ifaceName();
    // The per-member wrapper record, nested <Record>.<Iface>.<Name>Value, as a typed reference:
    // the renderer drops the in-scope enclosing segment so it renders in the baseline's
    // <Iface>.<Name>Value form.
    TypeRef wrapper = recordRef.sub(iface).sub(Oneof.valueRecordName(f));
    Entry wktMember = WktSourceMapping.resolve(f);
    if (wktMember != null) {
      return RawExpr.of(
          "new @0(@1)", wrapper, WktSourceMapping.decodeValue(f, wktMember, "in.readByteArray()"));
    }
    if (f.isMessage()) {
      return RawExpr.of("new @0(@1.decode(in.readByteArray()))", wrapper, codecRef(f));
    }
    // Scalar or enum member: wrap the decoded value in the per-member record.
    if (f.kind() == FieldKind.ENUM) {
      // The field's typed model reference — the record enum (in-package), resolved by the
      // renderer; the natural protobuf-java reference is a different package.
      return RawExpr.of("new @0(@1.forNumber(in.readEnum()))", wrapper, f.type());
    }
    // Scalar member.
    WireScalar wire = wireScalar(f);
    String readCall = decodeCall(wire);
    return switch (wire) {
      case STRING -> RawExpr.of("new @0(in.readString())", wrapper);
      case BYTES ->
          RawExpr.of("new @0(@1.of(in.readBytes().toByteArray()))", wrapper, TypeRefs.BYTES);
      default -> RawExpr.of("new @0(@1)", wrapper, readCall);
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
