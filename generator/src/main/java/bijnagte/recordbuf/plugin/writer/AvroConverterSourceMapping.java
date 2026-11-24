package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.ClassSpec;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.LocalVar;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import bijnagte.recordbuf.plugin.types.WellKnownTypes.Entry;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a top-level message's Avro {@code <X>AvroConverter} (record &harr; Avro-specific-record
 * conversion) through the source-AST, returning rendered Java source.
 *
 * <p>The converter handles both directions of a round-trip:
 *
 * <ul>
 *   <li>{@code toRecord(<X>Avro s)}: drives the recordbuf record's fluent builder, setting each
 *       component; Avro getters route scalars/bytes directly and message/WKT fields through their
 *       sibling converters or {@code WktAvroBridge}.
 *   <li>{@code fromRecord(<X> r)}: creates a fresh Avro specific record, populating each field via
 *       its setter; record accessors route scalars/bytes directly and message/WKT fields through
 *       their sibling converters or {@code WktAvroBridge}.
 * </ul>
 *
 * <p>Every reachable field shape is bridged: scalars (incl. the bit-exact {@code uint64} &harr;
 * {@code long}), strings, bytes, enums (string), WKTs, messages (referencing sibling converters
 * through the registry), repeated fields, maps (string-key via Avro's built-in map, non-string-key
 * via a pair-entry record), oneofs (message members direct, scalar/enum members via the shared
 * {@code RecordbufOneofValue} wrapper), and the trailing unknown-field carrier. A shape the bridge
 * cannot convert fails loud.
 */
public final class AvroConverterSourceMapping {

  /**
   * The WKTs whose Avro value is the record's value as it is (a number, a Timestamp's Instant, a
   * boolean), so a repeated field of them needs no per-element conversion.
   */
  private static final Set<String> SAME_VALUE_WKTS =
      Set.of(
          "google.protobuf.Timestamp",
          "google.protobuf.Int32Value",
          "google.protobuf.UInt32Value",
          "google.protobuf.Int64Value",
          "google.protobuf.UInt64Value",
          "google.protobuf.FloatValue",
          "google.protobuf.DoubleValue",
          "google.protobuf.BoolValue");

  private AvroConverterSourceMapping() {}

  /**
   * Generate the Avro-interop converter class for {@code message}, returning the rendered Java
   * source string.
   *
   * @param protoRoot the proto message name (the record/converter base)
   * @param protoFile the {@code recordbuf.proto} filename, for the source header
   * @param message the top-level message to generate a converter for
   * @param avroName the Avro specific record name for this message
   * @param specificRecordPkg the package of the generated Avro specific record
   * @param recordPkg the package of the recordbuf record (and this converter class)
   * @param registry the reachable-type registry, for cross-file references
   * @param formatter the source formatter
   * @return the generated source string
   */
  public static String generate(
      String protoRoot,
      String protoFile,
      Message message,
      String avroName,
      String specificRecordPkg,
      String recordPkg,
      AvroRegistry registry,
      Formatter formatter) {
    TypeRef.Named recordClass = message.type();
    TypeRef.Named avroSpecific = TypeRef.named(specificRecordPkg, avroName);
    String className = avroName + "Converter";

    TypeCollector ci = new TypeCollector(recordPkg);
    boolean samePkg = recordPkg.equals(specificRecordPkg);
    Set<TypeRef> externalRefs = samePkg ? Set.of() : Set.of(avroSpecific);

    Constructor privateCtor = Constructor.builder().private_().body().build();
    Method toRecord =
        buildToRecord(message, recordClass, avroSpecific, registry, specificRecordPkg);
    Method fromRecord =
        buildFromRecord(message, recordClass, avroSpecific, registry, specificRecordPkg);

    ClassSpec converterClass =
        ClassSpec.named(TypeRef.named(recordPkg, className))
            .public_()
            .final_()
            .javadoc(GeneratedDocs.type(className, "Avro converter for the %s type."))
            .constructor(privateCtor)
            .method(toRecord, fromRecord)
            .build();

    JavaFile javaFile = new JavaFile(recordPkg, Javadoc.empty(), converterClass, ci, externalRefs);
    String rendered = SourceRenderer.render(javaFile, formatter);

    String comment =
        """
        /*
         * Generated by recordbuf -- DO NOT EDIT.
         * Converter for %s record \u2194 Avro specific record.
         *
         */
        """
            .formatted(protoFile);
    return comment + rendered;
  }

  // --- toRecord body builder (Avro specific record -> recordbuf record) -------------------

  private static Method buildToRecord(
      Message message,
      TypeRef.Named recordClass,
      TypeRef avroSpecific,
      AvroRegistry registry,
      String specificRecordPkg) {
    var method =
        Method.named("toRecord")
            .public_()
            .static_()
            .returnType(recordClass)
            .param(avroSpecific, "s")
            .body(
                LocalVar.builder(recordClass.sub("Builder"), "b")
                    .assigned(RawExpr.of("@0.builder()", recordClass)));
    for (Field f : message.fields()) {
      method.body(toRecordField(f, "s", "b", registry, specificRecordPkg));
    }
    for (Oneof oneof : message.oneofs()) {
      method.body(oneofToRecord(oneof, "s", "b", registry, specificRecordPkg));
    }
    // TODO(avro): transite the record's unknown-field carrier (1:1 tuple array); the checkpoint
    // defaults it to empty.
    return method
        .body(Element.expr("@0.unknownFields(@1.empty())", "b", TypeRefs.UNKNOWN_FIELDS))
        .body(Element.ret("b.build()"))
        .build();
  }

