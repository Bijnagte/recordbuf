package bijnagte.recordbuf.plugin.model;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link ProtoFile} model from a {@link FileDescriptorProto}.
 *
 * <p>Scope for type resolution is computed by walking the proto tree manually: a field of type
 * "Foo" inside message {@code a.B.C} resolves against {@code a.B.C.Foo}, {@code a.B.Foo}, {@code
 * a.Foo}, and {@code Foo}.
 *
 * <p>Real {@code oneof{...}} groups are routed out of a message's flat fields into {@link Oneof}
 * entries on {@link Message#oneofs()} (each becomes one nested sealed interface on the generated
 * record), while a proto3-{@code optional} scalar/enum's synthetic oneof is skipped and its member
 * stays a top-level field.
 */
public final class ModelLoader {

  private final FileTable table;
  private final String javaPkgOf; // java package of the file being loaded, computed outside

  // Leading doc comments (raw, un-escaped text) keyed by the element's location path in the
  // FileDescriptorProto's source_code_info tree. Populated by load() for the file being loaded;
  // the writers HTML-escape at emit time, so the model stores the user's raw text. Empty by
  // default so the nested load* helpers (which read it) never dereference null.
  private Map<List<Integer>, String> commentPaths = Map.of();

  // The three shipped proto extensions from src/main/proto/recordbuf_options.proto:
  //   (recordbuf.java_interface)        on MessageOptions, field 50000
  //   (recordbuf.java_enum_interface)   on EnumOptions,    field 50001
  //   (recordbuf.protobuf_java)         on MessageOptions, field 50002
  // Each resolved extension survives CodeGeneratorRequest serialization as an unknown field on
  // MessageOptions / EnumOptions at its assigned tag; we read it back by tag number rather than
  // by name (the plugin does not link the options .proto itself).
  private static final int MESSAGE_INTERFACE_TAG = 50000;
  private static final int ENUM_INTERFACE_TAG = 50001;
  private static final int MESSAGE_PROTOBUF_JAVA_TAG = 50002;
  // (recordbuf.avro) -- per-file enablement of the Avro bridge, an enum-typed FileOptions
  // extension; a resolved extension reaches us as a varint unknown field at this tag.
  private static final int FILE_AVRO_TAG = 50003;
  // Uninterpreted-option fallback names. This path is unreachable from a real protoc run for
  // these extensions (protoc errors on an unknown extension option it cannot resolve); it only
  // serves hand-constructed descriptors, so a matching name suffices.
  private static final String MESSAGE_INTERFACE_NAME = "recordbuf.java_interface";
  private static final String ENUM_INTERFACE_NAME = "recordbuf.java_enum_interface";
  private static final String MESSAGE_PROTOBUF_JAVA_NAME = "recordbuf.protobuf_java";
  private static final String FILE_AVRO_NAME = "recordbuf.avro";
  private static final String EMPTY_PROTOBUF_JAVA = "";

  public ModelLoader(FileTable table, String javaPackageForThisFile) {
    this.table = table;
    this.javaPkgOf = javaPackageForThisFile;
  }

  public ProtoFile load(FileDescriptorProto fdp) {
    // Leading comments are looked up by the element's source_code_info location path; build the
    // index up front so every nested load* helper can attach a comment to its target. The
    // file-level location (path []) is deliberately NOT rendered here (D3: file-level dropped).
    this.commentPaths = indexComments(fdp);
    List<Message> topMsgs = new ArrayList<>();
    for (int i = 0; i < fdp.getMessageTypeCount(); i++) {
      DescriptorProto m = fdp.getMessageType(i);
      topMsgs.add(loadMessage(m, /* protoScope= */ scopeRoot(fdp), /* basePath= */ path(4, i)));
    }
    List<Enum> topEnums = new ArrayList<>();
    for (int i = 0; i < fdp.getEnumTypeCount(); i++) {
      EnumDescriptorProto e = fdp.getEnumType(i);
      topEnums.add(loadEnum(e, /* basePath= */ path(5, i)));
    }
    return new ProtoFile(
        fdp.getName(),
        fdp.getPackage(),
        /* javaPackage= */ javaPkgOf,
        List.copyOf(topMsgs),
        List.copyOf(topEnums),
        /* javaMultipleFiles= */ false,
        /* comment= */ null,
        /* avroToggle= */ avroToggleFor(
            fdp.getOptions().getUnknownFields(), fdp.getOptions().getUninterpretedOptionList()));
  }

