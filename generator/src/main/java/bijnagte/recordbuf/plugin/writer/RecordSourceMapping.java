package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.Component;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.Param;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Maps the generator's proto {@link Message} model into a {@link Type.Record} of the pure-Java,
 * proto-agnostic {@code source} AST ({@code bijnagte.recordbuf.codegen.source}), replacing the
 * string-concatenation record emission with a renderable, structural tree.
 *
 * <p>{@code toSource} maps the record <em>shell</em>: header (Javadoc, {@code @Deprecated}, {@code
 * implements} clause), components (one {@link Component} per proto {@link Field}), and nested ENUMS
 * as a nested {@link Type} (reusing {@link EnumSourceMapping} for full enum core + {@code
 * BY_NUMBER} cluster).
 *
 * <p>{@code toSourceWithCodec} extends the shell with a nested {@link Type.Class} codec that
 * dispatches through {@link CodecSourceMapping} (encode/decode for all field families: scalars,
 * repeated, map, optional, nested-message, singular/repeated-enum, oneof, and WKT via {@link
 * WktSourceMapping}). Messages with a {@code recordbuf.protobuf_java} option are handled by the
 * legacy {@link bijnagte.recordbuf.plugin.writer.InteropGenerator} JavaWriter fallback.
 */
public final class RecordSourceMapping {

  private RecordSourceMapping() {}

  /**
   * Map a proto {@link Message} model into a source-AST {@link Type.Record} for the record shell.
   */
  public static Type.Record toSource(Message m, TypeCollector ci) {
    Type.Record.Builder result =
        Type.Record.named(m.name())
            .public_()
            .javadoc(Javadoc.builder().text(m.comment()).build())
            .deprecated(m.deprecated())
            .implementsFqns(m.interfaces());

    // 1. The components -- one Component per proto field, in declaration order. Blank Javadoc +
    // no @Deprecated + blank initializer are the compact-ctor defaults, so the builder sets only
    // the
    // component's type + javaName; a record's components render in its header via components().
    for (Field f : m.fields()) {
      var comp = Component.type(scalarComponentType(f), f.javaName());
      if (isComponentNullable(f)) comp.annotation(Annotation.NULLABLE);
      result.component(comp.build());
    }
    // its
    // full core + its BY_NUMBER reverse-lookup cluster (with shared java.util.* imports).
    for (Enum e : m.nestedEnums()) {
      result.nested(EnumSourceMapping.toSource(e, ci, ci.declaringPackage()));
    }
    // M4-C2b-2b-12: oneof groups. Each contributes one @Nullable record component and
    // one nested sealed interface (the per-member value records live inside it).
    for (Oneof oneof : m.oneofs()) {
      OneofEmission oe = emitOneof(oneof);
      result.component(oe.component());
      result.nested(oe.iface());
    }

    return result.build();
  }

  /**
   * Map a message model to a source-AST {@link Type.Record} WITH the nested {@code Codec} included.
   * The codec (encode/decode static methods with real {@code List<Element>} bodies) is appended as
   * a nested {@link Type} to the record's nested list, so the rendered source contains both the
   * record shell and its codec.
   */
  public static Type.Record toSourceWithCodec(Message m, TypeCollector ci) {
    return toSourceWithCodec(m, ci, null);
  }

