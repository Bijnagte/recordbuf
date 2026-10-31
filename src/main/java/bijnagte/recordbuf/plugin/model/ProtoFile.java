package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Top-level view over a {@code .proto} file.
 *
 * <p>{@code javaPackage} may be empty — callers decide how to fall back (typically
 * to the proto package, then to the file stem).
 */
@RecordBuilder
public record ProtoFile(
        String fileName,
        String protoPackage,
        String javaPackage,
        List<Message> messages,
        List<Enum> enums,
        boolean javaMultipleFiles,
        @Nullable String comment
) {
    public boolean hasJavaPackage() { return javaPackage != null && !javaPackage.isBlank(); }
}
