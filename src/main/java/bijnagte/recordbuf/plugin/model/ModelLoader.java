package bijnagte.recordbuf.plugin.model;

import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;

/**
 * Builds a {@link ProtoFile} model from a {@link FileDescriptorProto}.
 *
 * <p>Scope for type resolution is computed by walking the proto tree manually:
 * a field of type "Foo" inside message {@code a.B.C} resolves against
 * {@code a.B.C.Foo}, {@code a.B.Foo}, {@code a.Foo}, and {@code Foo}.
 */
public final class ModelLoader {

    private final FileTable table;
    private final String javaPkgOf; // java package of the file being loaded, computed outside

    public ModelLoader(FileTable table, String javaPackageForThisFile) {
        this.table = table;
        this.javaPkgOf = javaPackageForThisFile;
    }

    public ProtoFile load(FileDescriptorProto fdp) {
        List<Message> topMsgs = new ArrayList<>();
        for (DescriptorProto m : fdp.getMessageTypeList()) {
            topMsgs.add(loadMessage(m, /*protoScope=*/ scopeRoot(fdp)));
        }
        List<Enum> topEnums = new ArrayList<>();
        for (EnumDescriptorProto e : fdp.getEnumTypeList()) {
            topEnums.add(loadEnum(e, /*protoScope=*/ scopeRoot(fdp)));
        }
        return new ProtoFile(
                fdp.getName(),
                fdp.getPackage(),
                /*javaPackage=*/ javaPkgOf,
                List.copyOf(topMsgs),
                List.copyOf(topEnums),
                /*javaMultipleFiles=*/ false,
                /*comment=*/ null
        );
    }

    private static String scopeRoot(FileDescriptorProto fdp) {
        return fdp.getPackage();
    }

    // --- messages ---

    private Message loadMessage(DescriptorProto m, String protoScope) {
        List<Field> fields = new ArrayList<>();
        for (FieldDescriptorProto f : m.getFieldList()) {
            // Skip the synthesized "Entry" fields inside map-entry messages:
            // they should never appear as fields of a user-declared message,
            // but defensively skip them anyway.
            if (isMapEntryDescriptor(m)) {
                // skip — we represent maps as a single "map" Field on the parent
            } else {
                fields.add(loadField(f, m, protoScope));
            }
        }
        fields = maybeExpandMaps(fields, m, protoScope);

        List<Message> nestedMsgs = new ArrayList<>();
        for (DescriptorProto nested : m.getNestedTypeList()) {
            if (isMapEntryDescriptor(nested)) continue; // don't surface Entry records
            nestedMsgs.add(loadMessage(nested, protoScopeForNested(m, protoScope)));
        }
        List<Enum> nestedEnums = new ArrayList<>();
        for (EnumDescriptorProto nested : m.getEnumTypeList()) {
            nestedEnums.add(loadEnum(nested, protoScopeForNested(m, protoScope)));
        }

        String javaScope = join(javaPkgOf, JavaNames.cap(m.getName()));
        return new Message(
                m.getName(),
                /*fullName=*/ javaScope,
                /*fields=*/ List.copyOf(fields),
                /*nestedMessages=*/ List.copyOf(nestedMsgs),
                /*nestedEnums=*/ List.copyOf(nestedEnums),
                /*deprecated=*/ m.getOptions().getDeprecated(),
                /*comment=*/ null
        );
    }

    private static String protoScopeForNested(DescriptorProto m, String protoScope) {
        return protoScope.isBlank() ? m.getName() : protoScope + "." + m.getName();
    }