  /** The two-element source_code_info path to a top-level message/enum by (fieldNumber, index). */
  private static List<Integer> path(int fieldNumber, int index) {
    return List.of(fieldNumber, index);
  }

  /** Extend a location path one level: the child's fieldNumber and its index within the parent. */
  private static List<Integer> child(List<Integer> parent, int fieldNumber, int index) {
    List<Integer> c = new ArrayList<>(parent.size() + 2);
    c.addAll(parent);
    c.add(fieldNumber);
    c.add(index);
    return c;
  }

  /**
   * Index each {@code source_code_info} location's {@code leading_comments} by its path, skipping
   * locations with no (non-blank) leading comment. {@code leading_detached_comments} is ignored (a
   * detached block does not immediately precede its declaration, matching native).
   */
  private static Map<List<Integer>, String> indexComments(FileDescriptorProto fdp) {
    Map<List<Integer>, String> map = new LinkedHashMap<>();
    for (SourceCodeInfo.Location loc : fdp.getSourceCodeInfo().getLocationList()) {
      if (!loc.hasLeadingComments()) continue;
      String c = loc.getLeadingComments();
      if (c.isBlank()) continue;
      map.put(List.copyOf(loc.getPathList()), c);
    }
    return map;
  }

  /** The raw leading comment for a location path, or {@code null} when none was captured. */
  @Nullable
  private String commentAtPath(List<Integer> p) {
    return commentPaths.get(p);
  }

  private static String scopeRoot(FileDescriptorProto fdp) {
    return fdp.getPackage();
  }

  // --- messages ---

  private Message loadMessage(DescriptorProto m, String protoScope, List<Integer> basePath) {
    // Real oneof members are routed OUT of the flat fields() list and grouped by
    // their (raw) oneofIndex into Oneof entries (K1): a proto3 oneof{...} becomes
    // one nested sealed interface, and its members live only there. A proto3-optional
    // scalar/enum carries a SYNTHETIC oneofIndex (oneofDecl synthetic == true) whose
    // member must stay a top-level field, so a real oneof member is exactly
    // hasOneofIndex() && !proto3Optional().
    Map<Integer, List<Field>> oneofMembersByIndex = new LinkedHashMap<>();
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < m.getFieldCount(); i++) {
      // Skip the synthesized "Entry" fields inside map-entry messages:
      // they should never appear as fields of a user-declared message,
      // but defensively skip them anyway. `i` is the raw field index, which
      // is what source_code_info locations key on, so it is used even on skip.
      if (isMapEntryDescriptor(m)) {
        // skip — we represent maps as a single "map" Field on the parent
        continue;
      }
      FieldDescriptorProto raw = m.getField(i);
      Field f = loadField(raw, protoScope, child(basePath, 2, i));
      if (raw.hasOneofIndex() && !raw.getProto3Optional()) {
        // A real oneof member: group it on its oneofIndex (a top-level field is
        // not routed here, so its flat field list keeps its declaration order).
        oneofMembersByIndex.computeIfAbsent(raw.getOneofIndex(), k -> new ArrayList<>()).add(f);
      } else {
        fields.add(f);
      }
    }
    fields = maybeExpandMaps(fields, m, protoScope);

    // One entry per REAL oneof_decl, in declaration order. A proto3-optional scalar/
    // enum is a *synthetic* oneof whose sole member carries proto3Optional == true and
    // therefore routed NO member here; its absence of routed members is the gate that
    // skips it (a real oneof group always has routed members, and proto3 rejects empty
    // groups). NOTE: protobuf-java 4.36.2 does not expose OneofDescriptorProto.synthetic,
    // so the "has routed members" test is used in place of a synthetic flag.
    List<Oneof> oneofs = new ArrayList<>();
    for (int oi = 0; oi < m.getOneofDeclCount(); oi++) {
      List<Field> members = oneofMembersByIndex.get(oi);
      if (members == null || members.isEmpty()) continue;
      oneofs.add(new Oneof(m.getOneofDecl(oi).getName(), m.getOneofDecl(oi), m, members));
    }

