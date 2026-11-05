package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.ProtoFile;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.util.List;

/**
 * The extension slot a non-protobuf wire-format bridge plugs into: the record/codec generator owns
 * the protobuf wire format (a message's {@code Codec} is never touched), and a {@code FormatBridge}
 * <em>adds</em> a same-shaped conversion of every message/enum into its own format — a per-type
 * representation, a per-type converter, and the per-type schema — alongside the record's output,
 * with no new user-facing flag per format.
 *
 * <p>Each bridge decides for itself, per file, whether it is enabled (R1's per-file {@code
 * recordbuf.avro} option, a per-request global flag the bridge owns, or the bridge's built-in
 * default). {@link #enabledFor} is the gate; {@link #emit} produces the bridge's files for an
 * enabled file. A bridge that is not enabled returns {@code false} and emits nothing, so an absent
 * bridge and a disabled bridge are both <em>no output</em> — the record/codec output is always
 * emitted, a bridge's is conditional.
 *
 * <p>Concrete bridges register themselves into a {@link BridgeRegistry}; the {@code recordbuf}
 * generator runs every registered bridge over every enabled file, in registration order.
 */
public interface FormatBridge {

  /**
   * Whether this bridge emits its output for {@code file}, given the per-invocation {@code ctx}
   * (the bridge's own global flag, parsed from the plugin parameter, plus run-wide context). The
   * resolution order is {@code per-file option > global flag > built-in default}, with a per-file
   * option winning over both.
   */
  boolean enabledFor(ProtoFile file, BridgeContext ctx);

  /** The this-bridge's output files for a bridge-enabled {@code file}; empty when disabled. */
  List<? extends CodeGeneratorResponse.File> emit(ProtoFile file, BridgeContext ctx);
}
