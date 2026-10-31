package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a protobuf enum.
 */
@RecordBuilder
public record Enum(
        String name,
        String fullName,
        List<EnumConstant> constants,
        boolean deprecated,
        @Nullable String comment
) {
    /** One entry in an enum. */
    @RecordBuilder
    public record EnumConstant(String name, int number, boolean deprecated, @Nullable String comment) {}
}
