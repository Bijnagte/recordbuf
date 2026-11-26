package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.Arg;
import bijnagte.recordbuf.codegen.source.ArgValue;
import bijnagte.recordbuf.codegen.source.ClassSpec;
import bijnagte.recordbuf.codegen.source.CommaList;
import bijnagte.recordbuf.codegen.source.Component;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.InterfaceSpec;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.Param;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.json.JavaTimeTemporal;
import bijnagte.recordbuf.json.JsonBytes;
import bijnagte.recordbuf.json.JsonEmpty;
import bijnagte.recordbuf.json.JsonFieldMask;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.JacksonTemporalFormat;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
 * legacy {@code bijnagte.recordbuf.plugin.writer.InteropGenerator} JavaWriter fallback.
 */
public final class RecordSourceMapping {

  private RecordSourceMapping() {}

  /**
   * Map a proto {@link Message} model into a source-AST {@link RecordSpec} for the record shell,
   * with no Jackson JSON surface (the default: a file without {@code recordbuf.jackson}).
   */
  public static RecordSpec toSource(Message m, String pkg) {
    return toSource(m, pkg, false);
  }

  /**
   * Map a proto {@link Message} model into a source-AST {@link RecordSpec} for the record shell.
   *
   * @param jackson when {@code true} (a file with {@code recordbuf.jackson = true}) every
   *     non-ignored component carries an explicit {@code @JsonProperty} (its resolved JSON name —
   *     the proto3 camelCase mapping, or the {@code recordbuf.json_name} override) and an ignored
   *     field carries {@code @JsonIgnore} instead; when {@code false} no Jackson annotation is
   *     emitted and the rendered source is byte-identical to the pre-feature output.
   */
  public static RecordSpec toSource(Message m, String pkg, boolean jackson) {
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
      jacksonAnnotation(comp, f, jackson);
      temporalAnnotation(comp, f, jackson);
      runtimeValueAnnotation(comp, f, jackson);
      if (isComponentNullable(f)) comp.annotation(Annotation.NULLABLE);
      result.component(comp.build());
    }
    // its
    // full core + its BY_NUMBER reverse-lookup cluster (with shared java.util.* imports).
    for (Enum e : m.nestedEnums()) {
      result.nested(EnumSourceMapping.toSource(e, pkg, jackson));
    }
    // M4-C2b-2b-12: oneof groups. Each contributes one @Nullable record component and
    // one nested sealed interface (the per-member value records live inside it).
    for (Oneof oneof : m.oneofs()) {
      OneofEmission oe = emitOneof(oneof, recordRef, false);
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
    return toSourceWithCodec(m, false);
  }