  private static Element toRecordField(
      Field f, String param, String builder, AvroRegistry registry, String specificRecordPkg) {
    String getter = param + ".get" + cap(f.javaName());
    String var = builder + "." + f.javaName();

    // The U9 JSON envelope: a node-typed dynamic WKT on a jackson-enabled file round-trips as the
    // shared envelope record ({format, contentType, payload}); absent (the union's null branch)
    // stays a null component.
    if (registry != null && registry.isEnvelopeField(f)) {
      return envelopeArm(var, getter + "()", f, true, registry);
    }

    String wkt = AvroSchemaGenerator.typeUrlIfWkt(f);
    if (wkt != null) {
      return wktToRecord(wkt, var, getter, f.isRepeated());
    }
    if (f.isMap()) {
      return mapToRecord(f, var, getter, specificRecordPkg);
    }
    if (f.kind() == FieldKind.MESSAGE) {
      if (f.isRepeated()) {
        return repeatedMessageToRecord(f, var, getter, registry);
      }
      return messageToRecord(f, var, getter, registry);
    }
    if (f.kind() == FieldKind.ENUM) {
      return enumToRecord(f, var, getter);
    }
    // Repeated length-delimited scalars convert per element: Avro's array of string is a
    // List<CharSequence>, its array of bytes a List<ByteBuffer>.
    if (f.kind() == FieldKind.SCALAR && f.isRepeated() && "string".equals(f.typeUrl())) {
      return Element.expr("@0(@1().stream().map(CharSequence::toString).toList())", var, getter);
    }
    if (f.kind() == FieldKind.SCALAR && f.isRepeated() && "bytes".equals(f.typeUrl())) {
      return Element.expr(
          "@0(@1().stream().map(e -> @2.of(@3.copyOfRange(e.array(), e.arrayOffset()"
              + " + e.position(), e.arrayOffset() + e.limit()))).toList())",
          var,
          getter,
          TypeRefs.BYTES,
          TypeRefs.ARRAYS);
    }
    if (f.kind() == FieldKind.SCALAR && "bytes".equals(f.typeUrl())) {
      return Element.expr(
          "@0(@1() != null ? @2.of(@3.copyOf(@1().array(), @1().remaining())) : @2.empty())",
          var,
          getter,
          TypeRefs.BYTES,
          TypeRefs.ARRAYS);
    }
    if (f.kind() == FieldKind.SCALAR && "string".equals(f.typeUrl())) {
      return Element.expr("@0(@1().toString())", var, getter);
    }
    // A repeated scalar's Avro elements are never null, so a plain List.copyOf holds them: the
    // record gets a plain list, not Avro's GenericData.Array.
    if (f.kind() == FieldKind.SCALAR && f.isRepeated()) {
      return Element.expr("@0(@1.copyOf(@2()))", var, TypeRefs.LIST, getter);
    }
    if (f.kind() == FieldKind.SCALAR) {
      return Element.expr("@0(@1())", var, getter);
    }
    throw new AvroUnsupportedException(
        f.name(), "cannot map Avro field to a record component ('" + f.typeUrl() + "')");
  }

  /**
   * An enum component read back from its Avro name (a string, never null): the record constant with
   * that name, per element for a repeated field.
   */
  private static Element enumToRecord(Field f, String var, String getter) {
    TypeRef enumType = enumType(f);
    if (f.isRepeated()) {
      return Element.expr(
          "@0(@1().stream().map(v -> @2).toList())", var, getter, enumFromName(enumType, "v"));
    }
    return Element.expr("@0(@1)", var, enumFromName(enumType, getter + "()"));
  }

  /**
   * A WKT field's record component. Avro holds a repeated field's elements as nullable unions, so a
   * repeated field converts each element with the conversion a singular field applies to its value.
   */
  private static Element wktToRecord(String wkt, String var, String getter, boolean repeated) {
    if (!repeated) {
      return Element.expr("@0(@1)", var, wktToRecordValue(wkt, getter + "()"));
    }
    if (SAME_VALUE_WKTS.contains(wkt)) {
      // The list is copied into a plain List: Avro's array is a GenericData.Array, whose equals
      // rejects the record's Instant elements. Stream.toList() keeps a null wrapper element.
      return Element.expr("@0(@1().stream().toList())", var, getter);
    }
    return Element.expr(
        "@0(@1().stream().map(v -> @2).toList())", var, getter, repeatedElementToRecord(wkt));
  }

  /**
   * The record value of one repeated WKT element {@code v}. A null FieldMask element reads back as
   * the empty mask: a list element gets no constructor normalization, so the singular arm's
   * null-to-empty step never reaches it, and a null record element would later fail in the
   * encoders.
   */
  private static RawExpr repeatedElementToRecord(String wkt) {
    if (wkt.equals("google.protobuf.FieldMask")) {
      return RawExpr.of(
          "v == null ? @0.empty() : @0.of(@1.fieldMaskFromAvro(v.toString()))",
          TypeRefs.FIELD_MASK,
          TypeRefs.WKT_AVRO_BRIDGE);
    }
    return wktToRecordValue(wkt, "v");
  }

