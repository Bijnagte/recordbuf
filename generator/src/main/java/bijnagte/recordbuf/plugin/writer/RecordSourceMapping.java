package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.ClassSpec;
import bijnagte.recordbuf.codegen.source.CommaList;
import bijnagte.recordbuf.codegen.source.Component;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.InterfaceSpec;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.Param;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps the generator's proto {@link Message} model into a {@link RecordSpec} of the pure-Java,
 * proto-agnostic {@code source} AST ({@code bijnagte.recordbuf.codegen.source}), replacing the
 * string-concatenation record emission with a renderable, structural tree.
 *
 * <p>{@code toSource} maps the record <em>shell</em>: header (Javadoc, {@code @Deprecated}, {@code
 * implements} clause), components (one {@link Component} per proto {@link Field}), and nested ENUMS
 * as a nested {@link Type} (reusing {@link EnumSourceMapping} for full enum core + {@code
 * BY_NUMBER} cluster).
 *
 * <p>{@code toSourceWithCodec} extends the shell with a nested {@link ClassSpec} codec that
 * dispatches through {@link CodecSourceMapping} (encode/decode for all field families: scalars,
 * repeated, map, optional, nested-message, singular/repeated-enum, oneof, and WKT via {@link
 * WktSourceMapping}). Messages with a {@code recordbuf.protobuf_java} option are handled by the
 * legacy {@link bijnagte.recordbuf.plugin.writer.InteropGenerator} JavaWriter fallback.
 */
public final class RecordSourceMapping {

  private RecordSourceMapping() {}

  /**
   * Map a proto {@link Message} model into a source-AST {@link RecordSpec} for the record shell.
   */
  public static RecordSpec toSource(Message m, String pkg) {
    TypeRef.Named recordRef = m.type();
    RecordSpec.Builder result =
        RecordSpec.named(m.type())
            .public_()
            .javadoc(
                GeneratedDocs.type(
                    m.comment(), "Auto-generated record for the " + m.name() + " proto message."))
            .deprecated(m.deprecated())
            .implementsFqns(m.interfaces().stream().map(TypeRef::fqn).toList());

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
      result.nested(EnumSourceMapping.toSource(e, pkg));
    }
    // M4-C2b-2b-12: oneof groups. Each contributes one @Nullable record component and
    // one nested sealed interface (the per-member value records live inside it).
    for (Oneof oneof : m.oneofs()) {
      OneofEmission oe = emitOneof(oneof, recordRef);
      result.component(oe.component());
      result.nested(oe.iface());
    }

