package bijnagte.recordbuf.plugin.model;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Top-level view over a {@code .proto} file.
 *
 * <p>{@code javaPackage} may be empty — callers decide how to fall back (typically to the proto
 * package, then to the file stem).
 *
 * <p>{@code jackson} is the resolved per-file Jackson surface (2026-11-20 Jackson plan, default
 * ON): a file without the {@code recordbuf.jackson} option generates the Jackson annotation
 * surface; only an explicit {@code recordbuf.jackson = false} generates the byte-identical,
 * dependency-free output.
 */
@RecordBuilder
public record ProtoFile(
    String fileName,
    String protoPackage,
    String javaPackage,
    List<Message> messages,
    List<Enum> enums,
    boolean javaMultipleFiles,
    @Nullable String comment,
    AvroToggle avroToggle,
    boolean jackson) {
  public boolean hasJavaPackage() {
    return javaPackage != null && !javaPackage.isBlank();
  }
}
