package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.ProtoFile;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * The registry of {@link FormatBridge}s a {@code recordbuf} invocation runs over every file. A
 * registry is built fresh per invocation (so per-call generation stays stateless) and seeded with
 * the bridges the plugin ships; a future bridge (say, a JSON or a different-wire bridge) plugs in
 * by registering itself, with <em>no</em> change to the record/codec generator or a new user-facing
 * flag — that is the extension-slot seam R1/R2 want.
 *
 * <p>Each file is offered to every bridge in registration order; a bridge emits for a file only
 * when {@link FormatBridge#enabledFor} returns {@code true}, so a disabled bridge and an absent
 * bridge both yield no output. The record/codec output is always emitted (it is not a bridge); the
 * registry adds the conditional, per-bridge output that accompanies it.
 */
public final class BridgeRegistry {

  private final List<FormatBridge> bridges;

  private BridgeRegistry(List<FormatBridge> bridges) {
    this.bridges = bridges;
  }

  /** A fresh registry seeded with the bridge the plugin ships. */
  public static BridgeRegistry defaultRegistry() {
    return new BridgeRegistry(List.of(AvroBridge.instance()));
  }

  /** Run every registered bridge over {@code file}; concatenate each enabled bridge's output. */
  public List<CodeGeneratorResponse.File> emitAll(ProtoFile file, BridgeContext ctx) {
    List<CodeGeneratorResponse.File> out = new ArrayList<>();
    for (FormatBridge bridge : bridges) {
      if (bridge.enabledFor(file, ctx)) {
        out.addAll(bridge.emit(file, ctx));
      }
    }
    return out;
  }
}