  /**
   * Map a message to its source-AST form (record shell + nested {@code Codec}), with the Jackson
   * surface switch: see {@link #toSource(Message, String, boolean)} for the annotation surface.
   * When {@code jackson} the record additionally carries the {@code jsonExtras} component (the
   * any-getter/any-setter retention pair) and a compact constructor normalizing it and the carrier.
   * The extras are JSON-only: the codec neither writes them to the wire nor reads them from it.
   */
  public static RecordSpec toSourceWithCodec(Message m, boolean jackson) {
    TypeRef.Named recordRef = m.type();
    // Rebuild the member list from scratch with the codec appended, mirroring toSource's logic.
    RecordSpec.Builder result =
        RecordSpec.named(m.type())
            .public_()
            .javadoc(
                GeneratedDocs.type(
                    m.comment(),
                    baseRecordDescription(m)
                        + (jackson
                            ? "\n@"
                                + "param jsonExtras retained unknown JSON properties, keyed by"
                                + " property name; projected flat onto the JSON object and"
                                + " absorbed from it on deserialization (JSON only: never written"
                                + " to the wire)"
                            : ".")))
            .deprecated(m.deprecated())
            .implementsFqns(m.interfaces().stream().map(TypeRef::fqn).toList())
            .impl(TypeRefs.PROTOBUF_MESSAGE);
    if (jackson) {
      // Absent values (an unset message / optional / wrapper / oneof) are omitted from the JSON
      // object rather than written as null; a missing property still reads back as null.
      result.annotation(jsonIncludeNonNull());
    }
    for (Field f : m.fields()) {
      var comp = Component.type(scalarComponentType(f), f.javaName());
      jacksonAnnotation(comp, f, jackson);
      temporalAnnotation(comp, f, jackson);
      runtimeValueAnnotation(comp, f, jackson);
      if (isComponentNullable(f)) comp.annotation(Annotation.NULLABLE);
      Component c = comp.build();
      result.component(c);
    }
    for (Enum e : m.nestedEnums()) {
      result.nested(EnumSourceMapping.toSource(e, recordRef.pkg(), jackson));
    }
    // M4-C2d-9: nested messages — emit each as an inner record via recursion.
    for (Message nested : m.nestedMessages()) {
      var nestedRec = toSourceWithCodec(nested, jackson);
      // The nested record's own type (its FQN, <Parent>.<Nested>) is carried on nested.type() —
      // built via the parent's type.sub(...) in the loader — so no package is threaded here.
      result.nested(nestedRec);
    }

    for (Oneof oneof : m.oneofs()) {
      OneofEmission oe = emitOneof(oneof, recordRef, jackson);
      Component c = oe.component();
      result.component(c);
      result.nested(oe.iface());
    }
    // nested map entry records + their codecs, before the main Codec.
    for (Field f : m.fields()) {
      if (f.kind() == FieldKind.MAP) {
        RecordSpec entry = mapEntryRecord(recordRef, f);
        result.nested(entry);
      }
    }

    // The unknown-JSON-property retention pair (a file with recordbuf.jackson): the extras map IS
    // a record component, annotated @JsonIgnore @JsonAnyGetter @JsonAnySetter on the record
    // parameter (no accessor pair): Jackson honors the annotations on the record parameter
    // (canonical constructor = the implicit creator) and on the implicit accessor — the map
    // projects flat onto the JSON object and absorbs unknown JSON properties into itself during
    // deserialization. Emitted before the carrier so the carrier stays the record's last
    // component.
    if (jackson) {
      var extras =
          Component.type(TypeRefs.JSON_NODE_MAP, "jsonExtras")
              .annotation(Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonIgnore")))
              .annotation(
                  Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonAnyGetter")))
              .annotation(
                  Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonAnySetter")))
              .annotation(Annotation.NULLABLE);
      Component extrasComp = extras.build();
      result.component(extrasComp);
      // The record header's Javadoc (set above) carries the jsonExtras documentation as a @param
      // tag: record components cannot carry their own Javadoc block (Error Prone's NotJavadoc).
    }
    // always-on unknown-field carrier: the record's last component.
    // The import is registered by the collect() phase via component.type().
    // Jackson-hidden when the file opts in: the binary carrier never appears in the record's JSON
    // (the "known fields + retained JSON extras" shape) — re-emitting its captured bytes is the
    // codec's wire job, not Jackson's.
    var carrier = Component.type(TypeRefs.UNKNOWN_FIELDS, "unknownFields");
    if (jackson) {
      carrier.annotation(Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonIgnore")));
    }
    Component carrierComp = carrier.build();
    result.component(carrierComp);

    if (jackson) {
      // The record-compact constructor (no parameter list, no parens) is the normalization point:
      // the components are its implicit parameters, bound to the backing fields, so the body can
      // re-assign them in place. It runs after the (synthesized, all-args) canonical constructor
      // on every construction path — the Jackson creator, a direct {@code new}, and the builder —
      // so each path lands on non-null, mutable state. Jackson passes its own map for the extras
      // component (or null when no unknown properties were present) and never for the carrier (it
      // is @JsonIgnore'd — absent from the JSON entirely), so both are normalized here.
      //
      // A *parameterless* form is deliberate: an explicitly declared canonical constructor would
      // re-stamp the component's annotations onto its own parameters, and {@code @JsonIgnore} /
      // {@code @JsonAnyGetter} carry no {@code @Target(PARAMETER)} — a javac "annotation
      // interface not applicable" error. The header carries the any-surface annotations (they bind
      // to the field and accessor, which is what Jackson reads); the synthesized canonical
      // constructor needs none. `public_()` is required: JLS 8.10.2 mandates a compact
      // constructor's access match the canonical constructor's — a public record needs a
      // public compact constructor (a bare one is a javac "invalid canonical constructor"
      // error).
      Constructor.Builder cc =
          Constructor.builder()
              .public_()
              .compact()
              .javadoc(
                  Javadoc.builder()
                      .text(
                          GeneratedDocs.MARKER
                              + " Normalizes the components on every construction path: "
                              + "null singular scalars become their proto3 defaults, the "
                              + "carrier becomes empty(), and the retained-JSON-properties map "
                              + "becomes a fresh mutable map. Jackson's implicit creator, a "
                              + "direct `new`, and the builder all pass through it.\n")
                      .build());
      // Per-field null normalization: an explicit JSON null (or a missing property) reaches the
      // component as null, and proto3 says a missing singular scalar is its default — so null is
      // re-bound to the codec default. Fields whose builder default IS null (presence-tracked
      // message/enum, explicit `optional`, oneof) are skipped: there null means "absent", which
      // the codec already encodes correctly.
      for (Field f : m.fields()) {
        // Skip: a null-default field (presence-tracked — null means "absent") or a Java
        // primitive component (never null — Jackson maps missing/explicit-null onto the
        // primitive itself, e.g. 0/false).
        if (hasNullDefault(f) || f.type() instanceof TypeRef.Primitive) continue;
        String n = f.javaName();
        cc.body(Element.expr(RawExpr.of(n + " = " + n + " == null ? @0 : " + n, defaultFor(f))));
      }
      cc.body(
          If.condition("unknownFields == null")
              .body(Element.expr(RawExpr.of("unknownFields = @0.empty()", TypeRefs.UNKNOWN_FIELDS)))
              .build());
      cc.body(
          Element.expr(
              RawExpr.of(
                  "jsonExtras = new @0<>(jsonExtras == null ? new @0<>() : jsonExtras)",
                  TypeRefs.LINKED_HASH_MAP)));
      result.constructor(cc.build());
    }

    // the fluent builder. Emitted after the carrier and after the codec (a forward
    // reference to the codec's inner-class is resolved by javac's inner-class resolution).
    BuilderEmission be = emitBuilder(m, jackson);

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
        .nested(CodecSourceMapping.toCodec(m, jackson));
    // Layer B interop — nested Interop class when recordbuf.protobuf_java is set. The
    // protobuf-java ref type is emitted fully-qualified by the TypeCollector's collision policy, so
    // its short name never collides with the record's own name in the same compilation unit.
    if (m.protobufJava() != null && !m.protobufJava().isEmpty()) {
      // The record's own FQN is registered via the JavaFile side channel (see
      // CodeGenerator.generateRecord), so its short name occupies the declaring package and the
      // out-of-package protobuf-java reference type sharing the name is emitted fully-qualified by
      // the TypeCollector's collision policy — the ref and the record never collide.
      result.nested(InteropSourceMapping.toInterop(m, "", jackson));
    }
    // U7: a jackson record with oneofs renders them flat -- the discriminator method(s) and the
    // @JsonCreator factory that reads the flat form back.
    if (jackson && !m.oneofs().isEmpty()) {
      oneofJsonSurface(m).forEach(result::method);
    }
    return result.build();
  }

  private static String baseRecordDescription(Message m) {
    return "Auto-generated record for the " + m.name() + " proto message.";
  }

  /**
   * Attach the temporal (de)serializer pair to a temporal well-known type field (a {@code
   * google.protobuf.Timestamp} / {@code google.protobuf.Duration} — the record holds the java.time
   * types): an explicit {@code @JsonSerialize} + {@code @JsonDeserialize} naming the
   * :recordbuf-json {@code JavaTimeTemporal} arm matching the field's {@code
   * recordbuf.jackson_format} (default: the ISO string). A singular field sets both {@code using} +
   * {@code contentUsing} (the content is inert on a scalar); a repeated field sets {@code
   * contentUsing} only — the element type, so the list keeps its own container serialization.
   */
  private static void temporalAnnotation(Component.Builder comp, Field f, boolean jackson) {
    temporalAnnotations(f, jackson).forEach(comp::annotation);
  }

  /** The {@link #temporalAnnotation} pair as annotations (empty when the field needs none). */
  private static List<Annotation> temporalAnnotations(Field f, boolean jackson) {
    if (!jackson) {
      return List.of();
    }
    WellKnownTypes.Entry entry = WellKnownTypes.resolve(f.typeUrl());
    if (entry == null) {
      return List.of();
    }
    bijnagte.recordbuf.json.JacksonTemporalFormat format =
        f.jacksonFormat() == null
            ? bijnagte.recordbuf.json.JacksonTemporalFormat.JSON
            : switch (f.jacksonFormat()) {
              case JacksonTemporalFormat.JSON -> bijnagte.recordbuf.json.JacksonTemporalFormat.JSON;
              case JacksonTemporalFormat.CANONICAL ->
                  bijnagte.recordbuf.json.JacksonTemporalFormat.CANONICAL;
            };
    var c = JavaTimeTemporal.forType(entry.fqn(), format);
    if (c == null) {
      return List.of();
    }
    return serializerPair(
        f.label() == FieldLabel.REPEATED,
        TypeRef.of(c.serializer.getName()),
        TypeRef.of(c.deserializer.getName()));
  }

  /**
   * The {@code @JsonSerialize} + {@code @JsonDeserialize} pair naming {@code ser} / {@code de}: a
   * single value sets {@code using} + {@code contentUsing} (the content is inert on a scalar); a
   * container (a repeated field, a map) sets {@code contentUsing} only -- its elements / values, so
   * the container keeps its own serialization.
   */
  private static List<Annotation> serializerPair(boolean container, TypeRef ser, TypeRef de) {
    List<Arg> serArgs = new ArrayList<>();
    List<Arg> deArgs = new ArrayList<>();
    if (!container) {
      serArgs.add(Arg.of("using", ArgValue.classLit(ser)));
      deArgs.add(Arg.of("using", ArgValue.classLit(de)));
    }
    serArgs.add(Arg.of("contentUsing", ArgValue.classLit(ser)));
    deArgs.add(Arg.of("contentUsing", ArgValue.classLit(de)));
    return List.of(
        Annotation.of(
            TypeRef.of("tools.jackson.databind.annotation.JsonSerialize"),
            serArgs.toArray(new Arg[0])),
        Annotation.of(
            TypeRef.of("tools.jackson.databind.annotation.JsonDeserialize"),
            deArgs.toArray(new Arg[0])));
  }

  /**
   * Attach the proto3 JSON (de)serializer pair for a runtime value type to a field on a jackson
   * file: {@code JsonBytes} (base64) for a {@code bytes}-family field (scalar {@code bytes} or
   * {@code google.protobuf.BytesValue}), {@code JsonFieldMask} (an array of lowerCamel paths) for a
   * {@code FieldMask}, {@code JsonEmpty} ({@code {}}) for an {@code Empty} — singular, repeated, or
   * as a map value. A singular field sets {@code using} + {@code contentUsing}; a repeated or map
   * field sets {@code contentUsing} only (its elements / values), as for temporals.
   */
  private static void runtimeValueAnnotation(Component.Builder comp, Field f, boolean jackson) {
    runtimeValueAnnotations(f, jackson).forEach(comp::annotation);
  }

  /** The {@link #runtimeValueAnnotation} pair as annotations (empty when the field needs none). */
  private static List<Annotation> runtimeValueAnnotations(Field f, boolean jackson) {
    if (!jackson) {
      return List.of();
    }
    String token = f.isMap() ? f.mapValue() : f.typeUrl();
    Class<?>[] pair = token == null ? null : runtimeValuePair(token);
    if (pair == null) {
      return List.of();
    }
    return serializerPair(
        f.label() == FieldLabel.REPEATED || f.isMap(),
        TypeRef.of(pair[0].getName()),
        TypeRef.of(pair[1].getName()));
  }

  /**
   * The {serializer, deserializer} pair for a field / map-value type token that resolves to a
   * runtime value type, or {@code null} when the token needs none.
   */
  private static Class<?> @Nullable [] runtimeValuePair(String token) {
    if (token.equals("bytes")) {
      return new Class<?>[] {JsonBytes.B64Serializer.class, JsonBytes.B64Deserializer.class};
    }
    WellKnownTypes.Entry entry = WellKnownTypes.resolve(token);
    if (entry == null) {
      return null;
    }
    if (entry.kind() == WellKnownTypes.Kind.BYTES_WRAPPER) {
      return new Class<?>[] {JsonBytes.B64Serializer.class, JsonBytes.B64Deserializer.class};
    }
    if (TypeRefs.FIELD_MASK.equals(entry.javaType())) {
      return new Class<?>[] {JsonFieldMask.Serializer.class, JsonFieldMask.Deserializer.class};
    }
    if (TypeRefs.EMPTY.equals(entry.javaType())) {
      return new Class<?>[] {JsonEmpty.Serializer.class, JsonEmpty.Deserializer.class};
    }
    return null;
  }

  /**
   * Attach the Jackson JSON-surface annotation to a component when the file opts in: an explicit
   * {@code @JsonProperty("<jsonName>")} (the resolved name — the proto3 camelCase mapping or the
   * {@code recordbuf.json_name} override), or {@code @JsonIgnore} for an ignored field. The
   * annotation renders BEFORE the component's {@code @Nullable} (declared first in the node's
   * annotation list), ahead of the type.
   */
  private static void jacksonAnnotation(Component.Builder comp, Field f, boolean jackson) {
    if (!jackson) return;
    if (f.jsonIgnored()) {
      comp.annotation(Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonIgnore")));
    } else {
      comp.annotation(
          Annotation.of(
              TypeRef.of("com.fasterxml.jackson.annotation.JsonProperty"),
              Arg.of(null, f.jsonName())));
      if (f.type() instanceof TypeRef.Primitive) {
        // A missing or null primitive is its proto3 default (0 / false): Jackson 3 otherwise fails
        // it (FAIL_ON_NULL_FOR_PRIMITIVES is on by default), so partial JSON could not be read.
        comp.annotation(
            Annotation.of(
                TypeRef.of("com.fasterxml.jackson.annotation.JsonSetter"),
                Arg.of(
                    "nulls",
                    new ArgValue.Ref(
                        TypeRef.named("com.fasterxml.jackson.annotation", "Nulls"), ".AS_EMPTY"))));
      }
    }
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
  private static OneofEmission emitOneof(Oneof oneof, TypeRef.Named recordRef, boolean jackson) {
    String ifaceName = oneof.ifaceName();
    String componentName = oneof.componentName();
    // The oneof interface — the loader-derived, fully-typed reference (nested in the record).
    TypeRef.Named ifaceRef = oneof.iface();

    // 1. The @Nullable record component in the enclosing record.
    //    Import is registered by the collect() phase via Annotation.NULLABLE.type().
    var componentBuilder = Component.type(ifaceRef, componentName);
    if (jackson) {
      // Flat JSON (U7): the set member's wrapper is inlined into the record's object -- its single
      // renamed property appears at the top level. An unset oneof contributes nothing through the
      // record-level @JsonInclude(NON_NULL). The component additionally carries a distinct
      // @JsonProperty name (the bare oneof name is reserved for the discriminator getter); because
      // @JsonUnwrapped never emits the component's own name, this is purely a model-level
      // disambiguation so the bare name can be the discriminator without a getter-name collision.
      componentBuilder
          .annotation(Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonUnwrapped")))
          .annotation(jsonProperty(componentName + "$"));
    }
    var component = componentBuilder.annotation(Annotation.NULLABLE).build();

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
      String valName = Oneof.valueRecordName(member);
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
              .component(oneofValueComponent(member, valType, jackson))
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

  /** {@code @JsonInclude(JsonInclude.Include.NON_NULL)}. */
  private static Annotation jsonIncludeNonNull() {
    TypeRef include =
        new TypeRef.Named(
            "com.fasterxml.jackson.annotation", List.of("JsonInclude"), "Include", List.of());
    return Annotation.of(
        TypeRef.of("com.fasterxml.jackson.annotation.JsonInclude"),
        Arg.of(null, new ArgValue.Ref(include, ".NON_NULL")));
  }

  /**
   * A oneof wrapper record's single {@code value} component. On a jackson file it carries the
   * member's JSON name (so the unwrapped wrapper renders the member as a top-level property) and,
   * when the member's type needs one, the same temporal / runtime-value (de)serializer pair a
   * regular field of that type carries.
   */
  private static Component oneofValueComponent(Field member, TypeRef valType, boolean jackson) {
    var comp = Component.type(valType, "value");
    if (jackson) {
      comp.annotation(jsonProperty(member.jsonName()));
      temporalAnnotation(comp, member, true);
      runtimeValueAnnotation(comp, member, true);
    }
    return comp.build();
  }

  /** {@code @JsonProperty("<name>")}. */
  private static Annotation jsonProperty(String name) {
    return Annotation.of(
        TypeRef.of("com.fasterxml.jackson.annotation.JsonProperty"), Arg.of(null, name));
  }

  /** A oneof member's wrapper record, nested in the oneof's sealed interface. */
  private static TypeRef.Named oneofValueRef(TypeRef.Named recordRef, Oneof oneof, Field member) {
    return new TypeRef.Named(
        recordRef.pkg(),
        List.of(recordRef.shortName(), oneof.ifaceName()),
        Oneof.valueRecordName(member),
        List.of());
  }

  /** The oneof's discriminator: its JSON property name and the record method that renders it. */
  private static String discriminatorProperty(Oneof oneof) {
    String override = oneof.jsonOneof();
    return override != null ? override : oneof.componentName();
  }

  /**
   * The record-level half of a jackson file's flat oneof rendering (U7), emitted when the message
   * has at least one oneof:
   *
   * <ul>
   *   <li>per oneof, a discriminator method ({@code choiceType()}) rendered as the JSON property
   *       {@code "choice"} (or the {@code recordbuf.json_oneof} override): the set member's JSON
   *       name, omitted when the oneof is unset;
   *   <li>a {@code @JsonCreator} factory taking every field, every oneof member, every
   *       discriminator, and the retained unknown properties; it selects each oneof's member with
   *       {@code JsonOneof.select} (malformed combinations fail) and delegates to the canonical
   *       constructor. Regular fields keep their component annotations (Jackson merges a property's
   *       annotations across the component and the factory parameter); member parameters repeat
   *       their wrapper's (de)serializer pair, since no component carries it.
   * </ul>
   *
   * <p>A discriminator whose method or JSON name would collide with a field or a member fails
   * generation.
   */
  private static List<Method> oneofJsonSurface(Message m) {
    TypeRef.Named recordRef = m.type();
    Set<String> javaNames = new HashSet<>();
    Set<String> jsonNames = new HashSet<>();
    for (Field f : m.fields()) {
      javaNames.add(f.javaName());
      jsonNames.add(f.jsonName());
    }
    for (Oneof oneof : m.oneofs()) {
      for (Field member : oneof.members()) {
        javaNames.add(member.javaName());
        jsonNames.add(member.jsonName());
      }
    }

    List<Method> methods = new ArrayList<>();
    Method.Builder factory =
        Method.named("fromJson")
            .static_()
            .javadoc(
                Javadoc.builder()
                    .text(
                        GeneratedDocs.MARKER
                            + " The JSON creator: binds each oneof from its flat member property"
                            + " and discriminator, then delegates to the canonical constructor.\n")
                    .build())
            .annotation(Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonCreator")))
            .returnType(recordRef);
    List<String> ctorArgs = new ArrayList<>();
    for (Field f : m.fields()) {
      Param p =
          isComponentNullable(f)
              ? Param.of(f.type(), f.javaName(), jsonProperty(f.jsonName()), Annotation.NULLABLE)
              : Param.of(f.type(), f.javaName(), jsonProperty(f.jsonName()));
      factory.param(p);
      ctorArgs.add(f.javaName());
    }

    List<Element> body = new ArrayList<>();
    for (Oneof oneof : m.oneofs()) {
      String property = discriminatorProperty(oneof);
      // Only the JSON property name is part of the JSON contract, so only a collision there fails
      // (and recordbuf.json_oneof renames it). The Java method is an implementation detail: when
      // <oneof>Type is taken (e.g. by a field's accessor) it steps aside to <oneof>JsonType, then
      // <oneof>JsonType2, ...
      if (jsonNames.contains(property)) {
        throw new IllegalStateException(
            "oneof \""
                + oneof.name()
                + "\" in "
                + m.name()
                + ": its JSON discriminator property \""
                + property
                + "\" collides with a field's JSON name; rename the field, or set"
                + " recordbuf.json_oneof on the oneof");
      }
      String method = oneof.componentName() + "Type";
      for (int attempt = 1; javaNames.contains(method); attempt++) {
        method = oneof.componentName() + "JsonType" + (attempt == 1 ? "" : attempt);
      }
      javaNames.add(method);
      jsonNames.add(property);

      // The discriminator method: the set member's JSON name, or null (omitted) when unset.
      Method.Builder disc =
          Method.named(method)
              .public_()
              .javadoc(
                  Javadoc.builder()
                      .text(
                          GeneratedDocs.MARKER
                              + " The JSON discriminator of the "
                              + oneof.name()
                              + " oneof: the set member's JSON name, or null when unset.\n")
                      .build())
              .annotation(jsonProperty(property))
              .annotation(Annotation.NULLABLE)
              .returnType(TypeRef.STRING);
      for (Field member : oneof.members()) {
        disc.body(
            If.condition(
                    "@0 instanceof @1",
                    oneof.componentName(),
                    oneofValueRef(recordRef, oneof, member))
                .body(Element.ret("\"" + member.jsonName() + "\""))
                .build());
      }
      methods.add(disc.body(Element.ret("null")).build());

      // The factory's member + discriminator parameters, and the oneof's selection.
      List<String> names = new ArrayList<>();
      List<String> values = new ArrayList<>();
      StringBuilder select = new StringBuilder();
      // Template args: @0 the oneof's interface, @1 JsonOneof, @2 List, @3 Objects, then one
      // wrapper record per member from @4 on.
      List<Object> args =
          new ArrayList<>(
              List.of(oneof.iface(), TypeRefs.JSON_ONEOF, TypeRefs.LIST, TypeRefs.OBJECTS));
      for (Field member : oneof.members()) {
        List<Annotation> anns = new ArrayList<>();
        anns.add(jsonProperty(member.jsonName()));
        anns.addAll(temporalAnnotations(member, true));
        anns.addAll(runtimeValueAnnotations(member, true));
        anns.add(Annotation.NULLABLE);
        factory.param(
            Param.of(
                TypeRefs.boxed(member.type()), member.javaName(), anns.toArray(new Annotation[0])));
        names.add("\"" + member.jsonName() + "\"");
        values.add(member.javaName());
      }
      factory.param(Param.of(TypeRef.STRING, method, jsonProperty(property), Annotation.NULLABLE));

      select
          .append("@0 ")
          .append(oneof.componentName())
          .append(" = switch (@1.select(\"")
          .append(oneof.name())
          .append("\", ")
          .append(method)
          .append(", @2.of(")
          .append(String.join(", ", names))
          .append("), ")
          .append(String.join(", ", values))
          .append(")) {");
      int i = 0;
      for (Field member : oneof.members()) {
        args.add(oneofValueRef(recordRef, oneof, member));
        int valueArg = args.size() - 1;
        select
            .append(" case ")
            .append(i++)
            .append(" -> new @")
            .append(valueArg)
            .append("(@3.requireNonNull(")
            .append(member.javaName())
            .append("));");
      }
      select.append(" default -> null; }");
      body.add(Element.expr(RawExpr.of(select.toString(), args.toArray())));
      ctorArgs.add(oneof.componentName());
    }
    factory.param(
        Param.of(
            TypeRefs.JSON_NODE_MAP,
            "jsonExtras",
            Annotation.of(TypeRef.of("com.fasterxml.jackson.annotation.JsonAnySetter")),
            Annotation.NULLABLE));
    ctorArgs.add("jsonExtras");
    ctorArgs.add("@0.empty()");
    body.add(
        Element.ret(
            RawExpr.of(
                "new @1(" + String.join(", ", ctorArgs) + ")",
                TypeRefs.UNKNOWN_FIELDS,
                recordRef)));
    methods.add(factory.body(body).build());
    return methods;
  }

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
  private static BuilderEmission emitBuilder(Message m, boolean jackson) {
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
    builderClz.field(
        Component.type(TypeRefs.UNKNOWN_FIELDS, "unknownFields")
            .initializer(RawExpr.of("@0.empty()", TypeRefs.UNKNOWN_FIELDS))
            .private_()
            .build());
    if (jackson) {
      // Storage for the jsonExtras component: a fresh mutable map.
      builderClz.field(
          Component.type(TypeRefs.JSON_NODE_MAP, "jsonExtras")
              .annotation(Annotation.NULLABLE)
              .initializer(RawExpr.of("new @0<>()", TypeRefs.LINKED_HASH_MAP))
              .private_()
              .build());
    }
    // The no-arg constructor (Java does not generate an implicit one when a parametered ctor
    // exists).
    builderClz.constructor(Constructor.builder().private_().body().build());

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
    if (jackson) {
      copyCtor.body(
          Element.expr(
              RawExpr.of(
                  "this.jsonExtras = new @0<>(x.jsonExtras == null ? new @0<>() : x.jsonExtras);",
                  TypeRefs.LINKED_HASH_MAP)));
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
    if (jackson) {
      // The retained unknown JSON properties: the setter defensively copies so a caller passing
      // an unmodifiable map does not poison the record.
      builderClz.method(
          Method.named("jsonExtras")
              .public_()
              .returnType(builderType)
              .param(Param.of(TypeRefs.JSON_NODE_MAP, "jsonExtras", Annotation.NULLABLE))
              .body(
                  Element.expr(
                      RawExpr.of(
                          "this.jsonExtras = new @0<>(jsonExtras == null ? new @0<>() : jsonExtras);",
                          TypeRefs.LINKED_HASH_MAP)),
                  Element.ret("this"))
              .build());
    }

    // build(): constructs the record.
    List<String> ctorArgs = new ArrayList<>();
    for (Field f : m.fields()) {
      ctorArgs.add(f.javaName());
    }

    for (var oneof : m.oneofs()) {
      ctorArgs.add(oneof.componentName());
    }
    if (jackson) ctorArgs.add("jsonExtras");
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
   * The inverse of the non-null branches of {@link #defaultFor(Field)}: true when that method would
   * emit {@code null} (a presence-tracked singular message/enum, an explicit {@code optional}, or a
   * WKT whose zero is absent). The compact constructor skips null-normalizing exactly these fields
   * — there null means "absent" and must survive construction.
   */
  private static boolean hasNullDefault(Field f) {
    var wkt = WellKnownTypes.resolve(f.typeUrl());
    if (wkt != null) {
      // A repeated WKT defaults to a fresh list; a singular WKT defaults to its zero or null.
      if (f.label() == FieldLabel.REPEATED) return false;
      return wkt.zeroDefault() == null;
    }
    if (f.isNullable()) return true;
    return (f.kind() == FieldKind.MESSAGE || f.kind() == FieldKind.ENUM)
        && f.label() != FieldLabel.REPEATED;
  }

  /**
   * M4-C2d-7: emit a map entry record for a MAP field. The entry has two components: {@code key}
   * and {@code value} (the key and value types of the field's {@code Map<K, V>}). The entry's body
   * contains a nested {@code <Field>EntryCodec} class via {@link
   * CodecSourceMapping#emitEntryCodec}.
   */
  private static RecordSpec mapEntryRecord(TypeRef.Named recordRef, Field f) {
    String javaName = f.javaName();
    String entryName =
        javaName.isEmpty() ? "" : Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
    entryName += "Entry";

    TypeRef keyTypeRef = f.mapKeyType();
    TypeRef valueTypeRef = f.mapValueType();

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