  /**
   * The record value of one Avro WKT value {@code value} (a null Avro value stays null). A {@code
   * SAME_VALUE_WKTS} value passes through unchanged.
   */
  private static RawExpr wktToRecordValue(String wkt, String value) {
    if (SAME_VALUE_WKTS.contains(wkt)) {
      return RawExpr.of(value);
    }
    return switch (wkt) {
      // StringValue: a CharSequence to a String.
      case "google.protobuf.StringValue" -> RawExpr.of("@0 == null ? null : @0.toString()", value);
      // Duration: avro.wkt.Duration {seconds: long, nanos: int} to java.time.Duration.
      case "google.protobuf.Duration" ->
          RawExpr.of(
              "@0 == null ? null : @1.ofSeconds(@0.getSeconds(), @0.getNanos() % 1_000_000_000L)",
              value, TypeRefs.DURATION);
      // FieldMask: [null, string] (a comma-joined string, via the bridge) to FieldMask.
      case "google.protobuf.FieldMask" ->
          RawExpr.of(
              "@0 == null ? null : @1.of(@2.fieldMaskFromAvro(@0.toString()))",
              value,
              TypeRefs.FIELD_MASK,
              TypeRefs.WKT_AVRO_BRIDGE);
      // BytesValue: [null, bytes] (a ByteBuffer) to Bytes, read by position and remaining.
      case "google.protobuf.BytesValue" ->
          RawExpr.of(
              "@0 == null ? null : @1.of(@2.bytes(@0))",
              value,
              TypeRefs.BYTES,
              TypeRefs.WKT_AVRO_BRIDGE);
      // Empty: the empty avro record (no data) to the runtime Empty singleton.
      case "google.protobuf.Empty" ->
          RawExpr.of("@0 == null ? null : @1.instance()", value, TypeRefs.EMPTY);
      // Any other (NullValue): the resolved value is used as-is.
      default -> RawExpr.of(value);
    };
  }

  private static Element messageToRecord(
      Field f, String var, String getter, AvroRegistry registry) {
    String fqn = f.typeUrl();
    Message ref = registry.messageOf(fqn);
    String refName = registry.nameOf(fqn);
    String refNs = registry.namespaceOf(fqn);
    String refRecPkg = registry.recordPackageOf(fqn);
    if (ref == null || refName == null || refNs == null || refRecPkg == null) {
      throw new AvroUnsupportedException(fqn, "unresolvable message reference");
    }
    TypeRef refConverter =
        TypeRef.named(refRecPkg, AvroSchemaGenerator.specificRecordName(ref.name()) + "Converter");
    if (registry.isCycleClosing(f)) {
      // A cycle-closing reference is a null-first union in Avro: an absent value is the null
      // branch.
      return Element.expr("@0(@1() == null ? null : @2.toRecord(@1()))", var, getter, refConverter);
    }
    return Element.expr("@0(@1.toRecord(@2()))", var, refConverter, getter);
  }

  private static Element repeatedMessageToRecord(
      Field f, String var, String getter, AvroRegistry registry) {
    String fqn = f.typeUrl();
    Message ref = registry.messageOf(fqn);
    String refName = registry.nameOf(fqn);
    String refNs = registry.namespaceOf(fqn);
    String refRecPkg = registry.recordPackageOf(fqn);
    if (ref == null || refName == null || refNs == null || refRecPkg == null) {
      throw new AvroUnsupportedException(fqn, "unresolvable repeated message reference");
    }
    TypeRef refConverter =
        TypeRef.named(refRecPkg, AvroSchemaGenerator.specificRecordName(ref.name()) + "Converter");
    return Element.expr("@0(@2().stream().map(@1::toRecord).toList())", var, refConverter, getter);
  }

  private static Element mapToRecord(Field f, String var, String getter, String specificRecordPkg) {
    // A string-key proto map is an Avro map (keys are CharSequence); a non-string key is a list of
    // pair-entry records. Rebuild the record's Map so its key/value Java types line up; scalar
    // values pass through unchanged (a message/bytes value would need a per-value bridge).
    // The Avro map is never null here: it is either set by fromRecord or read back from a decoded
    // datum (an Avro non-union field is always present).
    // The Collectors and the entry-record are TypeRefs so the collector imports them and uses their
    // short names (EP's UnnecessarilyFullyQualified forbids inline FQN literals).
    TypeRef collectors = TypeRef.named("java.util.stream", "Collectors");
    String g = getter + "()";
    // A string proto value is a CharSequence in Avro and a String in the record: materialize it.
    String valueMapper =
        "string".equals(f.mapValue()) ? "e -> e.getValue().toString()" : "e -> e.getValue()";
    if ("string".equals(f.mapKey())) {
      String stream =
          g
              + ".entrySet().stream().collect(@0.toMap(e -> e.getKey().toString(), "
              + valueMapper
              + "))";
      return Element.expr(var + "(" + stream + ")", collectors);
    }
    TypeRef entry = TypeRef.named(specificRecordPkg, AvroRegistry.mapEntryRecordName(f));
    String stream = g + ".stream().collect(@0.toMap(@1::getKey, " + valueMapper + "))";
    return Element.expr(var + "(" + stream + ")", collectors, entry);
  }

  private static Element mapFromRecord(
      Field f, String setter, String accessor, String specificRecordPkg) {
    // Collectors and the entry-record are TypeRefs so the collector imports them and uses their
    // short names (EP's UnnecessarilyFullyQualified forbids inline FQN literals).
    TypeRef collectors = TypeRef.named("java.util.stream", "Collectors");
    String a = accessor;
    if ("string".equals(f.mapKey())) {
      String stream =
          a + ".entrySet().stream().collect(@0.toMap(e -> e.getKey(), " + "e -> e.getValue()))";
      return Element.expr(setter + "(" + stream + ")", collectors);
    }
    TypeRef entry = TypeRef.named(specificRecordPkg, AvroRegistry.mapEntryRecordName(f));
    String stream =
        a
            + ".entrySet().stream().map(e -> new @1(e.getKey(), e.getValue()))"
            + ".collect(@0.toList())";
    return Element.expr(setter + "(" + stream + ")", collectors, entry);
  }

