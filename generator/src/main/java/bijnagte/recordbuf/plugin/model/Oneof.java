package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * Immutable view of a real protobuf {@code oneof} inside a {@link Message}.
 *
 * <p>A oneof's members live here — and here only — and are routed <em>out</em> of
 * the parent {@link Message}'s flat {@link Message#fields() fields} list by the
 * loader: one proto3 {@code oneof {...}} group becomes exactly one nested
 * {@code sealed interface} on the generated record (a single {@code @Nullable}
 * component, per the sealed-hierarchy design). A proto3-<em>optional</em>
 * scalar/enum's <i>synthetic</i> {@code oneofIndex} is NOT a real oneof, so its
 * member stays a top-level field and no such entry is created for it.
 *
 * @param name    the oneof's proto name (its Java interface is
 *                {@link JavaNames#cap(String) JavaNames.cap(name)})
 * @param members the oneof's declared members in declaration order, routed out of
 *                the parent's flat fields list; never {@code null} (empty when the
 *                group is empty, which proto3 rejects, but the invariant is held)
 */
@RecordBuilder
public record Oneof(String name, List<Field> members) {
    /** Normalize a {@code null} members list to an empty one and keep the members
     *  list immutably owned by this entry, mirroring {@link Message}'s
     *  invariant-on-its-owned-list style. */
    public Oneof {
        members = (members == null) ? List.of() : List.copyOf(members);
    }
}
