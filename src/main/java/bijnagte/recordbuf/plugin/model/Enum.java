package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a protobuf enum.
 *
 * @param name            short name (last path component)
 * @param fullName        fully-qualified name within this file
 * @param constants       the enum's constants
 * @param deprecated      {@code option deprecated = true}
 * @param comment         leading doc comment, if captured
 * @param interfaces      fully-qualified Java interface names the enum should
 *                        emit in an {@code implements} clause, in declaration
 *                        order and de-duplicated (never {@code null}); empty when
 *                        none are declared. The FQNs are opaque to the generator.
 */
@RecordBuilder
public record Enum(
        String name,
        String fullName,
        List<EnumConstant> constants,
        boolean deprecated,
        @Nullable String comment,
        List<String> interfaces
) {
    /** One entry in an enum. */
    @RecordBuilder
    public record EnumConstant(String name, int number, boolean deprecated, @Nullable String comment) {}

    /** Enforce the never-{@code null}, empty-default invariant on {@link #interfaces} even
     *  when a caller (or the record-builder's default initialization) supplies {@code null}. */
    public Enum {
        if (interfaces == null) interfaces = List.of();
    }
}
