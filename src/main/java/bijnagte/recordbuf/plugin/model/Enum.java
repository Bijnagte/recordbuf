package bijnagte.recordbuf.plugin.model;

import java.util.List;
import java.util.Optional;

/**
 * Immutable view of a protobuf enum.
 */
public record Enum(
        String name,
        String fullName,
        List<EnumConstant> constants,
        boolean deprecated,
        Optional<String> comment
) {
    /** One entry in an enum. */
    public record EnumConstant(String name, int number, boolean deprecated, Optional<String> comment) {}
}
