package bijnagte.recordbuf.plugin.model;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * Immutable view of a real protobuf {@code oneof} inside a {@link Message}.
 *
 * <p>The oneof's members live here — and here only — and are routed <em>out</em> of
 * the parent {@link Message}'s flat {@link Message#fields() fields} list by the
 * loader: one proto3 {@code oneof {...}} group becomes exactly one nested sealed
 * interface on the generated record (one {@code @Nullable} component, K4), and a
 * proto3-<em>optional</em> scalar/enum's <i>synthetic</i> {@code oneofIndex} is NOT
 * a real oneof, so its member stays a top-level field and no entry is created for it.
 *
 * <p>The oneof's Java surface: a nested {@code sealed interface} named
 * {@link #ifaceName() ifaceName()} (one record component of that type per oneof) that
 * permits, per member, either the member's own message record (an identity subtype) or
 * the one {@link #componentName()} wrapper record that carries a scalar/enum member's
 * value plus its discriminant (the member's field tag).
 *
 * @param name    the oneof's proto name
 * @param descriptor the raw protobuf {@link OneofDescriptorProto} for this group,
 *                carried from the loader so the codec can recover a member field's raw
 *                protobuf type (e.g. whether a member is an {@code enum}) for encode/decode
 *                casts without leaking protobuf classes into the writers; never {@code null}
 * @param parentDescriptor the raw {@link DescriptorProto} of the enclosing (owner) message,
 *                carried so the codec can resolve a member's raw field descriptor by tag via
 *                {@code parentDescriptor.getField(number)} (each oneof member's tag is also
 *                registered on the owner message, which is how a scalar/enum member's wire type
 *                is recovered without walking the oneof itself); never {@code null}
 * @param members the oneof's declared members in declaration order, routed out of the
 *                parent's flat fields list; never {@code null} (empty is the
 *                never-actually-emitted case: proto3 rejects empty oneof groups)
 */
@RecordBuilder
public record Oneof(String name, OneofDescriptorProto descriptor, DescriptorProto parentDescriptor, List<Field> members) {
    /** Normalize a {@code null} members list to an empty one and keep the members
     *  list immutably owned by this entry, mirroring {@link Message}'s
     *  invariant-on-its-owned-list style. */
    public Oneof {
        members = (members == null) ? List.of() : List.copyOf(members);
    }

    /**
     * The nested sealed interface's Java type name for this oneof — the first-letter
     * capitalization of the oneof's camelCased proto name. E.g. oneof {@code choice} ->
     * {@code Choice}, oneof {@code my_member} -> {@code MyMember}. One record component of
     * this type per oneof (K4) and the permits/implements surface (K6) key off it.
     */
    public String ifaceName() {
        return JavaNames.cap(JavaNames.camel(name));
    }

    /**
     * The one record component's Java name and the value wrapper's nested record name
     * for this oneof — the camelCased proto name of the group. E.g. oneof {@code choice}
     * -> component {@code choice}; oneof {@code my_member} -> {@code myMember}. The
     * wrapper record is nested inside {@link #ifaceName()} as the shared
     * {@code <iface>.Scalar(Object value, int tag)} type.
     */
    public String componentName() {
        return JavaNames.camel(name);
    }
}
