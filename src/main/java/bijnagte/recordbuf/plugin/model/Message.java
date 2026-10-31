package bijnagte.recordbuf.plugin.model;

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
 */
public record Message(
        String name,
        String fullName,
        List<Field> fields,
        List<Message> nestedMessages,
        List<Enum> nestedEnums,
        boolean deprecated,
        @Nullable String comment
) {
    public boolean hasNested() {
        return !nestedMessages.isEmpty() || !nestedEnums.isEmpty();
    }
}
