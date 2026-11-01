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
 * @param proto3Optional the raw {@code optional} keyword flag, used (with the
 *                       kind/label) to decide presence — see {@link #isNullable()}
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

    /**
     * Whether this field tracks presence in proto3 — i.e. whether its Java record
     * component is present-able (a boxed/nullable primitive, or a
     * {@code @Nullable} reference) rather than a fixed default-valued component.
     *
     * <p>Proto3 presence rules (proto3-only, per the plan): a singular
     * <em>message</em> always carries presence; a singular <em>scalar</em>/<em>enum</em>
     * carries presence only when the {@code optional} keyword is used. Repeated
     * fields and maps default to empty containers and never carry presence.
     */
    public boolean isNullable() {
        return switch (kind) {
            case MESSAGE -> label != FieldLabel.REPEATED; // singular message: present
            case SCALAR, ENUM -> proto3Optional;           // present only via `optional`
            default -> false;                              // MAP and any other singular kind
        };
    }
}
