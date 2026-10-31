package bijnagte.recordbuf.plugin.model;

import java.util.List;
import java.util.Optional;

/**
 * Top-level view over a {@code .proto} file.
 *
 * <p>{@code javaPackage} may be empty — callers decide how to fall back (typically
 * to the proto package, then to the file stem).
 */
public record ProtoFile(
        String fileName,
        String protoPackage,
        String javaPackage,
        List<Message> messages,
        List<Enum> enums,
        boolean javaMultipleFiles,
        Optional<String> comment
) {
    public boolean hasJavaPackage() { return javaPackage != null && !javaPackage.isBlank(); }
}
