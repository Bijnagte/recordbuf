package bijnagte.recordbuf.plugin.types;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;

/**
 * Maps protobuf field types to Java record-component types.
 *
 * <p>Conventions (proto3 syntax assumed unless noted):
 * <ul>
 *   <li>Scalar:  the obvious Java type ({@code int32→int}, {@code float→float},
 *       {@code string→String}, {@code bytes→byte[]}, ...).</li>
 *   <li>Message field: the named Java record (resolved by the caller; we
 *       return a placeholder string to keep types stateless).</li>
 *   <li>{@code optional} (proto3 explicit / proto2 implicit): wrapped in
 *       {@link Optional}.</li>
 *   <li>{@code repeated}: {@link java.util.List} (we use an unmodifiable view
 *       at the codec boundary and expose the bare {@code List<T>}).</li>
 *   <li>{@code map<K,V>}:  {@link java.util.Map}</li>
 *   <li>Oneof: handled at the message level — generated as a sealed hierarchy
 *       (TODO v1) or as mutually-exclusive components (current behavior).</li>
 * </ul>
 */
public final class JavaTypeMap {
    private JavaTypeMap() { }

    /** Returns the canonical Java type name (without generics) for a scalar field type. */
    public static String scalarJavaType(Type t) {
        return switch (t) {
            case TYPE_DOUBLE   -> "double";
            case TYPE_FLOAT    -> "float";
            case TYPE_INT64    -> "long";
            case TYPE_UINT64   -> "long";
            case TYPE_SINT64   -> "long";
            case TYPE_FIXED64  -> "long";
            case TYPE_SFIXED64 -> "long";
            case TYPE_INT32    -> "int";
            case TYPE_FIXED32  -> "int";
            case TYPE_SFIXED32 -> "int";
            case TYPE_SINT32   -> "int";
            case TYPE_UINT32   -> "int"; // v1: use int; revisit
            case TYPE_BOOL     -> "boolean";
            case TYPE_STRING   -> "java.lang.String";
            case TYPE_BYTES    -> "byte[]";
            // Non-scalar: callers should handle these before reaching here.
            default             -> null;
        };
    }

    /**
     * Build a Java signature for one field's component type, given its
     * resolution callbacks.
     *
     * @param field        the field
     * @param elementType   pre-resolved Java type name for the element of the
     *                      component (e.g. the message record name or enum
     *                      name); used only for MESSAGE / ENUM types.
     * @param mapKey        pre-resolved Java type for the map key (only when
     *                      {@code isMap}).
     * @param mapValue      pre-resolved Java type for the map value (only when
     *                      {@code isMap}).
     */
    public static String fieldType(FieldDescriptorProto field,
                                   String elementType,
                                   String mapKey,
                                   String mapValue) {
        boolean repeated = field.getLabel() == Label.LABEL_REPEATED;
        boolean optional = field.getProto3Optional() || field.getLabel() == Label.LABEL_OPTIONAL;
        String base;

        switch (field.getType()) {
            case TYPE_MESSAGE, TYPE_ENUM -> base = requireType(elementType, field);
            case TYPE_GROUP ->
                throw new UnsupportedOperationException("groups are not supported: " + field.getName());
            default -> {
                String scalar = scalarJavaType(field.getType());
                base = scalar != null ? scalar : "java.lang.Object";
            }
        }

        if (repeated) {
            return "java.util.List<" + base + ">";
        }
        if (optional) {
            // Only wrap if the base type is not already a reference we always
            // keep null-free. We wrap everything to keep semantics explicit.
            return "java.util.Optional<" + base + ">";
        }
        return base;
    }

    private static String requireType(String t, FieldDescriptorProto f) {
        if (t == null || t.isBlank()) {
            throw new IllegalStateException("missing resolved type for " + f.getName()
                    + " (fieldType=" + f.getType() + ")");
        }
        return t;
    }

    /** Java identifier for an enum value symbol, upper-snake-cased. */
    public static String enumJavaConstant(String protoName) {
        return protoName.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
