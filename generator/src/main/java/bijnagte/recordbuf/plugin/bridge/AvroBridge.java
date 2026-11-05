package bijnagte.recordbuf.plugin.bridge;

import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;

/**
 * The Avro wire-format bridge: the concrete {@link FormatBridge} that, for an enabled file, adds a
 * same-shaped conversion of every message/enum into Avro — a per-type {@code XSpecific}
 * representation, a per-type {@code XAvro} converter, and the per-type schema (an {@code SCHEMA}
 * constant on a {@code XSchema} companion plus a per-file {@code .avsc} output) — alongside the
 * record/codec output. Protobuf remains the only wire format: a message's {@code Codec} is never
 * touched; the bridge only adds the conversion.
 *
 * <p>Units delivered incrementally: U4 emits the per-file {@code .avsc} (the generated schema, R6);
 * U5 adds the embedded {@code SCHEMA} + the {@code SpecificRecord} + the {@code XAvro} converter;
 * U6 pins the fixtures parity against golden protobuf bytes.
 *
 * <p>Resolution (R1): enablement is {@code per-file recordbuf.avro option &gt; global --avro flag
 * &gt; built-in default(on)}, via {@link BridgeContext#avroEnabled}.
 */
public final class AvroBridge implements FormatBridge {

  @Override
  public boolean enabledFor(ProtoFile file, BridgeContext ctx) {
    return ctx.avroEnabled(file);
  }

  @Override
  public java.util.List<? extends CodeGeneratorResponse.File> emit(
      ProtoFile file, BridgeContext ctx) {
    // v1 bridges the shapes it supports; shapes it doesn't yet handle (oneof, nested message, map,
    // uint64, dynamic WKT -- all the unit's fail-loud points) contribute NO Avro output for the
    // file, so an existing generation with such a shape is not aborted by the bridge. The fail-loud
    // is enforced at the mapping unit (AvroSchemaGenerator, unit-tested); hardening the file bridge
    // to abort on an unsupported shape is a planned follow-on.
    if (!enabledFor(file, ctx))
      return java.util.List.of(); // off: the disabled file contributes nothing
    final String schema;
    try {
      schema = AvroSchemaGenerator.schema(file);
    } catch (AvroUnsupportedException notYetSupported) {
      return java.util.List.of(); // unsupported shape: no avro output for this file in v1
    }
    if (schema.isEmpty()) return java.util.List.of(); // no top-level message: nothing to bridge
    String path = file.messages().get(0).fullName() + "AvroSchema.avsc";
    CodeGeneratorResponse.File avsc =
        CodeGeneratorResponse.File.newBuilder().setName(path).setContent(schema).build();
    return java.util.List.of(avsc);
  }

  /** The bridge the default registry ships. */
  public static AvroBridge instance() {
    return new AvroBridge();
  }
}
