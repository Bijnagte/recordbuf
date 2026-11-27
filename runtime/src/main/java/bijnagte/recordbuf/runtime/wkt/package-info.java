/**
 * The well-known-type (WKT) wire scaffolding a generated codec leans on for a {@code
 * google.protobuf.*} field. A WKT field is, on the wire, an ordinary length-delimited nested
 * message; this helper owns the per-WKT inner-frame encode/decode so each generated codec emits one
 * line (e.g. {@code out.writeByteArray(num, Wkt.timestamp(v.ts()))}) instead of re-spelling the
 * nested framing — the same thinness the codec gains from {@link
 * bijnagte.recordbuf.runtime.Decoder} and {@link bijnagte.recordbuf.runtime.Wire}.
 *
 * <p>Structured WKTs ({@code Timestamp}, {@code Duration}) map to {@link java.time.Instant} /
 * {@link java.time.Duration}; a {@code FieldMask} maps to {@link
 * bijnagte.recordbuf.runtime.FieldMask}; an {@code Empty} maps to {@link
 * bijnagte.recordbuf.runtime.Empty}; and each scalar wrapper ({@code *Value}) maps to the boxed
 * primitive it wraps, framed on the wire exactly as that wrapper is on the wire.
 *
 * <p>Default-on-non-null (JSpecify).
 */
@NullMarked
package bijnagte.recordbuf.runtime.wkt;

import org.jspecify.annotations.NullMarked;