  private static Element oneofToRecord(
      Oneof oneof, String param, String builder, AvroRegistry registry, String specificRecordPkg) {
    String getter = param + ".get" + cap(oneof.name()) + "()";
    String var = builder + "." + oneof.name();
    If.Builder b = If.condition(getter + " == null").body(Element.expr(var + "(null)"));
    // Message members each ride their own specific-record subtype in the Avro union; the
    // scalar/enum members ride one shared {tag, value} wrapper, dispatched by the member's tag.
    boolean hasScalar =
        oneof.members().stream().anyMatch(m -> AvroSchemaGenerator.ridesValueWrapper(m, registry));
    for (Field member : oneof.members()) {
      if (AvroSchemaGenerator.ridesValueWrapper(member, registry)) {
        continue;
      }
      String fqn = member.typeUrl();
      String refName = registry.nameOf(fqn);
      String refNs = registry.namespaceOf(fqn);
      String refRecPkg = registry.recordPackageOf(fqn);
      Message ref = registry.messageOf(fqn);
      if (ref == null || refName == null || refNs == null || refRecPkg == null) {
        throw new AvroUnsupportedException(fqn, "unresolvable oneof message member");
      }
      TypeRef memberAvro = TypeRef.named(refNs, refName);
      TypeRef refConverter =
          TypeRef.named(
              refRecPkg, AvroSchemaGenerator.specificRecordName(ref.name()) + "Converter");
      b.elseIf(
          RawExpr.of(getter + " instanceof @0 m", memberAvro),
          Element.expr(
              var + "(new @0(@1.toRecord(m)))", memberValueRef(oneof, member), refConverter));
    }
    if (hasScalar) {
      boolean onlyWrapper =
          oneof.members().stream()
              .allMatch(m -> AvroSchemaGenerator.ridesValueWrapper(m, registry));
      if (onlyWrapper) {
        // Every member rides the wrapper, so the Avro field IS the wrapper type: no instanceof
        // (Error Prone's BadInstanceof), just a typed local.
        b.else_(
            Element.local(oneofValueRecord(specificRecordPkg), "env", getter),
            oneofValueToRecord(oneof, var, "env", registry));
      } else {
        b.elseIf(
            RawExpr.of(getter + " instanceof @0 env", oneofValueRecord(specificRecordPkg)),
            oneofValueToRecord(oneof, var, "env", registry));
      }
    }
    return b.build();
  }

  private static Element oneofValueToRecord(
      Oneof oneof, String var, String local, AvroRegistry registry) {
    List<Field> scalarMembers =
        oneof.members().stream()
            .filter(m -> AvroSchemaGenerator.ridesValueWrapper(m, registry))
            .toList();
    if (scalarMembers.isEmpty()) {
      return Element.of(""); // no wrapper-riding members; the envelope branch is unused
    }
    Field first = scalarMembers.get(0);
    If.Builder b =
        If.condition(local + ".getTag() == " + first.number())
            .body(memberValueToRecordBody(oneof, first, var, local, registry));
    for (int i = 1; i < scalarMembers.size(); i++) {
      Field member = scalarMembers.get(i);
      b.elseIf(
          local + ".getTag() == " + member.number(),
          memberValueToRecordBody(oneof, member, var, local, registry));
    }
    return b.build();
  }

  private static Element memberValueToRecordBody(
      Oneof oneof, Field member, String var, String local, AvroRegistry registry) {
    return Element.expr(
        var + "(new @0(@1))",
        memberValueRef(oneof, member),
        fromWrapperValue(member, local + ".getValue()", registry));
  }

