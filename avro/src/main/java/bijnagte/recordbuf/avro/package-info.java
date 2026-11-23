/**
 * The Avro bridge: the alternative-wire-format conversion layer the {@code recordbuf} generator
 * optionally emits when a type's file-level {@code recordbuf.avro} option is enabled (or the
 * plugin's per-{@code .proto}-invocation {@code --avro} flag is on). It is an <em>extension slot,
 * not a rewrite</em> of a message's codec: protobuf remains the only wire format (the generated
 * {@code Codec} is untouched), and the bridge adds a per-type Avro representation + a per-type
 * converter ({@code XAvro}) that round-trips a record through an Avro data structure.
 *
 * <p>This module hosts the hand-written bridge runtime — the extension seam ({@code
 * bridge.FormatBridge}, its {@code bridge.BridgeRegistry}), the Avro bridge ({@code
 * bridge.AvroBridge}), and the WKT bridge ({@code wkt.*}) — that generated {@code XAvro}/{@code
 * XSpecific} classes call into. The generator itself does not link this module at build time;
 * generated code references these types by fully-qualified name, the same way it already names the
 * {@code runtime} helpers.
 *
 * <p>Default-on, per-file toggle (R1): the bridge is on by default and gated per {@code .proto}
 * file via {@code option (recordbuf.avro)=...} (an {@code enum} of {@code
 * RECORDBUF_AVRO_NONE/ON/OFF}) and the {@code --avro} plugin flag; resolution is {@code file > flag
 * > default(on)}.
 *
 * <p>Default-on-non-null (JSpecify): every type / member here is non-null by default; individual
 * {@code @Nullable} escapes are annotated explicitly. This is the project's cross-project default
 * and the same contract the generator's own packages carry.
 */
@NullMarked
package bijnagte.recordbuf.avro;

import org.jspecify.annotations.NullMarked;