  /**
   * Map a message model to a source-AST {@link Type.Record} WITH the nested codec and a {@code
   * typeResolver} that maps each nested-message field to its codec {@code <Type>Codec} FQN (e.g.
   * {@code Single.SingleCodec}). A {@code null} resolver means no nested-message codec wiring
   * (scalar-only message).
   */
  public static Type.Record toSourceWithCodec(
      Message m, TypeCollector ci, @Nullable Function<Field, String> typeResolver) {
    // Rebuild the member list from scratch with the codec appended, mirroring toSource's logic.
    Type.Record.Builder result =
        Type.Record.named(m.name())
            .public_()
            .javadoc(Javadoc.builder().text(m.comment()).build())
            .deprecated(m.deprecated());
    for (Field f : m.fields()) {
      var comp = Component.type(scalarComponentType(f), f.javaName());
      if (isComponentNullable(f)) comp.annotation(Annotation.NULLABLE);
      result.component(comp.build());
    }
    for (Enum e : m.nestedEnums()) {
      result.nested(EnumSourceMapping.toSource(e, ci, ci.declaringPackage()));
    }
    // M4-C2d-9: nested messages — emit each as an inner record via recursion.
    for (Message nested : m.nestedMessages()) {
      var nestedRec =
          toSourceWithCodec(
              nested, ci, f -> f.kind() == FieldKind.MESSAGE ? f.typeUrl() + ".Codec" : null);
      // The nested type's FQN must be <Parent>.<Nested> — the TypeCollector handles the
      // package qualification, so just ensure the ci knows about it.
      result.nested(nestedRec);
    }
    // M4-C2b-2b-12: oneof groups.
    for (Oneof oneof : m.oneofs()) {
      OneofEmission oe = emitOneof(oneof);
      result.component(oe.component());
      result.nested(oe.iface());
    }
    // M4-C2d-7: nested map entry records + their codecs, before the main Codec.
    for (Field f : m.fields()) {
      if (f.kind() == FieldKind.MAP) {
        Type.Record entry = mapEntryRecord(f, ci);
        result.nested(entry);
      }
    }

    // M4-C2b-2b-5: always-on unknown-field carrier: the record's last component.
    // The import is registered by the collect() phase via component.type().
    String ufFqn = TypeRefs.UNKNOWN_FIELDS.fqn();
    result.component(Component.named(ufFqn, "unknownFields").build());

    // M4-C2b-2b-6: the fluent builder. Emitted after the carrier and after the codec (a forward
    // reference to the codec's inner-class is resolved by javac's inner-class resolution).
    BuilderEmission be = emitBuilder(m, ci, ufFqn);
    result.method(be.factory());
    result.nested(be.nested());
    // M4-C2b-2b-8: toBuilder + fromBytes (old path parity)
    String codecName = m.name() + "Codec";
    result.method(
        Method.named("toBuilder")
            .public_()
            .returnType(TypeRef.of("Builder"))
            .body(Element.expr("return new @0.Builder(this)", m.name()))
            .build());
    result.method(
        Method.named("fromBytes")
            .public_()
            .static_()
            .returnType(TypeRef.of(m.name()))
            .param(TypeRef.BYTES, "in")
            .thrown(TypeRef.IO_EXCEPTION)
            .body(Element.expr("return @0.decode(in)", codecName))
            .build());

    // M4-C2b-2b-5: the Layer A marshalling hook. Every record implements ProtobufMessage by
    // delegating to its own nested Codec.encode. Import is registered via implementsFqns by the
    // collect() phase.
    var toByteArray =
        Method.named("toByteArray")
            .public_()
            .returnType(TypeRef.BYTES)
            .thrown(TypeRef.IO_EXCEPTION)
            .body(Element.expr("return @0Codec.encode(this)", m.name()))
            .override(true)
            .build();
    result.method(toByteArray);

    // M4-C2b-2c: append the nested Codec, built from the same message model.
    Type.Class codec = CodecSourceMapping.toCodec(m, ci, typeResolver);
    result.nested(codec);

    // M4-C2e: Layer B interop — nested Interop class when recordbuf.protobuf_java is set.
    // useFqn is used for the protobuf-java ref type to avoid a short-name import that
    // would collide with the record's own name in the same compilation unit.
    if (m.protobufJava() != null && !m.protobufJava().isEmpty()) {
      result.nested(InteropSourceMapping.toInterop(m, ci, ""));
    }

    // Build the implements list: user-declared interfaces first, then ProtobufMessage
    // (matches the old JavaWriter ordering: option interfaces precede the runtime marker).
    List<String> implementsList = new ArrayList<>();
    implementsList.addAll(m.interfaces());
    implementsList.add(TypeRefs.PROTOBUF_MESSAGE.fqn());
    result.implementsFqns(implementsList);

    return result.build();
  }

