package bijnagte.recordbuf.plugin.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a protobuf enum.
 */
public record Enum(
        String name,
        String fullName,
        List<EnumConstant> constants,
        boolean deprecated,
        @Nullable String comment
) {
    /** One entry in an enum. */
    public record EnumConstant(String name, int number, boolean deprecated, @Nullable String comment) {}
}
