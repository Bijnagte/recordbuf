package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a single protobuf field. We deliberately keep this
 * framework-agnostic so writers/generators do not depend on protobuf's
 * classes.
 *
 * @param name           proto field name (snake_case)
 * @param javaName       java field name (from {@code (java_name)} option, else camelCased)
 * @param number         field tag
 * @param label          optional / required / repeated
 * @param kind           scalar / message / enum / map
 * @param typeUrl        for MESSAGE/ENUM, the fully-qualified proto type name
 *                       (used for resolution by the caller, not for codegen)
 * @param mapKey         for MAP, the proto scalar type of the key
 * @param mapValue       for MAP, the proto scalar type of the value
 * @param proto3Optional explicit optional keyword in proto3 (wraps in
 *                       {@code Optional<...>})
 * @param defaultValue   textual default (only meaningful for proto2 optionals
 *                       and for map/scalar defaults)
 */
@RecordBuilder
public record Field(
        String name,
        String javaName,
        int number,
        FieldLabel label,
        FieldKind kind,
        String typeUrl,
        String mapKey,
        String mapValue,
        boolean proto3Optional,
        Object defaultValue,
        @Nullable String comment
) {
    public boolean isMap() { return kind == FieldKind.MAP; }
    public boolean isMessage() { return kind == FieldKind.MESSAGE; }
    public boolean isEnum() { return kind == FieldKind.ENUM; }
    public boolean isScalar() { return kind == FieldKind.SCALAR; }
    public boolean isRepeated() { return label == FieldLabel.REPEATED; }
    public boolean isOptionalWrapped() {
        // Maps are always singular and never wrapped — they default to an empty
        // map. Only `optional` keyword (proto3) and explicit Label.OPTIONAL
        // trigger Optional<T>.
        if (kind == FieldKind.MAP) return false;
        return proto3Optional || label == FieldLabel.OPTIONAL;
    }
}
