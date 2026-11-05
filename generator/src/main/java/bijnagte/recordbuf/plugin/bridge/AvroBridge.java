package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.ProtoFile;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.util.List;

/**
 * The Avro wire-format bridge: the concrete {@link FormatBridge} that, for an enabled file, adds a
 * same-shaped conversion of every message/enum into Avro — a per-type {@code XSpecific}
 * representation, a per-type {@code XAvro} converter, and the per-type schema (an {@code SCHEMA}
 * constant on a {@code XSchema} companion plus a per-file {@code .avsc} output) — alongside the
 * record/codec output. Protobuf remains the only wire format: a message's {@code Codec} is never
 * touched; the bridge only adds the conversion.
 *
 * <p>Resolution (R1): enablement is {@code per-file recordbuf.avro option > global --avro flag >
 * built-in default(on)}, via {@link BridgeContext#avroEnabled}.
 */
public final class AvroBridge implements FormatBridge {

  @Override
  public boolean enabledFor(ProtoFile file, BridgeContext ctx) {
    return ctx.avroEnabled(file);
  }

  /**
   * The bridge's per-file output. Not yet implemented: a no-op stand-in so the seam + toggle wiring
   * is exercisable end-to-end, returning no files when the emitters (the per-type schema, the
   * {@code XSpecific}/{@code XAvro} converter, the per-file {@code .avsc}) are wired in.
   */
  @Override
  public List<CodeGeneratorResponse.File> emit(ProtoFile file, BridgeContext ctx) {
    return List.of();
  }

  /** The bridge the default registry ships. */
  public static AvroBridge instance() {
    return new AvroBridge();
  }
}
