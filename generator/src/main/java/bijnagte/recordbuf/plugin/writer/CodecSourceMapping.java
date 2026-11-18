package bijnagte.recordbuf.plugin.writer;

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
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeRefs;
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
    return doToCodec(m);
  }

  private static ClassSpec doToCodec(Message m) {
    // Every type the codec body references (encoder/decoder/unknownFields/wire/codecDefaults,
    // plus the HashMap/ArrayList/Bytes constructors and CodecDefaults.<x>() default calls) is now
    // a TypeRef positional arg of a RawExpr, so the renderer's collect walk registers + resolves
    // it; no explicit collect/render is needed here.
    String recName = m.type().shortName();
    String codecName = recName + "Codec";
    return ClassSpec.named(m.type().sub(codecName))
        .public_()
        .static_()
        .final_()
        .javadoc(GeneratedDocs.type(null, "Wire codec for the " + recName + " record."))
        .constructor(Constructor.builder().private_().build())
        .method(encode(m))
        .method(decode(m))
        .build();
  }

  /** The {@code byte[] encode(Message v) throws IOException} method body. */
  private static Method encode(Message m) {
    var method =
        Method.named("encode")
            .public_()
            .static_()
            .returnType(TypeRef.BYTES)
            .param(m.type(), "v")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(
                Element.expr("var enc = @0.start()", TypeRefs.ENCODER),
                Element.expr("var out = enc.out()"));

    // Sort by field number for wire-order correctness (OrderTest proves this).
    var sortedFields = m.fields().stream().sorted(Comparator.comparingInt(Field::number)).toList();

    for (Field f : sortedFields) {
      Entry wktEntry = WktSourceMapping.resolve(f);
      if (wktEntry != null) {
        var wkt0 = Objects.requireNonNull(wktEntry);
        method.body(WktSourceMapping.emitEncode(f, wkt0, "out", "v"));
      } else {
        Element e =
            switch (f.kind()) {
              case FieldKind.MAP -> emitMapEncode(f);
              case FieldKind.MESSAGE ->
                  f.label() == FieldLabel.REPEATED
                      ? emitRepeatedMessageEncode(f)
                      : emitMessageEncode(f);
              case FieldKind.ENUM ->
                  f.label() == FieldLabel.REPEATED ? emitRepeatedEnumEncode(f) : emitEnumEncode(f);
              case FieldKind.SCALAR ->
                  f.label() == FieldLabel.REPEATED
                      ? emitRepeatedScalarEncode(f)
                      : emitScalarEncode(f);
            };
        method.body(e);
      }
    }

    // Oneof members: pattern-matching switch on the sealed-interface component.
    for (Oneof o : m.oneofs()) {
      method.body(emitOneofEncode(o));
    }

    // M4-C2b-2b-5: re-emit the unknown-fields carrier, if any, preserving wire-order round-trip.
    return method
        .body(
            If.condition("!v.unknownFields().isEmpty()")
                .body(Element.expr("@0.writeUnknown(out, v.unknownFields())", TypeRefs.WIRE))
                .build(),
            Element.ret("enc.finish()"))
        .build();
  }

  /** The {@code Message decode(byte[] inBytes) throws IOException} method body. */
  private static Method decode(Message m) {
    var method =
        Method.named("decode")
            .public_()
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
            TypeRef keyType = TypeRef.boxedFor(Objects.requireNonNull(f.mapKey()));
            TypeRef valueType = TypeRef.boxedFor(Objects.requireNonNull(f.mapValue()));
            TypeRef mapType = TypeRefs.MAP.withGenerics(keyType, valueType);

            method.body(
                Element.expr("@0 @1 = new @2<>()", mapType, f.javaName(), TypeRefs.HASH_MAP));
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

  /** M4-C2d-5: singular MESSAGE encode — skip null, else writeByteArray(tag, Codec.encode(v)). */
  private static Element emitMessageEncode(Field f) {
    return If.condition("v.@0() != null", f.javaName())
        .body(
            Element.expr(
                "out.writeByteArray(@0, @1.encode(v.@2()))", f.number(), codecRef(f), f.javaName()))
        .build();
  }

  /** M4-C2d-5: repeated MESSAGE encode — one writeByteArray per element. */
  private static Element emitRepeatedMessageEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    return For.enhanced("var _m", getter)
        .body(Element.expr("out.writeByteArray(@0, @1.encode(_m))", f.number(), codecRef(f)))
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
                "out.writeByteArray(@0, @1.@1Codec.encode(new @1(_e.getKey(), _e.getValue())))",
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

  private static Element emitRepeatedScalarEncode(Field f) {
    String getter = "v." + f.javaName() + "()";
    int num = f.number();
    WireScalar wire = wireScalar(f);
    // Packed scalar: emit one length-delimited group when the list is non-empty.
    return If.condition("!@0.isEmpty()", getter)
        .body(
            Element.expr("var body = @0.start()", TypeRefs.ENCODER),
            Element.expr("var _o = @0.out()", "body"),
            For.enhanced("var _x", getter)
                .body(Element.expr("_o.@0(_x)", packedNoTag(wire)))
                .build(),
            Element.expr("out.writeByteArray(@0, body.finish())", num))
        .build();
  }

  private static Case emitRepeatedScalarDecodeCase(Field f) {
    int num = f.number();
    String local = f.javaName();
    WireScalar wire = wireScalar(f);
    return Case.of(
        String.valueOf(num),
        LocalVar.builder(TypeRef.Primitive.INT, "_lim")
            .assigned("in.pushLimit(in.readRawVarint32())"),
        Element.expr("while (in.getBytesUntilLimit() > 0) @0.add(@1)", local, decodeCall(wire)),
        Element.expr("in.popLimit(_lim)"));
  }

  private static Element emitRepeatedLocalInit(Field f) {
    return LocalVar.builder(TypeRefs.LIST.withGenerics(TypeRef.boxedFor(f.typeUrl())), f.javaName())
        .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST));
  }

  /**
   * M4-C2d-7: emit a 2-field entry codec for a map entry record. The entry has exactly two fields:
   * {@code key} (field 1) and {@code value} (field 2). Both are scalar types.
   *
   * @param entryRef the entry record's fully-typed name (e.g. {@code Sample.DataEntry})
   * @return the nested {@code <Entry>Codec} class
   */
  public static ClassSpec emitEntryCodec(TypeRef.Named entryRef, Field f) {
    // M4-C2d-12: resolve key/value wire scalars from the Field's type URLs.
    WireScalar keyWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapKey()));
    WireScalar valWire = wireScalarForTypeUrl(Objects.requireNonNull(f.mapValue()));
    String codecName = entryRef.shortName() + "Codec";

    // key: field 1 — always field 1 in a map entry; written unconditionally.
    // A bytes-valued key unwraps to the raw array (Bytes.toByteArray()); every other writes
    // through.
    String keyArg = keyWire == WireScalar.BYTES ? "v.key().toByteArray()" : "v.key()";

    // value: field 2 — always field 2; written unconditionally, same unwrapping rule.
    String valArg = valWire == WireScalar.BYTES ? "v.value().toByteArray()" : "v.value()";

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
                    Element.expr("var enc = @0.start()", TypeRefs.ENCODER),
                    Element.expr("var out = enc.out()"),
                    Element.expr("out.@0(1, @1)", encodeCall(keyWire), keyArg),
                    Element.expr("out.@0(2, @1)", encodeCall(valWire), valArg),
                    Element.ret("enc.finish()"))
                .build())
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
                    emitFieldLocalInit("value", valWire, TypeRefs.CODEC_DEFAULTS),
                    While.condition("true")
                        .body(
                            LocalVar.builder(TypeRef.Primitive.INT, "tag")
                                .assigned("@0.readTag(in)", TypeRefs.WIRE),
                            If.condition("tag == 0").body(Element.of("break;")).build(),
                            Switch.on("@0.tagFieldNumber(tag)", TypeRefs.WIRE)
                                .caseArms(
                                    emitFieldDecodeCase(1, "key", keyWire),
                                    emitFieldDecodeCase(2, "value", valWire))
                                .defaultBody(Element.expr("unknown.add(in, tag)"))
                                .build(),
                            Element.of(""))
                        .build(),
                    Element.ret("new @0(key, value)", entryRef.shortName()))
                .build())
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
  private static Element emitFieldLocalInit(String name, WireScalar ws, TypeRef cd) {
    if (ws == WireScalar.STRING) {
      return Element.expr("String @0 = \"\"", name);
    } else {
      return Element.expr("@0 @1 = @2.@3()", localTypeForWire(ws), name, cd, defaultForWire(ws));
    }
  }

  /** M4-C2d-12: emit a decode case line for a map entry's scalar field. */
  private static Case emitFieldDecodeCase(int fieldNum, String name, WireScalar ws) {
    return Case.of(
        String.valueOf(fieldNum),
        ws == WireScalar.STRING
            ? Element.expr("@0 = in.readString()", name)
            : Element.expr("@0 = @1", name, decodeCall(ws)));
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

  /**
   * Emit the oneof group encode: a pattern-matching {@code switch} on the sealed-interface
   * component. {@code case null -> {}} skips; message members dispatch through their nested codec;
   * scalar/enum members dispatch through the wire primitive that matches their field type.
   */
  private static Element emitOneofEncode(Oneof o) {
    String iface = o.ifaceName();
    String cmp = o.componentName();
    String getter = "v." + cmp + "()";
    Switch.Builder sw = Switch.on(getter);
    sw.caseArm("null");
    for (Field f : o.members()) {
      String bnd = String.valueOf(f.name().charAt(0)) + "_";
      if (f.isMessage()) {
        String sealedSub = iface + "." + JavaNames.cap(f.name()) + "Value";
        sw.caseArm(
            sealedSub + " " + bnd,
            Element.expr(
                "out.writeByteArray(@0, @1.encode(@2.value()))", f.number(), codecRef(f), bnd));
      } else if (f.kind() == FieldKind.ENUM) {
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        sw.caseArm(
            recName + " " + bnd,
            Element.expr("out.writeEnum(@0, @1.value().getNumber())", f.number(), bnd));
      } else {
        String recName = iface + "." + JavaNames.cap(f.name()) + "Value";
        WireScalar wire = wireScalar(f);
        String call = encodeCall(wire);
        sw.caseArm(
            recName + " " + bnd, Element.expr("out.@0(@1, @2.value())", call, f.number(), bnd));
      }
    }
    return sw.build();
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
    TypeRef wrapper = recordRef.sub(iface).sub(JavaNames.cap(f.name()) + "Value");
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