  /**
   * A wrapper-riding oneof member's value read back from the shared wrapper's {@code value} union
   * (an {@code Object}), converted to the record's member type: the inverse of {@link
   * #toWrapperValue}.
   */
  private static RawExpr fromWrapperValue(Field member, String value, AvroRegistry registry) {
    if (member.kind() == FieldKind.ENUM) {
      // The wrapper stores the enum number (an int); recover the enum via forNumber.
      return RawExpr.of("@0.forNumber((Integer) " + value + ")", enumType(member));
    }
    if (registry.isEnvelopeField(member)) {
      TypeRef envelope =
          TypeRef.named(registry.documentNamespace(), registry.dynamicWktEntryName());
      TypeRef nodeType =
          Objects.requireNonNull(
              Objects.requireNonNull(WellKnownTypes.resolve(member.typeUrl())).nodeType());
      String parse = "@0.node(@1.of(((@2) " + value + ").getPayload().toString()))";
      return nodeType.equals(TypeRefs.JSON_NODE)
          ? RawExpr.of(parse, TypeRefs.DYNAMIC_WKT, TypeRefs.JSON_PAYLOAD, envelope)
          : RawExpr.of(
              "(@3) " + parse, TypeRefs.DYNAMIC_WKT, TypeRefs.JSON_PAYLOAD, envelope, nodeType);
    }
    String wkt = AvroSchemaGenerator.typeUrlIfWkt(member);
    if (wkt == null) {
      return switch (member.typeUrl()) {
        // Avro decodes a string as a CharSequence (Utf8), not a String; materialize via toString.
        case "string" -> RawExpr.of(value + ".toString()");
        case "bytes" -> bytesFromAvro(value);
        // Numeric scalars: the wrapper value is the boxed primitive; downcast to the record's type.
        default -> RawExpr.of("(@0) " + value, scalarWrapperType(member));
      };
    }
    return switch (wkt) {
      case "google.protobuf.Timestamp" ->
          RawExpr.of("@0.instantFromMicros((Long) " + value + ")", TypeRefs.WKT_AVRO_BRIDGE);
      case "google.protobuf.Duration" ->
          RawExpr.of(
              "@0.ofSeconds(((@1) " + value + ").getSeconds(), ((@1) " + value + ").getNanos())",
              TypeRefs.DURATION,
              avroWktRecord("Duration"));
      case "google.protobuf.Empty" -> RawExpr.of("@0.instance()", TypeRefs.EMPTY);
      // An empty mask is stored as the union's null branch.
      case "google.protobuf.FieldMask" ->
          RawExpr.of(
              "@0.of(@1.fieldMaskFromAvro("
                  + value
                  + " == null ? null : "
                  + value
                  + ".toString()))",
              TypeRefs.FIELD_MASK,
              TypeRefs.WKT_AVRO_BRIDGE);
      case "google.protobuf.StringValue" -> RawExpr.of(value + ".toString()");
      case "google.protobuf.BytesValue" -> bytesFromAvro(value);
      case "google.protobuf.Int32Value", "google.protobuf.UInt32Value" ->
          RawExpr.of("(Integer) " + value);
      case "google.protobuf.Int64Value", "google.protobuf.UInt64Value" ->
          RawExpr.of("(Long) " + value);
      case "google.protobuf.FloatValue" -> RawExpr.of("(Float) " + value);
      case "google.protobuf.DoubleValue" -> RawExpr.of("(Double) " + value);
      case "google.protobuf.BoolValue" -> RawExpr.of("(Boolean) " + value);
      default -> throw new AvroUnsupportedException(member.name(), "no oneof mapping for " + wkt);
    };
  }

  /**
   * A wrapper-riding oneof member's record value as the shared wrapper's {@code value}: an enum as
   * its number, bytes as a ByteBuffer, a well-known type as its Avro form (a Timestamp as epoch
   * micros -- the resolution a regular Timestamp field has in Avro -- a Duration / Empty as their
   * records, a FieldMask as its path string), a dynamic WKT as the JSON envelope.
   */
  private static RawExpr toWrapperValue(Field member, String value, AvroRegistry registry) {
    if (member.kind() == FieldKind.ENUM) {
      return RawExpr.of(value + ".getNumber()");
    }
    if (registry.isEnvelopeField(member)) {
      return RawExpr.of(
          "new @0(\"json\", @1.contentType(\""
              + AvroSchemaGenerator.normalizeRef(member.typeUrl())
              + "\", "
              + value
              + "), @1.payload("
              + value
              + ").jsonText())",
          TypeRef.named(registry.documentNamespace(), registry.dynamicWktEntryName()),
          TypeRefs.DYNAMIC_WKT);
    }
    String wkt = AvroSchemaGenerator.typeUrlIfWkt(member);
    if (wkt == null) {
      return "bytes".equals(member.typeUrl())
          ? RawExpr.of("@0.wrap(" + value + ".toByteArray())", TypeRefs.BYTE_BUFFER)
          : RawExpr.of(value);
    }
    return switch (wkt) {
      case "google.protobuf.Timestamp" ->
          RawExpr.of("@0.instantToMicros(" + value + ")", TypeRefs.WKT_AVRO_BRIDGE);
      // The floor pair (non-negative nanos), as for a regular Duration field.
      case "google.protobuf.Duration" ->
          RawExpr.of(
              "new @0(" + value + ".toSeconds(), " + value + ".toNanosPart())",
              avroWktRecord("Duration"));
      case "google.protobuf.Empty" -> RawExpr.of("new @0()", avroWktRecord("Empty"));
      case "google.protobuf.FieldMask" ->
          RawExpr.of("@0.fieldMaskToAvro(" + value + ".paths())", TypeRefs.WKT_AVRO_BRIDGE);
      case "google.protobuf.BytesValue" ->
          RawExpr.of("@0.wrap(" + value + ".toByteArray())", TypeRefs.BYTE_BUFFER);
      default -> RawExpr.of(value);
    };
  }

  /** A {@code bytes} union value (a ByteBuffer) as the record's {@code Bytes}. */
  private static RawExpr bytesFromAvro(String value) {
    return RawExpr.of(
        "@0.of(@1.bytes((@2) " + value + "))",
        TypeRefs.BYTES,
        TypeRefs.WKT_AVRO_BRIDGE,
        TypeRefs.BYTE_BUFFER);
  }

  /** A WKT named record the Avro bridge generates (its {@code Duration} / {@code Empty}). */
  private static TypeRef avroWktRecord(String name) {
    return TypeRef.named(AvroSchemaGenerator.WKT_NAMED_NAMESPACE, name);
  }

  private static TypeRef memberValueRef(Oneof oneof, Field member) {
    // Matches the record generator's naming: the sealed interface plus the capitalized member
    // name and "Value" (e.g. Compat.Choice.NameValue) -- never the oneof name prefixed.
    return oneof.iface().sub(cap(member.name()) + "Value");
  }