    List<Message> nestedMsgs = new ArrayList<>();
    for (int j = 0; j < m.getNestedTypeCount(); j++) {
      DescriptorProto nested = m.getNestedType(j); // raw index: source_code_info keys on it
      if (isMapEntryDescriptor(nested)) continue; // don't surface Entry records
      nestedMsgs.add(
          loadMessage(nested, protoScopeForNested(m, protoScope), child(basePath, 3, j)));
    }
    List<Enum> nestedEnums = new ArrayList<>();
    for (int j = 0; j < m.getEnumTypeCount(); j++) {
      EnumDescriptorProto nested = m.getEnumType(j); // raw index: source_code_info keys on it
      nestedEnums.add(loadEnum(nested, child(basePath, 4, j)));
    }

    String javaScope = join(javaPkgOf, JavaNames.cap(m.getName()));
    return MessageBuilder.builder()
        .name(m.getName())
        .fullName(javaScope)
        .fields(List.copyOf(fields))
        .nestedMessages(List.copyOf(nestedMsgs))
        .nestedEnums(List.copyOf(nestedEnums))
        .oneofs(List.copyOf(oneofs))
        .deprecated(m.getOptions().getDeprecated())
        .comment(commentAtPath(basePath))
        .interfaces(
            interfacesFor(
                m.getOptions().getUnknownFields(),
                m.getOptions().getUninterpretedOptionList(),
                MESSAGE_INTERFACE_TAG,
                MESSAGE_INTERFACE_NAME))
        .protobufJava(
            protobufJavaFor(
                m.getOptions().getUnknownFields(), m.getOptions().getUninterpretedOptionList()))
        .build();
  }

  private static String protoScopeForNested(DescriptorProto m, String protoScope) {
    return protoScope.isBlank() ? m.getName() : protoScope + "." + m.getName();
  }

  /**
   * Walk the field list of a message and turn "repeated FooEntry" fields with map_entry=true into
   * Field(K,V) MAP fields.
   */
  private List<Field> maybeExpandMaps(
      List<Field> fields, DescriptorProto owner, String protoScope) {
    List<Field> result = new ArrayList<>(fields.size());
    for (Field f : fields) {
      if (f.isMap()) {
        result.add(f); // already a map — keep
        continue;
      }
      if (!f.isMessage()) {
        result.add(f);
        continue;
      }
      // Look up the field's message type in the owner's nested types.
      DescriptorProto entry = findNestedMessage(owner, f.typeUrl());
      if (entry != null && entry.getOptions().getMapEntry() && entry.getFieldCount() == 2) {
        result.add(buildMapField(f, entry, protoScope));
        continue;
      }
      result.add(f);
    }
    return result;
  }

  private @Nullable DescriptorProto findNestedMessage(DescriptorProto owner, String typeUrl) {
    // typeUrl may be a fully-qualified proto FQN or just a short name. We
    // only care about the last segment because the Entry message is always
    // a direct nested type of `owner`.
    String shortName = shortName(typeUrl);
    for (DescriptorProto nested : owner.getNestedTypeList()) {
      if (nested.getName().equalsIgnoreCase(shortName)) {
        return nested;
      }
    }
    return null;
  }

  private static String shortName(String typeUrl) {
    int dot = typeUrl.lastIndexOf('.');
    String s = dot < 0 ? typeUrl : typeUrl.substring(dot + 1);
    return s.startsWith(".") ? s.substring(1) : s;
  }

  private Field buildMapField(Field synthetic, DescriptorProto entry, String protoScope) {
    // entry.field(0) = key, entry.field(1) = value
    FieldDescriptorProto keyP = entry.getField(0);
    FieldDescriptorProto valP = entry.getField(1);
    // Store the PROTO TYPE NAME (e.g. "int32"/"string"/"bool") for the scalar
    // key/value, not the Java type. The codec needs the proto name to pick the
    // right wire scalar (int32 vs uint32 vs sint32 are all "int" in Java but
    // differ on the wire); RecordSourceMapping boxes that name to the record component
    // type (e.g. Map<String, Integer>). A message/enum value (out of scope for
    // this family) is resolved to its proto FQN for later resolution.
    String keyProtoType = mapMemberProtoType(keyP, typeScope(entry, protoScope));
    String valProtoType = mapMemberProtoType(valP, typeScope(entry, protoScope));
    // For maps, we keep the synthetic field's name but mark as MAP. The synthetic map field IS
    // the user's field (e.g. `map<string,int32> users = 1;`), so its comment/deprecation must
    // carry through from the field loadField already built -- not be re-defaulted to null/false.
    return new Field(
        synthetic.name(),
        synthetic.javaName(),
        synthetic.number(),
        FieldLabel.OPTIONAL,
        FieldKind.MAP,
        /* typeUrl= */ synthetic.typeUrl(),
        keyProtoType,
        valProtoType,
        /* proto3Optional= */ false,
        /* defaultValue= */ null,
        /* comment= */ synthetic.comment(),
        /* deprecated= */ synthetic.deprecated());
  }

  /**
   * The proto type name of a map entry's key/value field: the bare scalar name (e.g. "int32",
   * "string") for scalars, or the resolved proto FQN for a message/enum value.
   */
  private String mapMemberProtoType(FieldDescriptorProto f, String protoScope) {
    if (isScalar(f.getType())) return scalarTypeName(f.getType());
    return resolveType(f.getTypeName(), protoScope);
  }

  private static String typeScope(DescriptorProto entry, String protoScope) {
    // entry is nested in protoScope's enclosing; the typeUrl for the entry
    // itself lives at protoScope + "." + entry.name().
    return join(protoScope, entry.getName());
  }

  /** Resolve a proto type name into a fully-qualified proto FQN. */
  private String resolveType(String raw, String protoScope) {
    if (raw == null || raw.isBlank()) return raw;
    if (raw.startsWith(".")) return raw.substring(1);
    // Try scope-walking resolution. Outer-most first... actually inner-most
    // first per proto3 rules.
    List<String> scopes = new ArrayList<>();
    // Collect enclosing scopes from inner-most to outer-most.
    // We approximate: try parent (implied by caller's protoScope) and
    // progressively shorter prefixes.
    String[] parts = protoScope.split("\\.");
    scopes.add(protoScope);
    for (int i = parts.length; i > 0; i--) {
      scopes.add(String.join(".", Arrays.copyOfRange(parts, 0, i)));
    }
    scopes.add("");
    for (String sc : scopes) {
      String cand = sc.isEmpty() ? raw : sc + "." + raw;
      if (table.javaFqn(cand) != null) return cand;
    }
    // Last resort: relative to package root.
    return protoScope.isEmpty() ? raw : protoScope + "." + raw;
  }

  // --- enums ---

  private Enum loadEnum(EnumDescriptorProto e, List<Integer> basePath) {
    List<Enum.EnumConstant> cs = new ArrayList<>();
    for (int i = 0; i < e.getValueCount(); i++) {
      EnumValueDescriptorProto v = e.getValue(i);
      cs.add(
          new Enum.EnumConstant(
              JavaNames.snakeUpper(v.getName()),
              v.getNumber(),
              v.getOptions().getDeprecated(),
              commentAtPath(child(basePath, 2, i))));
    }
    String javaFqn = join(javaPkgOf, JavaNames.cap(e.getName()));
    return EnumBuilder.builder()
        .name(e.getName())
        .fullName(javaFqn)
        .constants(List.copyOf(cs))
        .deprecated(e.getOptions().getDeprecated())
        .comment(commentAtPath(basePath))
        .interfaces(
            interfacesFor(
                e.getOptions().getUnknownFields(),
                e.getOptions().getUninterpretedOptionList(),
                ENUM_INTERFACE_TAG,
                ENUM_INTERFACE_NAME))
        .build();
  }

  // --- fields ---

  private Field loadField(FieldDescriptorProto f, String protoScope, List<Integer> path) {
    String javaName = JavaNames.camel(f.getName());
    FieldLabel label = label(f.getLabel());

    FieldKind kind;
    String typeUrl = null;
    String mapKey = null, mapValue = null;

    switch (f.getType()) {
      case TYPE_MESSAGE, TYPE_GROUP -> {
        typeUrl = resolveType(f.getTypeName(), protoScope);
        kind = FieldKind.MESSAGE;
      }
      case TYPE_ENUM -> {
        typeUrl = resolveType(f.getTypeName(), protoScope);
        kind = FieldKind.ENUM;
      }
      default -> {
        kind = FieldKind.SCALAR;
        typeUrl = scalarTypeName(f.getType());
      }
    }

    Object defaultVal = extractDefault(f);
    // `proto3Optional` carries ONLY the `optional` keyword flag; presence is
    // decided by Field.isNullable() (which also treats singular messages as
    // present and repeated/map as always-defaulted). We must NOT fold the
    // label in here: in proto3 every singular field is LABEL_OPTIONAL, so
    // `proto3Optional || label == OPTIONAL` would wrongly mark plain scalars
    // as present-able (the root cause of the bogus Optional<int>).
    boolean proto3Opt = f.getProto3Optional();

    return new Field(
        f.getName(),
        javaName,
        f.getNumber(),
        label,
        kind,
        typeUrl,
        mapKey,
        mapValue,
        /* proto3Optional= */ proto3Opt,
        defaultVal,
        /* comment= */ commentAtPath(path),
        /* deprecated= */ f.getOptions().getDeprecated());
  }

  private static FieldLabel label(FieldDescriptorProto.Label raw) {
    return switch (raw) {
      case LABEL_OPTIONAL -> FieldLabel.OPTIONAL;
      case LABEL_REQUIRED -> FieldLabel.REQUIRED;
      case LABEL_REPEATED -> FieldLabel.REPEATED;
      default -> FieldLabel.OPTIONAL;
    };
  }

  // --- scalar / type helpers ---

  /** Returns the lowercased proto type name for a scalar, e.g. "int32". */
  static String scalarTypeName(FieldDescriptorProto.Type t) {
    // The enum constants are TYPE_DOUBLE, TYPE_INT32, etc. — strip the
    // prefix to get the wire-level name.
    String n = t.name();
    if (n.startsWith("TYPE_")) n = n.substring(5);
    return n.toLowerCase(Locale.ROOT);
  }

  static boolean isScalar(FieldDescriptorProto.Type t) {
    return switch (t) {
      case TYPE_DOUBLE,
          TYPE_FLOAT,
          TYPE_INT32,
          TYPE_INT64,
          TYPE_UINT32,
          TYPE_UINT64,
          TYPE_SINT32,
          TYPE_SINT64,
          TYPE_FIXED32,
          TYPE_FIXED64,
          TYPE_SFIXED32,
          TYPE_SFIXED64,
          TYPE_BOOL,
          TYPE_STRING,
          TYPE_BYTES ->
          true;
      default -> false;
    };
  }

  private static @Nullable Object extractDefault(FieldDescriptorProto f) {
    if (f.getType() == FieldDescriptorProto.Type.TYPE_STRING)
      return f.hasDefaultValue() ? f.getDefaultValue() : "";
    if (f.getType() == FieldDescriptorProto.Type.TYPE_BYTES)
      return f.hasDefaultValue() ? f.getDefaultValue() : "";
    if (f.getType() == FieldDescriptorProto.Type.TYPE_BOOL)
      return f.hasDefaultValue() ? Boolean.parseBoolean(f.getDefaultValue()) : false;
    return null;
  }

  private static boolean isMapEntryDescriptor(DescriptorProto m) {
    return m.getOptions().getMapEntry();
  }

  private static String join(String a, String b) {
    if (a == null || a.isBlank()) return b == null ? "" : b;
    if (b == null || b.isBlank()) return a;
    return a + "." + b;
  }

  /**
   * Collect the fully-qualified interface names for a message/enum that declared the matching proto
   * option, de-duplicated but preserving declaration order. Prefers the COMPILED path (a resolved
   * extension reaches us as an unknown field at {@code tag}) and falls back to uninterpreted
   * options whose dotted name equals {@code uninterpretedName}. Returns an empty list when none are
   * declared.
   */
  private static List<String> interfacesFor(
      UnknownFieldSet unknownFields,
      List<UninterpretedOption> uninterpreted,
      int tag,
      String uninterpretedName) {
    LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
    // Compiled path: one length-delimited entry per declaration, in order (duplicates kept).
    UnknownFieldSet.Field f = unknownFields.getField(tag);
    for (ByteString value : f.getLengthDelimitedList()) {
      ordered.put(value.toStringUtf8(), Boolean.TRUE);
    }
    // Fallback path: only reachable from hand-constructed descriptors.
    for (UninterpretedOption opt : uninterpreted) {
      if (opt.hasStringValue() && optionName(opt).equals(uninterpretedName)) {
        ordered.put(opt.getStringValue().toStringUtf8(), Boolean.TRUE);
      }
    }
    return new ArrayList<>(ordered.keySet());
  }

  /** Reconstruct a {@code (pkg.name)} dotted option name from an uninterpreted option's parts. */
  private static String optionName(UninterpretedOption opt) {
    StringBuilder sb = new StringBuilder();
    for (UninterpretedOption.NamePart part : opt.getNameList()) {
      if (!sb.isEmpty()) {
        sb.append('.');
      }
      sb.append(part.getNamePart());
    }
    return sb.toString();
  }

  /**
   * Read the singular {@code recordbuf.protobuf_java} option value (the java_package that hosts the
   * equivalent protobuf-java reference) for a message, or <strong>empty</strong> when neither path
   * carries it. Prefers the COMPILED path (a resolved extension reaches us as an unknown field at
   * {@code 50002}); falls back to an uninterpreted option whose dotted name equals {@link
   * #MESSAGE_PROTOBUF_JAVA_NAME}. Returns {@code ""} on absence so the caller can tell "not opted
   * in" from "opted in with a blank package" without a null check.
   */
  private static String protobufJavaFor(
      UnknownFieldSet unknownFields, List<UninterpretedOption> uninterpreted) {
    UnknownFieldSet.Field f = unknownFields.getField(MESSAGE_PROTOBUF_JAVA_TAG);
    if (!f.getLengthDelimitedList().isEmpty()) {
      return f.getLengthDelimitedList().get(0).toStringUtf8();
    }
    for (UninterpretedOption opt : uninterpreted) {
      if (opt.hasStringValue() && optionName(opt).equals(MESSAGE_PROTOBUF_JAVA_NAME)) {
        return opt.getStringValue().toStringUtf8();
      }
    }
    return EMPTY_PROTOBUF_JAVA;
  }

  /**
   * Read this file's {@code recordbuf.avro} control into the model's {@link AvroToggle}, or {@link
   * AvroToggle#NEUTRAL} when neither path carries it. Prefers the COMPILED path (a resolved
   * enum-typed file-option extension reaches us as a varint unknown field at {@code 50003}); falls
   * back to an uninterpreted option whose dotted name equals {@link #FILE_AVRO_NAME} (the only path
   * for a hand-constructed descriptor). An enum value of {@code RECORDBUF_AVRO_NONE}(0) -- or any
   * absent/unrecognized value -- maps to {@link AvroToggle#NEUTRAL} (the per-file option is unset,
   * so the global flag / built-in default decide); {@code RECORDBUF_AVRO_ON}(1) → {@link
   * AvroToggle#ON}, {@code RECORDBUF_AVRO_OFF}(2) → {@link AvroToggle#OFF}.
   */
  private static AvroToggle avroToggleFor(
      UnknownFieldSet unknownFields, List<UninterpretedOption> uninterpreted) {
    UnknownFieldSet.Field f = unknownFields.getField(FILE_AVRO_TAG);
    if (!f.getVarintList().isEmpty()) {
      return avroFromInt(f.getVarintList().get(0));
    }
    for (UninterpretedOption opt : uninterpreted) {
      if (optionName(opt).equals(FILE_AVRO_NAME) && opt.hasPositiveIntValue()) {
        return avroFromInt(opt.getPositiveIntValue());
      }
    }
    return AvroToggle.NEUTRAL;
  }

  /** Map an {@code AvroControl} wire value to the model's {@link AvroToggle}. */
  private static AvroToggle avroFromInt(long v) {
    int w = (int) v;
    return switch (w) {
      case 1 -> AvroToggle.ON;
      case 2 -> AvroToggle.OFF;
      // RECORDBUF_AVRO_NONE(0) and any unrecognized value both mean "unset per-file": fall through
      // to the global flag / default.
      default -> AvroToggle.NEUTRAL;
    };
  }
}