    /** Walk the field list of a message and turn "repeated FooEntry" fields
     *  with map_entry=true into Field(K,V) MAP fields. */
    private List<Field> maybeExpandMaps(List<Field> fields, DescriptorProto owner, String protoScope) {
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
            if (entry != null && entry.getOptions().getMapEntry()
                    && entry.getFieldCount() == 2) {
                result.add(buildMapField(f, entry, protoScope));
                continue;
            }
            result.add(f);
        }
        return result;
    }

    private DescriptorProto findNestedMessage(DescriptorProto owner, String typeUrl) {
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
        if (typeUrl == null) return null;
        int dot = typeUrl.lastIndexOf('.');
        String s = dot < 0 ? typeUrl : typeUrl.substring(dot + 1);
        return s.startsWith(".") ? s.substring(1) : s;
    }    private Field buildMapField(Field synthetic, DescriptorProto entry, String protoScope) {
        // entry.field(0) = key, entry.field(1) = value
        FieldDescriptorProto keyP = entry.getField(0);
        FieldDescriptorProto valP = entry.getField(1);
        String keyJavaType = scalarOrTypeJava(keyP, typeScope(entry, protoScope));
        String valJavaType = scalarOrTypeJava(valP, typeScope(entry, protoScope));
        // For maps, we keep the synthetic field's name but mark as MAP.
        return new Field(
                synthetic.name(),
                synthetic.javaName(),
                synthetic.number(),
                FieldLabel.OPTIONAL,
                FieldKind.MAP,
                /*typeUrl=*/ synthetic.typeUrl(),
                keyJavaType,
                valJavaType,
                /*proto3Optional=*/ false,
                /*defaultValue=*/ null,
                null
        );
    }

    private static String typeScope(DescriptorProto entry, String protoScope) {
        // entry is nested in protoScope's enclosing; the typeUrl for the entry
        // itself lives at protoScope + "." + entry.name().
        return join(protoScope, entry.getName());
    }

    private static String stripDot(String s) {
        return s == null ? "" : (s.startsWith(".") ? s.substring(1) : s);
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
            scopes.add(String.join(".", java.util.Arrays.copyOfRange(parts, 0, i)));
        }
        scopes.add("");
        for (String sc : scopes) {
            String cand = sc.isEmpty() ? raw : sc + "." + raw;
            if (table.javaFqn(cand) != null) return cand;
        }
        // Last resort: relative to package root.
        return protoScope.isEmpty() ? raw : protoScope + "." + raw;
    }

    private String scalarOrTypeJava(FieldDescriptorProto f, String protoScope) {
        if (isScalar(f.getType())) return scalarJava(f.getType());
        // Message or Enum — resolve the Java FQN.
        String protoFqn = resolveType(f.getTypeName(), protoScope);
        String javaFqn = table.javaFqn(protoFqn);
        if (javaFqn == null) {
            // Unknown (probably an undefined dependency); use the bare name.
            return JavaNames.cap(shortName(f.getTypeName()));
        }
        return javaFqn;
    }

    // --- enums ---

    private Enum loadEnum(EnumDescriptorProto e, String protoScope) {
        List<Enum.EnumConstant> cs = new ArrayList<>();
        for (EnumValueDescriptorProto v : e.getValueList()) {
            cs.add(new Enum.EnumConstant(
                    JavaNames.snakeUpper(v.getName()),
                    v.getNumber(),
                    v.getOptions().getDeprecated(),
                    null
            ));
        }
        String javaFqn = join(javaPkgOf, JavaNames.cap(e.getName()));
        return new Enum(
                e.getName(),
                /*fullName=*/ javaFqn,
                List.copyOf(cs),
                e.getOptions().getDeprecated(),
                null
        );
    }

    // --- fields ---

    private Field loadField(FieldDescriptorProto f, DescriptorProto parent, String protoScope) {
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
                /*proto3Optional=*/ proto3Opt,
                defaultVal,
                null
        );
    }

    private static FieldLabel label(FieldDescriptorProto.Label raw) {
        return switch (raw) {
            case LABEL_OPTIONAL -> FieldLabel.OPTIONAL;
            case LABEL_REQUIRED -> FieldLabel.REQUIRED;
            case LABEL_REPEATED -> FieldLabel.REPEATED;
            default             -> FieldLabel.OPTIONAL;
        };
    }

    // --- scalar / type helpers ---

    /** Returns the lowercased proto type name for a scalar, e.g. "int32". */
    static String scalarTypeName(FieldDescriptorProto.Type t) {
        // The enum constants are TYPE_DOUBLE, TYPE_INT32, etc. — strip the
        // prefix to get the wire-level name.
        String n = t.name();
        if (n.startsWith("TYPE_")) n = n.substring(5);
        return n.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean isScalar(Type t) {
        return switch (t) {
            case TYPE_DOUBLE, TYPE_FLOAT, TYPE_INT32, TYPE_INT64, TYPE_UINT32,
                 TYPE_UINT64, TYPE_SINT32, TYPE_SINT64, TYPE_FIXED32, TYPE_FIXED64,
                 TYPE_SFIXED32, TYPE_SFIXED64, TYPE_BOOL, TYPE_STRING, TYPE_BYTES -> true;
            default -> false;
        };
    }

    static String scalarJava(Type t) {
        return switch (t) {
            case TYPE_DOUBLE   -> "double";
            case TYPE_FLOAT    -> "float";
            case TYPE_INT32, TYPE_SINT32, TYPE_SFIXED32, TYPE_FIXED32 -> "int";
            case TYPE_INT64, TYPE_SINT64, TYPE_SFIXED64, TYPE_FIXED64 -> "long";
            case TYPE_UINT32, TYPE_UINT64 -> "int"; // v1: use int; revisit in v2
            case TYPE_BOOL    -> "boolean";
            case TYPE_STRING  -> "java.lang.String";
            case TYPE_BYTES   -> "byte[]";
            default           -> "java.lang.Object";
        };
    }

    private static Object extractDefault(FieldDescriptorProto f) {
        if (f.getType() == Type.TYPE_STRING)
            return f.hasDefaultValue() ? f.getDefaultValue() : "";
        if (f.getType() == Type.TYPE_BYTES)
            return f.hasDefaultValue() ? f.getDefaultValue() : "";
        if (f.getType() == Type.TYPE_BOOL)
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
}