  /**
   * The record component's Java type for the M4-C2b scalar subset. A non-scalar field throws here;
   * repeated scalars resolve to {@code List<T>} where T is the boxed Java element type; singular
   * scalars resolve to their Java primitive or boxed type. The {@link TypeCollector} collects
   * cross-package type references so the render pass can emit correct imports.
   */
  private static TypeRef scalarComponentType(Field f) {
    // M4-C2b-2b-13: map<K, V> field. Key + value are proto scalar type URLs;
    // each is rendered as its boxed Java type (primitives get boxed inside Map<>).
    // Must run BEFORE the REPEATED label check (maps use REPEATED but are not Lists).
    if (f.kind() == FieldKind.MAP) {
      var mapRef =
          TypeRef.named(
              "java.util",
              "Map",
              TypeRef.boxedFor(Objects.requireNonNull(f.mapKey())),
              TypeRef.boxedFor(Objects.requireNonNull(f.mapValue())));
      return mapRef;
    }
    // M4-C2d-2: repeated MESSAGE/ENUM → List<ElementType>, element resolved per kind.
    if (f.label() == FieldLabel.REPEATED) {
      var listRef = TypeRef.named("java.util", "List", elementComponentType(f));
      return listRef;
    }
    // M4-C2d-12: WKT fields → native Java type from WellKnownTypes (e.g. Timestamp→Instant,
    // Int32Value→Integer). Singular WKT fields are @Nullable (presence-tracked; null = unset).
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      TypeRef wktRef = TypeRef.of(Objects.requireNonNull(wkt.javaType()));
      return wktRef;
    }
    // M4-C2d-2: singular MESSAGE/ENUM → the resolved short type name.
    // Singular message fields are presence-tracked, so they're @Nullable in the record;
    // the builder copy constructor and codec must null-guard accordingly.
    if (f.kind() == FieldKind.MESSAGE || f.kind() == FieldKind.ENUM) {
      TypeRef sibRef = TypeRef.of(javaSimpleName(f.typeUrl()));
      return sibRef;
    }
    // M4-C2d-1: optional scalar → @Nullable boxed wrapper.
    if (f.isNullable()) {
      return TypeRef.boxedFor(f.typeUrl());
    }
    return switch (f.typeUrl()) {
      case "int32", "uint32", "fixed32", "sfixed32", "sint32" -> TypeRef.Primitive.INT;
      case "int64", "uint64", "fixed64", "sfixed64", "sint64" -> TypeRef.Primitive.LONG;
      case "float" -> TypeRef.Primitive.FLOAT;
      case "double" -> TypeRef.Primitive.DOUBLE;
      case "bool" -> TypeRef.Primitive.BOOLEAN;
      case "string" -> TypeRef.STRING;
      case "bytes" -> TypeRefs.BYTES;
      default ->
          throw new UnsupportedOperationException(
              "scalar " + f.typeUrl() + " component type is not in the M4-C2b-1 subset");
    };
  }

  /**
   * Whether a record component for a proto field should carry the {@code @Nullable} annotation:
   * singular {@code MESSAGE}, {@code ENUM}, {@code WKT}, and {@code optional} scalars (the field's
   * own {@link Field#isNullable()}) are presence-tracked, so their Java component is
   * {@code @Nullable} in the record.
   */
  private static boolean isComponentNullable(Field f) {
    // REPEATED fields render as List<> / Map<> which are non-null; only singular fields can be
    // null.
    if (f.label() == FieldLabel.REPEATED) return false;
    if (isComponentNullableWkt(f)) return true;
    return switch (f.kind()) {
      case MESSAGE, ENUM -> true;
      case MAP -> false;
      default -> f.isNullable();
    };
  }

  private static boolean isComponentNullableWkt(Field f) {
    return WellKnownTypes.resolve(f.typeUrl()) != null;
  }

  /** The inner element type of a {@code repeated X} field, resolved per {@link FieldKind}. */
  private static TypeRef elementComponentType(Field f) {
    // M4-C2d-12: WKT elements use the native Java type.
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      TypeRef wktElemRef = TypeRef.of(Objects.requireNonNull(wkt.javaType()));
      return wktElemRef;
    }
    if (f.kind() == FieldKind.MESSAGE || f.kind() == FieldKind.ENUM) {
      TypeRef sibElemRef = TypeRef.of(javaSimpleName(f.typeUrl()));
      return sibElemRef;
    }
    return TypeRef.boxedFor(f.typeUrl());
  }

  /**
   * The Java simple name for a proto type URL's last path component (e.g. {@code a.b.c.Item}
   * {@literal ->} {@code Item}).
   */
  private static String javaSimpleName(String typeUrl) {
    int lastDot = typeUrl.lastIndexOf('.');
    return lastDot < 0 ? typeUrl : typeUrl.substring(lastDot + 1);
  }

  /**
   * M4-C2b-2b-12 (full): emit a oneof as its {@code @Nullable} record component, a nested {@code
   * public sealed interface} with {@code extends Unwrapable<Object>}, the {@code permits} clause
   * (one per-member record per non-message/enum member, plus identity subtypes for message
   * members), the {@code default Object value()} method, and the nested per-member records.
   *
   * <p>{@code permitsFqns} are <em>pre-resolved relative references</em> — the renderer emits them
   * verbatim (no type-collection call), because nested type qualifiers like {@code
   * Choice.NameValue} are not import-eligible. Message member permits are resolved via {@code
   * TypeRef.of(typeUrl).collect(ci) + .render(ctx)} to get their short name.
   */
  private static OneofEmission emitOneof(Oneof oneof) {
    String ifaceName = oneof.ifaceName();
    String componentName = oneof.componentName();

    // 1. The @Nullable record component in the enclosing record.
    //    Import is registered by the collect() phase via Annotation.NULLABLE.type().
    var component =
        Component.named(ifaceName, componentName).annotation(Annotation.NULLABLE).build();

    // 2. Build the sealed interface: extends clause + permits + per-member records + default
    // value().
    String unwrapFqn = TypeRefs.UNWRAPABLE.fqn();
    // The extendsFqn carries the full FQN; the collect() phase registers it via
    // TypeRef.collect(ci).
    Type.Interface.Builder iface =
        Type.Interface.named(ifaceName).public_().sealed_().extendsFqn(unwrapFqn + "<Object>");

    // Build permits and nested per-member records simultaneously. For each oneof member
    // (scalar, enum, or message), emit a nested public record <Cap>Value(<Type> value)
    // implementing the interface, and add "<Iface>.<Cap>Value" to the permits.
    for (Field member : oneof.members()) {
      // Every oneof member (scalar, enum, or message) gets a dedicated nested record
      // <Cap>Value(<valType> value) implementing the interface. Message members cannot be
      // identity subtypes in the source-AST path because each message is its own file.
      String valName = JavaNames.cap(member.name()) + "Value";
      iface.permitsFqn(ifaceName + "." + valName);
      // The value component type: for enum → the enum's short name (Same-package, no
      // import); for scalar → the boxed Java type; for message → the message's short type
      // reference (resolved via the type resolver).
      TypeRef valType;
      if (member.isMessage()) {
        valType = TypeRef.of(javaSimpleName(member.typeUrl()));
      } else if (member.kind() == FieldKind.ENUM) {
        valType = TypeRef.of(javaSimpleName(member.typeUrl()));
      } else {
        valType = TypeRef.boxedFor(member.typeUrl());
      }
      Type.Record wr =
          Type.Record.named(valName)
              .public_()
              .implementsFqn(ifaceName)
              .component(Component.type(valType, "value").build())
              .build();
      iface.nested(wr);
    }

    // The default value() method, which overrides Unwrapable.value(). It is a `default` (not
    // abstract) interface method so the nested per-member records can implement the interface
    // without
    // a separate impl. @Override is correct: the method overrides the Unwrapable.value() supertype.
    var defaultMethod =
        Method.named("value")
            .default_()
            .returnType(TypeRef.OBJECT)
            .body(Element.of("return this;"))
            .override(true)
            .build();
    iface.method(defaultMethod);

    return new OneofEmission(component, iface.build());
  }

  /**
   * The product of {@link #emitOneof}: the @Nullable component in the enclosing record and the
   * nested sealed interface (with its per-member value records inside) that becomes part of the
   * record's {@code nested} list. The caller adds both to the enclosing record; the helper is pure
   * and returns both rather than mutating the caller's record builder.
   */
  private record OneofEmission(Component component, Type.Interface iface) {}

  /**
   * The boxed Java type for a proto scalar type URL (for use inside {@code Map<>, List<>}, or as a
   * {@code @Nullable} wrapper). Primitives are boxed; {@code string}→{@code String}; {@code
   * bytes}→runtime {@code Bytes}.
   */
  // M4-C2b-2b-6: builder emission. A minimal value-only builder for scalar messages.
  // Returns Members: a static builder() factory + a nested Builder class.
  // The Builder is a public static final inner class with:
  // - one private field per component (with default initializer)
  // - one fluent setter per component
  // - private unknownFields field with default empty()
  // - public build() terminal
  private static BuilderEmission emitBuilder(Message m, TypeCollector ci, String ufFqn) {
    String recName = m.name();
    // The nested static Builder class — header first, members accumulated below.
    Type.Class.Builder builderClz =
        Type.Class.named("Builder")
            .javadoc(
                Javadoc.builder()
                    .text("Fluent, value-only builder for the ", m.name(), " record.")
                    .build())
            .public_()
            .static_()
            .final_();

    // Storage fields with codec defaults for each component field.
    for (Field f : m.fields()) {
      var comp =
          Component.type(scalarComponentType(f), f.javaName())
              .initializer(defaultFor(f))
              .private_();
      if (isComponentNullable(f)) comp.annotation(Annotation.NULLABLE);
      builderClz.field(comp.build());
    }
    // M4-C2b-2b-12: oneof component storage fields. Each defaults to null.
    for (var oneof : m.oneofs()) {
      builderClz.field(
          Component.named(oneof.ifaceName(), oneof.componentName())
              .annotation(Annotation.NULLABLE)
              .initializer(RawExpr.of("null"))
              .private_()
              .build());
    }
    // Carrier storage field with default empty().
    TypeRef ufRef = TypeRef.of(ufFqn);
    String ufShort = ufRef.render(ci.resolutionContext());
    builderClz.field(
        Component.named(ufShort, "unknownFields")
            .initializer(RawExpr.of("@0.empty()", ufRef))
            .private_()
            .build());

    // The no-arg constructor (Java does not generate an implicit one when a parametered ctor
    // exists).
    builderClz.constructor(Constructor.builder().private_().body().build());

    // The toBuilder copy constructor.
    List<Element> copyBody = new ArrayList<>();
    for (Field f : m.fields()) {
      String fn = f.javaName();
      if (f.label() == FieldLabel.REPEATED) {
        copyBody.add(Element.expr("this.@0 = new @2<>(x.@1())", fn, fn, TypeRefs.ARRAY_LIST));
      } else if (f.kind() == FieldKind.MAP) {
        copyBody.add(Element.expr("this.@0 = new @2<>(x.@1())", fn, fn, TypeRefs.HASH_MAP));
      } else {
        copyBody.add(Element.expr("this.@0 = x.@1()", fn, fn));
      }
    }
    // M4-C2b-2b-12: oneof component copy.
    for (var oneof : m.oneofs()) {
      copyBody.add(Element.expr("this.@0 = x.@1()", oneof.componentName(), oneof.componentName()));
    }
    copyBody.add(Element.of("this.unknownFields = x.unknownFields();"));
    builderClz.constructor(
        Constructor.builder().private_().param(TypeRef.of(recName), "x").body(copyBody).build());

    // Fluent setter for each field.
    for (Field f : m.fields()) {
      String fieldName = f.javaName();
      builderClz.method(
          Method.named(fieldName)
              .public_()
              .returnType(TypeRef.of("Builder"))
              .param(Param.of(scalarComponentType(f), fieldName))
              .body(Element.expr("this.@0 = @1", fieldName, fieldName), Element.of("return this;"))
              .build());
    }
    // M4-C2b-2b-12: oneof fluent setter per component.
    for (var oneof : m.oneofs()) {
      String compName = oneof.componentName();
      builderClz.method(
          Method.named(compName)
              .public_()
              .returnType(TypeRef.of("Builder"))
              .param(Param.of(TypeRef.of(oneof.ifaceName()), compName, Annotation.NULLABLE))
              .body(Element.expr("this.@0 = @1", compName, compName), Element.of("return this;"))
              .build());
    }
    // Carrier setter.
    builderClz.method(
        Method.named("unknownFields")
            .public_()
            .returnType(TypeRef.of("Builder"))
            .param(TypeRef.of(ufShort), "unknownFields")
            .body(Element.of("this.unknownFields = unknownFields;"), Element.of("return this;"))
            .build());

    // build(): constructs the record.
    List<String> ctorArgs = new ArrayList<>();
    for (Field f : m.fields()) {
      ctorArgs.add(f.javaName());
    }
    // M4-C2b-2b-12: oneof build args.
    for (var oneof : m.oneofs()) {
      ctorArgs.add(oneof.componentName());
    }
    ctorArgs.add("unknownFields");
    builderClz.method(
        Method.named("build")
            .javadoc(Javadoc.builder().text("Builds a ", m.name(), " record.").build())
            .public_()
            .returnType(TypeRef.of(recName))
            .body(Element.expr("return new @0(@1)", recName, String.join(", ", ctorArgs)))
            .build());

    // The nested Builder class itself — with javadoc matching the old JavaWriter path.
    Type.Class nestedBuilder = builderClz.build();

    // The static factory on the record, with javadoc.
    var builderFactory =
        Method.named("builder")
            .javadoc(
                Javadoc.builder()
                    .text(
                        "Static factory for a fresh ",
                        m.name(),
                        " {@link Builder}. Build it fluently, then call {@link Builder#build()}.")
                    .build())
            .public_()
            .static_()
            .returnType(TypeRef.of("Builder"))
            .body(Element.expr("return new @0.Builder()", recName))
            .build();

    return new BuilderEmission(builderFactory, nestedBuilder);
  }

  /**
   * The product of {@link #emitBuilder}: the static {@code builder()} factory that becomes a method
   * on the enclosing record plus the nested {@code Builder} class that gets nested on the record.
   * The caller adds both; the helper is pure and returns them rather than mutating the caller's
   * record builder.
   */
  private record BuilderEmission(Method factory, Type.Class nested) {}

  /**
   * The default initializer for a builder's storage field: "" for String, 0 for numeric, null for
   * others.
   */
  private static RawExpr defaultFor(Field f) {
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      if (f.label() == FieldLabel.REPEATED) return RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST);
      var z = wkt.zeroDefault();
      if (z != null) return RawExpr.of("@0", z);
      return RawExpr.of("null");
    }
    if (f.isNullable()) {
      // M4-C2d-1: optional scalar → null default, not a boxed 0.
      return RawExpr.of("null");
    }
    // Singular MESSAGE/ENUM components are @Nullable in the record (presence-tracked; see
    // scalarComponentType), so their builder-storage default is null -- not the boxed 0 the
    // typeUrl switch below would emit (which would not even compile against a reference type).
    if ((f.kind() == FieldKind.MESSAGE || f.kind() == FieldKind.ENUM)
        && f.label() != FieldLabel.REPEATED) {
      return RawExpr.of("null");
    }
    if (f.label() == FieldLabel.REPEATED) {
      // Mutable default: callers get a fresh ArrayList, never an immutable List.of().
      return RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST);
    }
    // M4-C2b-2b-13: map field → empty mutable map default.
    if (f.kind() == FieldKind.MAP) {
      return RawExpr.of("new @0<>()", TypeRefs.HASH_MAP);
    }
    return switch (f.typeUrl()) {
      case "string" -> RawExpr.of("\"\"");
      case "bytes" -> RawExpr.of("@0.empty()", TypeRefs.BYTES);
      case "bool" -> RawExpr.of("false");
      case "float" -> RawExpr.of("0.0f");
      case "double" -> RawExpr.of("0.0");
      default -> RawExpr.of("0");
    };
  }

  /**
   * M4-C2d-7: emit a map entry record for a MAP field. The entry has two components: {@code key}
   * and {@code value} (boxed types matching the proto map key/value types). The entry's body
   * contains a nested {@code <Field>EntryCodec} class via {@link
   * CodecSourceMapping#emitEntryCodec}.
   */
  @SuppressWarnings("java:S905")
  private static Type.Record mapEntryRecord(Field f, TypeCollector ci) {
    String javaName = f.javaName();
    String entryName =
        javaName.isEmpty() ? "" : Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
    entryName += "Entry";

    TypeRef keyTypeRef = TypeRef.boxedFor(Objects.requireNonNull(f.mapKey()));
    TypeRef valueTypeRef = TypeRef.boxedFor(Objects.requireNonNull(f.mapValue()));

    Type.Record.Builder result = Type.Record.named(entryName).public_();
    result.component(Component.type(keyTypeRef, "key").build());
    result.component(Component.type(valueTypeRef, "value").build());

    Type.Class entryCodec = CodecSourceMapping.emitEntryCodec(entryName, f, ci);
    result.nested(entryCodec);

    return result.build();
  }
}
