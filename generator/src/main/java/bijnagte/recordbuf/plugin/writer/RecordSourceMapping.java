package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Member;
import bijnagte.recordbuf.codegen.source.Statement;
import bijnagte.recordbuf.codegen.source.Type;
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
 * implements} clause), components (one {@link Member.Field} per proto {@link Field}), and nested
 * ENUMS as {@link Member.Nested} (reusing {@link EnumSourceMapping} for full enum core + {@code
 * BY_NUMBER} cluster).
 *
 * <p>{@code toSourceWithCodec} extends the shell with a nested {@link Type.Class} codec that
 * dispatches through {@link CodecSourceMapping} (encode/decode for all field families: scalars,
 * repeated, map, optional, nested-message, singular/repeated-enum, oneof, and WKT via {@link
 * WktSourceMapping}). Messages with a {@code recordbuf.protobuf_java} option are handled by the
 * legacy {@link bijnagte.recordbuf.plugin.writer.InteropGenerator} JavaWriter fallback.
 */
public final class RecordSourceMapping {

  private static final String RT = "bijnagte.recordbuf.runtime";

  private RecordSourceMapping() {}

  /**
   * Map a proto {@link Message} model into a source-AST {@link Type.Record} for the record shell.
   */
  public static Type.Record toSource(Message m, TypeCollector ci) {
    Type.Record.Builder result =
        Type.Record.builder()
            .public_()
            .javadoc(Javadoc.builder().text(m.comment()).build())
            .deprecated(m.deprecated())
            .name(m.name())
            .implementsFqns(List.copyOf(m.interfaces()));

    // 1. The components -- one Member.Field per proto field, in declaration order. Blank Javadoc +
    // no @Deprecated + blank initializer are the compact-ctor defaults, so the builder sets only
    // the
    // component's type + javaName; a record's components render in its header via components().
    for (Field f : m.fields()) {
      result.component(
          Member.Field.builder().type(scalarComponentType(f, ci)).name(f.javaName()).build());
    }

    // 2. The nested ENUMS -- each reuses the M4-C2a-ext enum mapping, so the nested enum renders
    // its
    // full core + its BY_NUMBER reverse-lookup cluster (with shared java.util.* imports).
    for (Enum e : m.nestedEnums()) {
      result.nested(new Member.Nested(EnumSourceMapping.toSource(e, ci)));
    }
    // M4-C2b-2b-12: oneof groups.
    for (Oneof oneof : m.oneofs()) {
      emitOneof(result, oneof, ci);
    }

    return result.build();
  }

