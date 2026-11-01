package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a protobuf message.
 *
 * @param name            short name (last path component)
 * @param fullName        fully-qualified name within this file (e.g. {@code foo.Bar.Baz})
 * @param fields          direct fields (maps and oneofs expanded to "synthetic"
 *                        fields by the loader; see {@link Field#isMap()}).
 * @param nestedMessages  messages declared inside this one
 * @param nestedEnums     enums declared inside this one
 * @param deprecated      {@code option deprecated = true}
 * @param comment         leading doc comment, if captured
 * @param interfaces      fully-qualified Java interface names the record should
 *                        emit in an {@code implements} clause, in declaration
 *                        order and de-duplicated (never {@code null}); empty when
 *                        none are declared. The FQNs are opaque to the generator.
 */
@RecordBuilder
public record Message(
        String name,
        String fullName,
        List<Field> fields,
        List<Message> nestedMessages,
        List<Enum> nestedEnums,
        boolean deprecated,
        @Nullable String comment,
        List<String> interfaces
) {
    public boolean hasNested() {
        return !nestedMessages.isEmpty() || !nestedEnums.isEmpty();
    }

    /** Enforce the never-{@code null}, empty-default invariant on {@link #interfaces} even
     *  when a caller (or the record-builder's default initialization) supplies {@code null}. */
    public Message {
        if (interfaces == null) interfaces = List.of();
    }
}