  private static TypeRef scalarWrapperType(Field f) {
    return switch (f.typeUrl()) {
      case "int32", "uint32", "sint32", "fixed32", "sfixed32" ->
          TypeRef.named("java.lang", "Integer");
      case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> TypeRef.named("java.lang", "Long");
      case "float" -> TypeRef.named("java.lang", "Float");
      case "double" -> TypeRef.named("java.lang", "Double");
      case "bool" -> TypeRef.named("java.lang", "Boolean");
      default -> TypeRef.OBJECT;
    };
  }

  // --- fromRecord body builder (recordbuf record -> Avro specific record) -----------------

  private static Method buildFromRecord(
      Message message,
      TypeRef.Named recordClass,
      TypeRef avroSpecific,
      AvroRegistry registry,
      String specificRecordPkg) {
    var method =
        Method.named("fromRecord")
            .public_()
            .static_()
            .returnType(avroSpecific)
            .param(recordClass, "r")
            .body(
                LocalVar.builder(avroSpecific, "s").assigned(RawExpr.of("new @0()", avroSpecific)))
            .body(Element.of(""));
    for (Field f : message.fields()) {
      method.body(fromRecordField(f, "s", "r", registry, specificRecordPkg));
    }
    for (Oneof oneof : message.oneofs()) {
      method.body(oneofFromRecord(oneof, "s", "r", registry, specificRecordPkg));
    }
    // TODO(avro): transit the record's unknown-field carrier into the specific record. Until then
    // the always-present (non-nullable) carrier array is set empty, so the specific record the
    // converter returns can be Avro-encoded (a null array fails the binary write).
    return method
        .body(Element.expr("s.setUnknownFields(@0.of())", TypeRefs.LIST))
        .body(Element.ret("s"))
        .build();
  }

  private static Element fromRecordField(
      Field f, String param, String record, AvroRegistry registry, String specificRecordPkg) {
    String setter = param + ".set" + cap(f.javaName());
    String accessor = record + "." + f.javaName() + "()";

    // The U9 JSON envelope: a node-typed dynamic WKT component round-trips as the shared envelope
    // record; an absent (null) component sets the union's null branch.
    if (registry != null && registry.isEnvelopeField(f)) {
      return envelopeArm(setter, accessor, f, false, registry);
    }

    String wkt = AvroSchemaGenerator.typeUrlIfWkt(f);
    if (wkt != null) {
      return wktFromRecord(wkt, setter, accessor, f.isRepeated());
    }
    if (f.isMap()) {
      return mapFromRecord(f, setter, accessor, specificRecordPkg);
    }
    if (f.kind() == FieldKind.MESSAGE) {
      String fqn = f.typeUrl();
      String refName = registry.nameOf(fqn);
      String refNs = registry.namespaceOf(fqn);
      String refRecPkg = registry.recordPackageOf(fqn);
      Message ref = registry.messageOf(fqn);
      if (ref == null || refName == null || refNs == null || refRecPkg == null) {
        throw new AvroUnsupportedException(fqn, "unresolvable message reference");
      }
      TypeRef refConverter =
          TypeRef.named(
              refRecPkg, AvroSchemaGenerator.specificRecordName(ref.name()) + "Converter");
      if (f.isRepeated()) {
        return Element.expr(
            "@0(@2.stream().map(@1::fromRecord).toList())", setter, refConverter, accessor);
      }
      // A singular message is always presence-tracked (@Nullable); null-check before the nested
      // converter (a @NonNull target) and pass null to the (un-annotated) Avro setter when absent.
      return Element.expr(
          "@0(@2 == null ? null : @1.fromRecord(@2))", setter, refConverter, accessor);
    }
    if (f.kind() == FieldKind.ENUM) {
      return enumFromRecord(f, setter, accessor);
    }
    // Repeated length-delimited scalars convert per element (see the toRecord arms).
    if (f.kind() == FieldKind.SCALAR && f.isRepeated() && "string".equals(f.typeUrl())) {
      return Element.expr("@0(@1.copyOf(@2))", setter, TypeRefs.LIST, accessor);
    }
    if (f.kind() == FieldKind.SCALAR && f.isRepeated() && "bytes".equals(f.typeUrl())) {
      return Element.expr(
          "@0(@2.stream().map(e -> @1.wrap(e.toByteArray())).toList())",
          setter,
          TypeRefs.BYTE_BUFFER,
          accessor);
    }
    if (f.kind() == FieldKind.SCALAR && "bytes".equals(f.typeUrl())) {
      return Element.expr("@0(@1.wrap(@2.toByteArray()))", setter, TypeRefs.BYTE_BUFFER, accessor);
    }
    if (f.kind() == FieldKind.SCALAR && "string".equals(f.typeUrl())) {
      return Element.expr("@0(@1)", setter, accessor);
    }
    if (f.kind() == FieldKind.SCALAR) {
      if (f.isNullable()) {
        // A presence-tracked scalar is a @Nullable boxed primitive; the Avro twin's field is a
        // primitive, so null-check and default to the primitive's zero value when absent.
        return Element.expr(
            "@0(@1 == null ? " + scalarDefault(f.typeUrl()) + " : @1)", setter, accessor);
      }
      return Element.expr("@0(@1)", setter, accessor);
    }
    throw new AvroUnsupportedException(
        f.name(), "cannot map record field to an Avro field ('" + f.typeUrl() + "')");
  }