  /**
   * Map a message model to a source-AST {@link Type.Record} WITH the nested {@code Codec} included.
   * The codec (encode/decode static methods with real {@code List<Statement>} bodies) is appended
   * as a {@link Member.Nested} to the record's member list, so the rendered source contains both
   * the record shell and its codec.
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
        Type.Record.builder()
            .public_()
            .javadoc(Javadoc.builder().text(m.comment()).build())
            .deprecated(m.deprecated())
            .name(m.name());
    for (Field f : m.fields()) {
      result.component(
          Member.Field.builder().type(scalarComponentType(f, ci)).name(f.javaName()).build());
    }
    for (Enum e : m.nestedEnums()) {
      result.nested(new Member.Nested(EnumSourceMapping.toSource(e, ci)));
    }
    // M4-C2d-9: nested messages — emit each as an inner record via recursion.
    for (Message nested : m.nestedMessages()) {
      String nestedFqn = nested.fullName(); // not used — just ensure ci knows the type
      var nestedRec =
          toSourceWithCodec(
              nested, ci, f -> f.kind() == FieldKind.MESSAGE ? f.typeUrl() + ".Codec" : null);
      // The nested type's FQN must be <Parent>.<Nested> — the TypeCollector handles the
      // package qualification, so just ensure the ci knows about it.
      result.nested(new Member.Nested(nestedRec));
    }
    // M4-C2b-2b-12: oneof groups.
    for (Oneof oneof : m.oneofs()) {
      emitOneof(result, oneof, ci);
    }
    // M4-C2d-7: nested map entry records + their codecs, before the main Codec.
    for (Field f : m.fields()) {
      if (f.kind() == FieldKind.MAP) {
        Type.Record entry = mapEntryRecord(f, ci);
        result.nested(new Member.Nested(entry));
      }
    }

    // M4-C2b-2b-5: always-on unknown-field carrier: the record's last component.
    String ufFqn = RT + ".UnknownFields";
    ci.use(ufFqn);
    // M4-C2d-3: register java.util.ArrayList for the codec's repeated-local-init lines,
    // and java.util.List for the record's repeated-field components.
    ci.use("java.util.ArrayList");
    result.component(Member.Field.builder().type(ufFqn).name("unknownFields").build());

    // M4-C2b-2b-6: the fluent builder. Emitted after the carrier and after the codec (a forward
    // reference to the codec's inner-class is resolved by javac's inner-class resolution).
    emitBuilder(result, m, ci, ufFqn);
    // M4-C2b-2b-8: toBuilder + fromBytes (old path parity)
    String codecName = m.name() + "Codec";
    result.method(
        Member.Method.builder()
            .public_()
            .returnType("Builder")
            .name("toBuilder")
            .body(Statement.of("return new " + m.name() + ".Builder(this);"))
            .build());
    result.method(
        Member.Method.builder()
            .public_()
            .static_()
            .returnType(m.name())
            .name("fromBytes")
            .param("byte[]", "in")
            .thrown(List.of("java.io.IOException"))
            .body(Statement.of("return " + codecName + ".decode(in);"))
            .build());

    // M4-C2b-2b-5: the Layer A marshalling hook. Every record implements ProtobufMessage by
    // delegating to its own nested Codec.encode.
    String pbMsgFqn = RT + ".ProtobufMessage";
    ci.use(pbMsgFqn);
    var toByteArray =
        Member.Method.builder()
            .public_()
            .returnType("byte[]")
            .name("toByteArray")
            .thrown(List.of("java.io.IOException"))
            .body(Statement.of("return " + m.name() + "Codec.encode(this);"))
            .override(true)
            .build();
    result.method(toByteArray);

    // M4-C2b-2c: append the nested Codec, built from the same message model.
    Type.Class codec = CodecSourceMapping.toCodec(m, ci, typeResolver);
    result.nested(new Member.Nested(codec));

    // M4-C2e: Layer B interop — nested Interop class when recordbuf.protobuf_java is set.
    // useFqn is used for the protobuf-java ref type to avoid a short-name import that
    // would collide with the record's own name in the same compilation unit.
    if (m.protobufJava() != null && !m.protobufJava().isEmpty()) {
      result.nested(new Member.Nested(InteropSourceMapping.toInterop(m, ci, "")));
    }

    // Build the implements list: user-declared interfaces first, then ProtobufMessage
    // (matches the old JavaWriter ordering: option interfaces precede the runtime marker).
    List<String> implementsList = new ArrayList<>();
    implementsList.addAll(m.interfaces());
    implementsList.add(pbMsgFqn);
    result.implementsFqns(implementsList);

    return result.build();
  }

  /**
   * The record component's Java type for the M4-C2b-1 scalar subset. A non-scalar field -- a nested
   * message, an enum, a repeated / optional field, a map, or a WKT -- throws here: its component
   * type is M4-C2b-2+ work and is not in this shell's scope.
   */
  /**
   * The record component's Java type for the M4-C2b scalar subset. A non-scalar field throws here;
   * repeated scalars resolve to {@code List<T>} where T is the boxed Java element type; singular
   * scalars resolve to their Java primitive or boxed type. The {@link TypeCollector} collects
   * cross-package type references so the render pass can emit correct imports.
   */
  private static String scalarComponentType(Field f, TypeCollector ci) {
    // M4-C2b-2b-13: map<K, V> field. Key + value are proto scalar type URLs;
    // each is rendered as its boxed Java type (primitives get boxed inside Map<>).
    // Must run BEFORE the REPEATED label check (maps use REPEATED but are not Lists).
    if (f.kind() == FieldKind.MAP) {
      ci.use("java.util.Map");
      // MAP's mapKey/mapValue are @Nullable String; a MAP field always has both.
      String keyType = boxedScalarType(Objects.requireNonNull(f.mapKey()));
      String valueType = boxedScalarType(Objects.requireNonNull(f.mapValue()));
      return "Map<" + keyType + ", " + valueType + ">";
    }
    // M4-C2d-2: repeated MESSAGE/ENUM → List<ElementType>, element resolved per kind.
    if (f.label() == FieldLabel.REPEATED) {
      ci.use("java.util.List");
      return "List<" + elementComponentType(f, ci) + ">";
    }
    // M4-C2d-12: WKT fields → native Java type from WellKnownTypes (e.g. Timestamp→Instant,
    // Int32Value→Integer). Singular WKT fields are @Nullable (presence-tracked; null = unset).
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      ci.use("org.jspecify.annotations.Nullable");
      return "@Nullable " + ci.use(Objects.requireNonNull(wkt.javaType()));
    }
    // M4-C2d-2: singular MESSAGE/ENUM → the resolved short type name.
    // Singular message fields are presence-tracked, so they're @Nullable in the record;
    // the builder copy constructor and codec must null-guard accordingly.
    switch (f.kind()) {
      case MESSAGE:
      case ENUM:
        ci.use("org.jspecify.annotations.Nullable");
        return "@Nullable " + ci.use(javaSimpleName(f.typeUrl()));
      default:
        break;
    }
    // M4-C2d-1: optional scalar → @Nullable boxed wrapper.
    if (f.isNullable()) {
      ci.use("org.jspecify.annotations.Nullable");
      return "@Nullable " + boxedScalarComponent(f.typeUrl());
    }
    return switch (f.typeUrl()) {
      case "int32", "uint32", "fixed32", "sfixed32", "sint32" -> "int";
      case "int64", "uint64", "fixed64", "sfixed64", "sint64" -> "long";
      case "float" -> "float";
      case "double" -> "double";
      case "bool" -> "boolean";
      case "string" -> "String";
      case "bytes" -> ci.use(RT + ".Bytes");
      default ->
          throw new UnsupportedOperationException(
              "scalar " + f.typeUrl() + " component type is not in the M4-C2b-1 subset");
    };
  }

  /** The inner element type of a {@code repeated X} field, resolved per {@link FieldKind}. */
  private static String elementComponentType(Field f, TypeCollector ci) {
    // M4-C2d-12: WKT elements use the native Java type.
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      return ci.use(Objects.requireNonNull(wkt.javaType()));
    }
    return switch (f.kind()) {
      case MESSAGE, ENUM -> ci.use(javaSimpleName(f.typeUrl()));
      default -> boxedScalarComponent(f.typeUrl());
    };
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
   * verbatim (no {@code ci.use()} call), because nested type qualifiers like {@code
   * Choice.NameValue} are not import-eligible. Message member permits are resolved via {@code
   * ci.use(typeUrl)} to get their short name.
   */
  private static void emitOneof(Type.Record.Builder record, Oneof oneof, TypeCollector ci) {
    String ifaceName = oneof.ifaceName();
    String componentName = oneof.componentName();

    // 1. The @Nullable record component in the enclosing record.
    ci.use("org.jspecify.annotations.Nullable");
    record.component(
        Member.Field.builder().type("@Nullable " + ifaceName).name(componentName).build());

    // 2. Build the sealed interface: extends clause + permits + per-member records + default
    // value().
    String unwrapFqn = RT + ".Unwrapable";
    // Pre-resolve the extends FQN via ci.use() (it preserves generics and registers the import).
    String ext = ci.use(unwrapFqn + "<Object>");

    // Build the sealed interface: extends clause first, then permits + per-member nested records +
    // default value() as we scan the members.
    Type.Interface.Builder iface =
        Type.Interface.builder().public_().sealed_().name(ifaceName).extendsFqns(List.of(ext));

    // Build permits and nested per-member records simultaneously. For each oneof member
    // (scalar, enum, or message), emit a nested public record <Cap>Value(<Type> value)
    // implementing the interface, and add "<Iface>.<Cap>Value" to the permits.
    for (Field member : oneof.members()) {
      // Every oneof member (scalar, enum, or message) gets a dedicated nested record
      // <Cap>Value(<valType> value) implementing the interface. Message members cannot be
      // identity subtypes in the source-AST path because each message is its own file.
      String valName = JavaNames.cap(member.name()) + "Value";
      iface.permitsFqn(ifaceName + "." + valName);
      // The value component type: for enum → the enum's short name (same-package, no
      // import); for scalar → the boxed Java type; for message → the message's short type
      // reference (resolved via the type resolver).
      String valType;
      if (member.isMessage()) {
        valType = ci.use(javaSimpleName(member.typeUrl()));
      } else if (member.kind() == FieldKind.ENUM) {
        valType = ci.use(javaSimpleName(member.typeUrl()));
      } else {
        valType = boxedScalarType(member.typeUrl());
      }
      Type.Record wr =
          Type.Record.builder()
              .public_()
              .name(valName)
              .implementsFqns(List.of(ifaceName))
              .component(Member.Field.builder().type(valType).name("value").build())
              .build();
      iface.nested(new Member.Nested(wr));
    }

    // The default value() method (overrides the Unwrapable.value()). Use `default` keyword in an
    // interface; @Override would be an abstract method WITH a body (illegal).
    var defaultMethod =
        Member.Method.builder()
            .default_()
            .returnType("Object")
            .name("value")
            .body(Statement.of("return this;"))
            .build();
    iface.method(defaultMethod);

    record.nested(new Member.Nested(iface.build()));
  }

  /**
   * The boxed Java type for a proto scalar type URL (for use inside {@code Map<>, List<>}, or as a
   * {@code @Nullable} wrapper). Primitives are boxed; {@code string}→{@code String}; {@code
   * bytes}→runtime {@code Bytes}.
   */
  private static String boxedScalarType(String typeUrl) {
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

  /** The short-form boxed Java component type for a repeated scalar's element. */
  private static String boxedScalarComponent(String typeUrl) {
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

  // M4-C2b-2b-6: builder emission. A minimal value-only builder for scalar messages.
  // Returns Members: a static builder() factory + a nested Builder class.
  // The Builder is a public static final inner class with:
  // - one private field per component (with default initializer)
  // - one fluent setter per component
  // - private unknownFields field with default empty()
  // - public build() terminal
  private static void emitBuilder(
      Type.Record.Builder record, Message m, TypeCollector ci, String ufFqn) {
    String recName = m.name();
    // The nested static Builder class — header first, members accumulated below.
    Type.Class.Builder builderClz =
        Type.Class.builder()
            .javadoc(
                Javadoc.builder()
                    .text("Fluent, value-only builder for the ", m.name(), " record.")
                    .build())
            .name("Builder")
            .public_()
            .static_()
            .final_();

    // Storage fields with codec defaults for each component field.
    // Register container imports up-front so the TypeCollector has them when the render pass
    // resolves field types (the builder's copy-constructor and fresh defaults may use
    // new ArrayList / new HashMap).
    for (Field f : m.fields()) {
      if (f.label() == FieldLabel.REPEATED) ci.use("java.util.ArrayList");
      if (f.kind() == FieldKind.MAP) ci.use("java.util.HashMap");
    }
    for (Field f : m.fields()) {
      String fieldDefault = defaultFor(f);
      builderClz.field(
          Member.Field.builder()
              .type(scalarComponentType(f, ci))
              .name(f.javaName())
              .initializer(fieldDefault)
              .private_()
              .build());
    }
    // M4-C2b-2b-12: oneof component storage fields. Each defaults to null.
    for (var oneof : m.oneofs()) {
      builderClz.field(
          Member.Field.builder()
              .type("@Nullable " + oneof.ifaceName())
              .name(oneof.componentName())
              .initializer("null")
              .private_()
              .build());
    }
    // Carrier storage field with default empty().
    String ufShort = ci.use(ufFqn);
    builderClz.field(
        Member.Field.builder()
            .type(ufShort)
            .name("unknownFields")
            .initializer(ufShort + ".empty()")
            .private_()
            .build());

    // The no-arg constructor (Java does not generate an implicit one when a parametered ctor
    // exists).
    builderClz.constructor(Member.Constructor.builder().private_().name("Builder").body().build());

    // The toBuilder copy constructor.
    List<Statement> copyBody = new ArrayList<>();
    for (Field f : m.fields()) {
      String fn = f.javaName();
      if (f.label() == FieldLabel.REPEATED) {
        copyBody.add(Statement.of("this." + fn + " = new ArrayList<>(x." + fn + "());"));
      } else if (f.kind() == FieldKind.MAP) {
        copyBody.add(Statement.of("this." + fn + " = new HashMap<>(x." + fn + "());"));
      } else {
        copyBody.add(Statement.of("this." + fn + " = x." + fn + "();"));
      }
    }
    // M4-C2b-2b-12: oneof component copy.
    for (var oneof : m.oneofs()) {
      copyBody.add(
          Statement.of("this." + oneof.componentName() + " = x." + oneof.componentName() + "();"));
    }
    copyBody.add(Statement.of("this.unknownFields = x.unknownFields();"));
    builderClz.constructor(
        Member.Constructor.builder()
            .private_()
            .name("Builder")
            .param(recName, "x")
            .body(copyBody)
            .build());

    // Fluent setter for each field.
    for (Field f : m.fields()) {
      String fieldName = f.javaName();
      builderClz.method(
          Member.Method.builder()
              .public_()
              .returnType("Builder")
              .name(fieldName)
              .param(scalarComponentType(f, ci), fieldName)
              .body(
                  Statement.of("this." + fieldName + " = " + fieldName + ";"),
                  Statement.of("return this;"))
              .build());
    }
    // M4-C2b-2b-12: oneof fluent setter per component.
    for (var oneof : m.oneofs()) {
      String compName = oneof.componentName();
      builderClz.method(
          Member.Method.builder()
              .public_()
              .returnType("Builder")
              .name(compName)
              .param("@Nullable " + oneof.ifaceName(), compName)
              .body(
                  Statement.of("this." + compName + " = " + compName + ";"),
                  Statement.of("return this;"))
              .build());
    }
    // Carrier setter.
    builderClz.method(
        Member.Method.builder()
            .public_()
            .returnType("Builder")
            .name("unknownFields")
            .param(ufShort, "unknownFields")
            .body(Statement.of("this.unknownFields = unknownFields;"), Statement.of("return this;"))
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
        Member.Method.builder()
            .javadoc(Javadoc.builder().text("Builds a ", m.name(), " record.").build())
            .public_()
            .returnType(recName)
            .name("build")
            .body(Statement.of("return new " + recName + "(" + String.join(", ", ctorArgs) + ");"))
            .build());

    // The nested Builder class itself — with javadoc matching the old JavaWriter path.
    Type.Class nestedBuilder = builderClz.build();

    // The static factory on the record, with javadoc.
    var builderFactory =
        Member.Method.builder()
            .javadoc(
                Javadoc.builder()
                    .text(
                        "Static factory for a fresh ",
                        m.name(),
                        " {@link Builder}. Build it fluently, then call {@link Builder#build()}.")
                    .build())
            .public_()
            .static_()
            .returnType("Builder")
            .name("builder")
            .body(Statement.of("return new " + recName + ".Builder();"))
            .build();

    record.method(builderFactory);
    record.nested(new Member.Nested(nestedBuilder));
  }

  /**
   * The default initializer for a builder's storage field: "" for String, 0 for numeric, null for
   * others.
   */
  private static String defaultFor(Field f) {
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      if (f.label() == FieldLabel.REPEATED) return "new ArrayList\u003c\u003e()";
      var z = wkt.zeroDefault();
      if (z != null) return z;
      return "null";
    }
    if (f.isNullable()) {
      // M4-C2d-1: optional scalar → null default, not a boxed 0.
      return "null";
    }
    if (f.label() == FieldLabel.REPEATED) {
      // Mutable default: callers get a fresh ArrayList, never an immutable List.of().
      return "new ArrayList<>()";
    }
    // M4-C2b-2b-13: map field → empty mutable map default.
    if (f.kind() == FieldKind.MAP) {
      return "new HashMap<>()";
    }
    return switch (f.typeUrl()) {
      case "string" -> "\"\"";
      case "bytes" -> "Bytes.empty()";
      case "bool" -> "false";
      case "float" -> "0.0f";
      case "double" -> "0.0";
      default -> "0";
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

    String keyType = boxedScalarType(Objects.requireNonNull(f.mapKey()));
    String valueType = boxedScalarType(Objects.requireNonNull(f.mapValue()));

    Type.Record.Builder result = Type.Record.builder().public_().name(entryName);
    result.component(Member.Field.builder().type(keyType).name("key").build());
    result.component(Member.Field.builder().type(valueType).name("value").build());

    Type.Class entryCodec = CodecSourceMapping.emitEntryCodec(entryName, f, ci);
    result.nested(new Member.Nested(entryCodec));

    return result.build();
  }
}
