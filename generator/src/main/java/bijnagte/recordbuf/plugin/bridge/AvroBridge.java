package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
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
 * <p>Resolution (R1): enablement is {@code per-file recordbuf.avro option &gt; global --avro flag
 * &gt; built-in default(on)}, via {@link BridgeContext#avroEnabled}.
 *
 * <p><b>Wiring scope:</b> U5 delivers the per-type emitters as generator-tested artifacts: {@link
 * bijnagte.recordbuf.plugin.writer.AvroSpecificGenerator} ({@code XSpecific} + embedded {@code
 * SCHEMA}) and {@link bijnagte.recordbuf.plugin.writer.AvroConverterGenerator} ({@code XAvro} with
 * {@code fromRecord}/{@code toRecord}). They are <em>not yet wired into this per-file emit</em>
 * because emitting the per-type {@code .java} into the live output requires those classes to
 * compile + round-trip — the fixtures harness (U6). Until U6, this bridge emits only the standalone
 * {@code .avsc} (R6), which keeps the whole build green.
 */
public final class AvroBridge implements FormatBridge {

  @Override
  public boolean enabledFor(ProtoFile file, BridgeContext ctx) {
    return ctx.avroEnabled(file);
  }

  @Override
  public List<? extends CodeGeneratorResponse.File> emit(ProtoFile file, BridgeContext ctx) {
    // Off: a disabled file contributes nothing, exactly as an absent bridge would (R1/R3).
    if (!enabledFor(file, ctx)) return List.of();

    final String schema;
    try {
      schema = AvroSchemaGenerator.schema(file);
    } catch (AvroUnsupportedException notYetSupported) {
      // Fail-loud at the mapping unit (unit-tested); at the file level v1 skips so an existing
      // generation with an unsupported shape is not aborted by the bridge.
      return List.of();
    }
    if (schema.isEmpty()) return List.of(); // no top-level message: nothing to bridge

    // The standalone .avsc output (R6). U6 wires the per-type emitters in and the
    // compile/round-trip.
    String path = file.messages().get(0).fullName() + "AvroSchema.avsc";
    CodeGeneratorResponse.File avsc =
        CodeGeneratorResponse.File.newBuilder().setName(path).setContent(schema).build();
    return List.of(avsc);
  }

  /** The bridge the default registry ships. */
  public static AvroBridge instance() {
    return new AvroBridge();
  }
}