  /**
   * The U9 Jackson×Avro envelope arm, in the two directions a dynamic-WKT field rides it:
   *
   * <ul>
   *   <li><b>record → specific (fromRecord):</b> a null component takes the union's null branch;
   *       otherwise {@code new RecordbufDynamicWkt("json", DynamicWkt.contentType(<wkt FQN>, node),
   *       DynamicWkt.payload(node).jsonText())} — the content type is the WKT FQN, or an {@code
   *       Any}'s own {@code typeUrl}.
   *   <li><b>specific → record (toRecord):</b> a null envelope stays an absent (null) component;
   *       otherwise {@code DynamicWkt.node(JsonPayload.of(payload))}, cast to the component's
   *       concrete node type when it is narrower than {@code JsonNode}. The {@code contentType} is
   *       informational: the payload is self-describing JSON.
   * </ul>
   *
   * <p>A repeated field maps the same arm per element.
   *
   * @param target the record-side builder setter (toRecord) or the Avro-side setter (fromRecord)
   * @param source the side holding the value, as a rendered expression
   * @param f the field
   * @param toRecord true for specific→record, false for record→specific
   * @param registry the reachable-type registry, providing the envelope record and its namespace
   */
  private static Element envelopeArm(
      String target, String source, Field f, boolean toRecord, AvroRegistry registry) {
    TypeRef envelope = TypeRef.named(registry.documentNamespace(), registry.dynamicWktEntryName());
    String wktFqn = AvroSchemaGenerator.normalizeRef(f.typeUrl());
    Entry entry = Objects.requireNonNull(WellKnownTypes.resolve(f.typeUrl()), "dynamic WKT");
    TypeRef nodeType = Objects.requireNonNull(entry.nodeType(), "dynamic WKT node type");
    // `%s` is the per-value operand: the lambda parameter for a repeated field (mapped per
    // element),
    // the source expression itself for a singular one.
    boolean needsCast = toRecord && !nodeType.equals(TypeRefs.JSON_NODE);
    String convert;
    if (toRecord) {
      String cast = needsCast ? "(@4) " : "";
      convert = "%s == null ? null : " + cast + "@2.node(@3.of(%s.getPayload().toString()))";
    } else {
      convert =
          "%s == null ? null : new @2(\"json\", @3.contentType(\""
              + wktFqn
              + "\", %s), @3.payload(%s).jsonText())";
    }
    String template =
        f.isRepeated()
            ? "@0(@1.stream().map(v -> " + convert.replace("%s", "v") + ").toList())"
            : "@0(" + convert.replace("%s", "@1") + ")";
    // Pass the node type only when the cast uses it: every TypeRef argument becomes an import.
    if (needsCast) {
      return Element.expr(
          template, target, source, TypeRefs.DYNAMIC_WKT, TypeRefs.JSON_PAYLOAD, nodeType);
    }
    return toRecord
        ? Element.expr(template, target, source, TypeRefs.DYNAMIC_WKT, TypeRefs.JSON_PAYLOAD)
        : Element.expr(template, target, source, envelope, TypeRefs.DYNAMIC_WKT);
  }

  /**
   * A WKT field's Avro field. A repeated field maps each element with the conversion a singular
   * field applies to its value; the Avro array's elements are a {@code List} of the element type,
   * named by a type witness where the mapped value is a {@code String}.
   */
  private static Element wktFromRecord(
      String wkt, String setter, String accessor, boolean repeated) {
    if (!repeated) {
      return Element.expr("@0(@1)", setter, wktFromRecordValue(wkt, accessor));
    }
    if (SAME_VALUE_WKTS.contains(wkt)) {
      return Element.expr("@0(@1)", setter, accessor);
    }
    return Element.expr(
        "@0(@1.stream().<@2>map(v -> @3).toList())",
        setter,
        accessor,
        avroWktElement(wkt),
        wktFromRecordValue(wkt, "v"));
  }

  /**
   * The Avro value of one record WKT value {@code value} (a null record value stays null). A {@code
   * SAME_VALUE_WKTS} value passes through unchanged.
   */
  private static RawExpr wktFromRecordValue(String wkt, String value) {
    if (SAME_VALUE_WKTS.contains(wkt)) {
      return RawExpr.of(value);
    }
    return switch (wkt) {
      // StringValue: the String is a CharSequence in Avro, so it passes through.
      case "google.protobuf.StringValue" -> RawExpr.of(value);
      // Duration: java.time.Duration to the floor pair (non-negative nanos).
      case "google.protobuf.Duration" ->
          RawExpr.of(
              "@0 == null ? null : new @1(@0.toSeconds(), @0.toNanosPart())",
              value,
              avroWktRecord("Duration"));
      // FieldMask: the paths as a comma-joined string, via the bridge.
      case "google.protobuf.FieldMask" ->
          RawExpr.of(
              "@0 == null ? null : @1.fieldMaskToAvro(@0.paths())",
              value,
              TypeRefs.WKT_AVRO_BRIDGE);
      // BytesValue: Bytes to a ByteBuffer.
      case "google.protobuf.BytesValue" ->
          RawExpr.of("@0 == null ? null : @1.wrap(@0.toByteArray())", value, TypeRefs.BYTE_BUFFER);
      // Empty: the runtime Empty singleton to the empty avro record (no data).
      case "google.protobuf.Empty" ->
          RawExpr.of("@0 == null ? null : new @1()", value, avroWktRecord("Empty"));
      // Any other (NullValue): the resolved value is used as-is.
      default -> RawExpr.of(value);
    };
  }