    return result.build();
  }

  /**
   * Map a message model to a source-AST {@link RecordSpec} WITH the nested {@code Codec} included.
   * The codec (encode/decode static methods with real {@code List<Element>} bodies) is appended as
   * a nested {@link Type} to the record's nested list, so the rendered source contains both the
   * record shell and its codec.
   */
  public static RecordSpec toSourceWithCodec(Message m) {
    TypeRef.Named recordRef = m.type();
    // Rebuild the member list from scratch with the codec appended, mirroring toSource's logic.
    RecordSpec.Builder result =
        RecordSpec.named(m.type())
            .public_()
            .javadoc(
                GeneratedDocs.type(
                    m.comment(), "Auto-generated record for the " + m.name() + " proto message."))
            .deprecated(m.deprecated())
            .implementsFqns(m.interfaces().stream().map(TypeRef::fqn).toList())
            .impl(TypeRefs.PROTOBUF_MESSAGE);

    for (Field f : m.fields()) {
      var comp = Component.type(scalarComponentType(f), f.javaName());
      if (isComponentNullable(f)) comp.annotation(Annotation.NULLABLE);
      result.component(comp.build());
    }
    for (Enum e : m.nestedEnums()) {
      result.nested(EnumSourceMapping.toSource(e, recordRef.pkg()));
    }
    // M4-C2d-9: nested messages — emit each as an inner record via recursion.
    for (Message nested : m.nestedMessages()) {
      var nestedRec = toSourceWithCodec(nested);
      // The nested record's own type (its FQN, <Parent>.<Nested>) is carried on nested.type() —
      // built via the parent's type.sub(...) in the loader — so no package is threaded here.
      result.nested(nestedRec);
    }

    for (Oneof oneof : m.oneofs()) {
      OneofEmission oe = emitOneof(oneof, recordRef);
      result.component(oe.component());
      result.nested(oe.iface());
    }
    // nested map entry records + their codecs, before the main Codec.
    for (Field f : m.fields()) {
      if (f.kind() == FieldKind.MAP) {
        RecordSpec entry = mapEntryRecord(recordRef, f);
        result.nested(entry);
      }
    }

    // always-on unknown-field carrier: the record's last component.
    // The import is registered by the collect() phase via component.type().

    result.component(Component.type(TypeRefs.UNKNOWN_FIELDS, "unknownFields").build());

    // the fluent builder. Emitted after the carrier and after the codec (a forward
    // reference to the codec's inner-class is resolved by javac's inner-class resolution).
    BuilderEmission be = emitBuilder(m);

    String codecName = m.name() + "Codec";
    TypeRef.Named codecRef = recordRef.sub(codecName);

    result
        .method(be.factory())
        .nested(be.nested())
        .method(
            Method.named("toBuilder")
                .public_()
                .returnType(recordRef.sub("Builder"))
                .body(Element.ret("new @0.Builder(this)", m.name()))
                .build())
        .method(
            Method.named("fromBytes")
                .public_()
                .static_()
                .returnType(recordRef)
                .param(TypeRef.BYTES, "in")
                .thrown(TypeRef.IO_EXCEPTION)
                .body(Element.ret("@0.decode(in)", codecRef))
                .build())

        // The Layer A marshalling hook. Every record implements ProtobufMessage by
        // delegating to its own nested Codec.encode. Import is registered via implementsFqns by the
        // collect() phase.
        .method(
            Method.named("toByteArray")
                .public_()
                .returnType(TypeRef.BYTES)
                .thrown(TypeRef.IO_EXCEPTION)
                .body(Element.ret("@0.encode(this)", codecRef))
                .override(true)
                .build())

        // Append the nested Codec, built from the same message model.
        .nested(CodecSourceMapping.toCodec(m));
    // Layer B interop — nested Interop class when recordbuf.protobuf_java is set. The
    // protobuf-java ref type is emitted fully-qualified by the TypeCollector's collision policy, so
    // its short name never collides with the record's own name in the same compilation unit.
    if (m.protobufJava() != null && !m.protobufJava().isEmpty()) {
      // The record's own FQN is registered via the JavaFile side channel (see
      // CodeGenerator.generateRecord), so its short name occupies the declaring package and the
      // out-of-package protobuf-java reference type sharing the name is emitted fully-qualified by
      // the TypeCollector's collision policy — the ref and the record never collide.
      result.nested(InteropSourceMapping.toInterop(m, ""));
    }
    return result.build();
  }

  /**
   * The record component's Java type for a field: read from the model (see {@link Field#type()})
   * rather than re-deriving it here — the loader already decided the primitive/boxed scalar form
   * (presence tracked), the {@code List<T>} element, the map's boxed {@code Map<K, V>}, the
   * referenced record/enum's fully-typed name, and each well-known type's native Java type.
   */
  private static TypeRef scalarComponentType(Field f) {
    // The model's fully-typed reference, verbatim. The renderer's in-scope rule drops a redundant
    // enclosing qualifier for a nested member (a nested type in the same package renders bare
    // inside its enclosing record — the form the @Nullable TYPE_USE annotation requires); a
    // cross-package reference keeps its qualifier and its import.
    return f.type();
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

  /**
   * M4-C2b-2b-12 (full): emit a oneof as its {@code @Nullable} record component, a nested {@code
   * public sealed interface} with {@code extends Unwrapable<Object>}, the {@code permits} clause
   * (one per-member record per non-message/enum member, plus identity subtypes for message
   * members), the {@code default Object value()} method, and the nested per-member records.
   *
   * <p>{@code permitsFqns} are <em>pre-resolved relative references</em> — the renderer emits them
   * verbatim (no type-collection call), because nested type qualifiers like {@code
   * Choice.NameValue} are not import-eligible. Message member permits are resolved via {@code
   * TypeRef.of(...)}; the renderer collects it and resolves it to a short name.
   */
  private static OneofEmission emitOneof(Oneof oneof, TypeRef.Named recordRef) {
    String ifaceName = oneof.ifaceName();
    String componentName = oneof.componentName();
    // The oneof interface — the loader-derived, fully-typed reference (nested in the record).
    TypeRef.Named ifaceRef = oneof.iface();

    // 1. The @Nullable record component in the enclosing record.
    //    Import is registered by the collect() phase via Annotation.NULLABLE.type().
    var component = Component.type(ifaceRef, componentName).annotation(Annotation.NULLABLE).build();

    // 2. Build the sealed interface: extends clause + permits + per-member records + default
    // value().
    String unwrapFqn = TypeRefs.UNWRAPABLE.fqn();
    // The extendsFqn carries the full FQN; the collect() phase registers it via
    // TypeRef.referencedTypes().
    InterfaceSpec.Builder iface =
        InterfaceSpec.named(ifaceRef)
            .javadoc(
                GeneratedDocs.type(
                    null, "Auto-generated sealed interface for the " + ifaceName + " oneof."))
            .public_()
            .sealed_()
            .extendsFqn(unwrapFqn + "<Object>");

    // Build permits and nested per-member records simultaneously. For each oneof member
    // (scalar, enum, or message), emit a nested public record <Cap>Value(<Type> value)
    // implementing the interface, and add "<Iface>.<Cap>Value" to the permits.
    for (Field member : oneof.members()) {
      // Every oneof member (scalar, enum, or message) gets a dedicated nested record
      // <Cap>Value(<valType> value) implementing the interface. Message members cannot be
      // identity subtypes in the source-AST path because each message is its own file.
      String valName = JavaNames.cap(member.name()) + "Value";
      var valRef =
          new TypeRef.Named(
              recordRef.pkg(), List.of(recordRef.shortName(), ifaceName), valName, List.of());
      iface.permitsType(valRef);
      // The value component type, read from the model (see {@link Field#type()}) — the member's
      // referenced record/enum's fully-typed name, or its boxed/reference scalar form (oneof
      // members always carry presence, so a present-able boxed form).
      TypeRef valType = TypeRefs.boxed(member.type());
      RecordSpec wr =
          RecordSpec.named(valRef)
              .javadoc(
                  GeneratedDocs.type(
                      null, "Auto-generated record for the " + valName + " oneof value."))
              .public_()
              .implementsFqn(ifaceName) // in-package member: renders short
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
            .body(Element.ret("this"))
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
  private record OneofEmission(Component component, InterfaceSpec iface) {}

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
  private static BuilderEmission emitBuilder(Message m) {
    String recName = m.name();
    TypeRef.Named recordRef = m.type();
    // The nested static Builder class — header first, members accumulated below.
    ClassSpec.Builder builderClz =
        ClassSpec.named(recordRef.sub("Builder"))
            .javadoc(
                GeneratedDocs.type(
                    null, "Fluent, value-only builder for the " + m.name() + " record."))
            .public_()
            .static_()
            .final_();
    TypeRef builderType = recordRef.sub("Builder");

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
          Component.type(
                  new TypeRef.Named(
                      recordRef.pkg(),
                      List.of(recordRef.shortName()),
                      oneof.ifaceName(),
                      List.of()),
                  oneof.componentName())
              .annotation(Annotation.NULLABLE)
              .initializer(RawExpr.of("null"))
              .private_()
              .build());
    }
    // Carrier storage field with default empty().
    builderClz
        .field(
            Component.type(TypeRefs.UNKNOWN_FIELDS, "unknownFields")
                .initializer(RawExpr.of("@0.empty()", TypeRefs.UNKNOWN_FIELDS))
                .private_()
                .build())

        // The no-arg constructor (Java does not generate an implicit one when a parametered ctor
        // exists).
        .constructor(Constructor.builder().private_().body().build());

    // The toBuilder copy constructor.
    var copyCtor = Constructor.builder().private_().param(recordRef, "x");
    for (Field f : m.fields()) {
      String fn = f.javaName();
      if (f.label() == FieldLabel.REPEATED) {
        copyCtor.body(Element.expr("this.@0 = new @2<>(x.@1())", fn, fn, TypeRefs.ARRAY_LIST));
      } else if (f.kind() == FieldKind.MAP) {
        copyCtor.body(Element.expr("this.@0 = new @2<>(x.@1())", fn, fn, TypeRefs.HASH_MAP));
      } else {
        copyCtor.body(Element.expr("this.@0 = x.@1()", fn, fn));
      }
    }
    // M4-C2b-2b-12: oneof component copy.
    for (var oneof : m.oneofs()) {
      copyCtor.body(Element.expr("this.@0 = x.@1()", oneof.componentName(), oneof.componentName()));
    }
    builderClz.constructor(
        copyCtor.body(Element.of("this.unknownFields = x.unknownFields;")).build());

    // Fluent setter for each field. Propagate the component's nullability: a @Nullable
    // component's setter accepts null (matching the record component), so callers (e.g. the
    // Avro converter, which sees a nullable Avro value) can pass a nullable value without a
    // NullAway error.
    for (Field f : m.fields()) {
      String fieldName = f.javaName();
      Param param =
          isComponentNullable(f)
              ? Param.of(scalarComponentType(f), fieldName, Annotation.NULLABLE)
              : Param.of(scalarComponentType(f), fieldName);
      builderClz.method(
          Method.named(fieldName)
              .public_()
              .returnType(builderType)
              .param(param)
              .body(Element.expr("this.@0 = @1", fieldName, fieldName), Element.ret("this"))
              .build());
    }
    // M4-C2b-2b-12: oneof fluent setter per component.
    for (var oneof : m.oneofs()) {
      String compName = oneof.componentName();
      builderClz.method(
          Method.named(compName)
              .public_()
              .returnType(builderType)
              .param(
                  Param.of(
                      new TypeRef.Named(
                          recordRef.pkg(),
                          List.of(recordRef.shortName()),
                          oneof.ifaceName(),
                          List.of()),
                      compName,
                      Annotation.NULLABLE))
              .body(Element.expr("this.@0 = @1", compName, compName), Element.ret("this"))
              .build());
    }
    // Carrier setter.
    builderClz.method(
        Method.named("unknownFields")
            .public_()
            .returnType(builderType)
            .param(TypeRefs.UNKNOWN_FIELDS, "unknownFields")
            .body(Element.expr("this.unknownFields = unknownFields"), Element.ret("this"))
            .build());

    // build(): constructs the record.
    List<String> ctorArgs = new ArrayList<>();
    for (Field f : m.fields()) {
      ctorArgs.add(f.javaName());
    }

    for (var oneof : m.oneofs()) {
      ctorArgs.add(oneof.componentName());
    }
    ctorArgs.add("unknownFields");
    builderClz.method(
        Method.named("build")
            .javadoc(GeneratedDocs.type(null, "Builds a " + m.name() + " record."))
            .public_()
            .returnType(recordRef)
            .body(Element.ret("new @0(@1)", recName, CommaList.of(ctorArgs)))
            .build());

    ClassSpec nestedBuilder = builderClz.build();

    // The static factory on the record, with javadoc.
    var builderFactory =
        Method.named("builder")
            .javadoc(
                GeneratedDocs.type(
                    null,
                    "Static factory for a fresh "
                        + m.name()
                        + " {@link Builder}. Build it fluently, then call {@link Builder#build()}."))
            .public_()
            .static_()
            .returnType(builderType)
            .body(Element.ret("new @0.Builder()", recordRef))
            .build();

    return new BuilderEmission(builderFactory, nestedBuilder);
  }

  /**
   * The product of {@link #emitBuilder}: the static {@code builder()} factory that becomes a method
   * on the enclosing record plus the nested {@code Builder} class that gets nested on the record.
   * The caller adds both; the helper is pure and returns them rather than mutating the caller's
   * record builder.
   */
  private record BuilderEmission(Method factory, ClassSpec nested) {}

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
  private static RecordSpec mapEntryRecord(TypeRef.Named recordRef, Field f) {
    String javaName = f.javaName();
    String entryName =
        javaName.isEmpty() ? "" : Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
    entryName += "Entry";

    TypeRef keyTypeRef = TypeRef.boxedFor(Objects.requireNonNull(f.mapKey()));
    TypeRef valueTypeRef = TypeRef.boxedFor(Objects.requireNonNull(f.mapValue()));

    var entryRef = recordRef.sub(entryName);
    ClassSpec entryCodec = CodecSourceMapping.emitEntryCodec(entryRef, f);

    return RecordSpec.named(entryRef)
        .javadoc(
            GeneratedDocs.type(null, "Auto-generated record for the " + entryName + " map entry."))
        .public_()
        .component(Component.type(keyTypeRef, "key").build())
        .component(Component.type(valueTypeRef, "value").build())
        .nested(entryCodec)
        .build();
  }
}
