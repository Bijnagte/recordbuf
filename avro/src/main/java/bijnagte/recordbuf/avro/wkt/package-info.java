/**
 * The well-known-types (WKT) bridge, hosted in {@code :recordbuf-avro} (R8). It is the canonical
 * source of the Avro schema fragments and value conversions for the recordbuf well-known types —
 * {@code Timestamp}, {@code Duration}, {@code FieldMask}, {@code NullValue}, the scalar wrappers,
 * and {@code Empty} — reused by every generated converter rather than re-open-coded.
 *
 * <p>WKTs map to <em>native</em> Avro logical types (KTD4): {@code Timestamp} → {@code
 * timestamp-micros}, {@code Duration} → the structured record {@code {seconds: long, nanos: int}}
 * (not the micros-only logical {@code duration}), a scalar wrapper → {@code [null, logical]},
 * {@code FieldMask} → {@code [null, string]}, {@code Empty} → an empty record. A Confluent/SR
 * dialect is not foreclosed (R23): the native logical types are a subset of Confluent's, so a later
 * schema-registry variant is a runtime addition on this bridge, not a regeneration.
 *
 * <p>The record layer is WKT-agnostic (R10): this bridge is the only place that knows about WKTs,
 * and it recognizes a WKT field by the WKT FQN the field's type carries, delegating to a bridge
 * fragment rather than the record learning WKTs.
 *
 * <p>Default-on-non-null (JSpecify): every type / member here is non-null by default, with
 * {@code @Nullable} the single escape — the project's cross-project default; a null {@code [null,
 * T]} is the {@code null} branch of the union, reached via a nullable field only.
 */
@NullMarked
package bijnagte.recordbuf.avro.wkt;

import org.jspecify.annotations.NullMarked;