  /**
   * The Avro Java type of one element of a repeated WKT field whose elements are mapped: the type
   * witness that keeps the mapped stream typed as the Avro array's element.
   */
  private static TypeRef avroWktElement(String wkt) {
    return switch (wkt) {
      case "google.protobuf.StringValue", "google.protobuf.FieldMask" ->
          TypeRef.named("java.lang", "CharSequence");
      case "google.protobuf.BytesValue" -> TypeRefs.BYTE_BUFFER;
      case "google.protobuf.Duration" -> avroWktRecord("Duration");
      case "google.protobuf.Empty" -> avroWktRecord("Empty");
      default -> throw new AvroUnsupportedException(wkt, "no Avro element type for a repeated WKT");
    };
  }

  /**
   * An enum component written as its constant's name. A singular enum component is
   * {@code @Nullable} (absent on the wire, which a proto3 enum cannot tell from the default), and
   * the Avro string is never null, so a null component writes the proto3 default, the first
   * constant's name. Avro therefore reads it back as that constant, not as null: the same value on
   * the wire, but not the same record.
   */
  private static Element enumFromRecord(Field f, String setter, String accessor) {
    if (f.isRepeated()) {
      // The Avro array is a List<CharSequence>: the type witness keeps map's String elements typed.
      return Element.expr(
          "@0(@1.stream().<@2>map(v -> v.name()).toList())",
          setter,
          accessor,
          TypeRef.named("java.lang", "CharSequence"));
    }
    TypeRef enumType = enumType(f);
    return Element.expr(
        "@0(@1 == null ? @2.name() : @1.name())", setter, accessor, firstConstant(enumType));
  }

  private static Element oneofFromRecord(
      Oneof oneof, String param, String record, AvroRegistry registry, String specificRecordPkg) {
    String setter = param + ".set" + cap(oneof.name());
    String accessor = record + "." + oneof.name() + "()";
    If.Builder b = If.condition(accessor + " == null").body(Element.expr(setter + "(null)"));
    for (Field member : oneof.members()) {
      TypeRef valueRec = memberValueRef(oneof, member);
      if (!AvroSchemaGenerator.ridesValueWrapper(member, registry)) {
        String fqn = member.typeUrl();
        String refName = registry.nameOf(fqn);
        String refNs = registry.namespaceOf(fqn);
        String refRecPkg = registry.recordPackageOf(fqn);
        Message ref = registry.messageOf(fqn);
        if (ref == null || refName == null || refNs == null || refRecPkg == null) {
          throw new AvroUnsupportedException(fqn, "unresolvable oneof message member");
        }
        TypeRef refConverter =
            TypeRef.named(
                refRecPkg, AvroSchemaGenerator.specificRecordName(ref.name()) + "Converter");
        b.elseIf(
            RawExpr.of(accessor + " instanceof @0 w", valueRec),
            Element.expr(setter + "(@0.fromRecord(w.value()))", refConverter));
      } else {
        // a wrapper-riding member: the shared {tag, value} wrapper, the value in its Avro form.
        String tag = String.valueOf(member.number());
        RawExpr wrapperValue = toWrapperValue(member, "w.value()", registry);
        // A member whose Avro form ignores the value (Empty) binds no pattern variable.
        String binding = wrapperValue.template().contains("w.value()") ? " w" : "";
        b.elseIf(
            RawExpr.of(accessor + " instanceof @0" + binding, valueRec),
            Element.expr(
                setter + "(new @0(" + tag + ", @1))",
                oneofValueRecord(specificRecordPkg),
                wrapperValue));
      }
    }
    return b.build();
  }

  // --- shared helpers ---------------------------------------------------------------------

  private static TypeRef oneofValueRecord(String specificRecordPkg) {
    return TypeRef.named(specificRecordPkg, AvroRegistry.ONEOF_VALUE_ENTRY);
  }

  /**
   * The record-side enum type of an enum field, the element type of a repeated one. It is read from
   * the field's model type, which names the enum through its enclosing record for a nested enum.
   */
  private static TypeRef enumType(Field f) {
    if (f.isRepeated() && f.type() instanceof TypeRef.Named list) {
      return list.generics().get(0);
    }
    return f.type();
  }

  /**
   * The record constant whose name is the Avro string {@code name}, or the first constant when none
   * has that name: the fallback the record's {@code forNumber} gives an undefined number, so an
   * unknown Avro name converts instead of failing.
   */
  private static RawExpr enumFromName(TypeRef enumType, String name) {
    return RawExpr.of(
        "@0.stream(@1.values()).filter(c -> c.name().contentEquals("
            + name
            + ")).findFirst().orElse(@2)",
        TypeRefs.ARRAYS,
        enumType,
        firstConstant(enumType));
  }

  /**
   * The enum's first constant: the proto3 default (protoc requires the first value to be zero) and
   * the record's {@code forNumber} fallback.
   */
  private static RawExpr firstConstant(TypeRef enumType) {
    return RawExpr.of(
        "@0.stream(@1.values()).findFirst().orElseThrow()", TypeRefs.ARRAYS, enumType);
  }

  /**
   * The zero-value literal for a scalar proto type, used to default a presence-tracked scalar to
   * its primitive's default when the record's value is null (absent).
   */
  private static String scalarDefault(String typeUrl) {
    return switch (typeUrl) {
      case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "0";
      case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> "0L";
      case "float" -> "0.0f";
      case "double" -> "0.0";
      case "bool" -> "false";
      default -> "0";
    };
  }

  private static String cap(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
