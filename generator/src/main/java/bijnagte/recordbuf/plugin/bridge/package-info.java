/**
 * The extension-slot seam the {@code recordbuf} generator runs a non-protobuf wire-format bridge
 * through. A {@link bijnagte.recordbuf.plugin.bridge.FormatBridge} adds a same-shaped conversion of
 * every message/enum into its own format alongside the record/codec output — a per-type
 * representation, a per-type converter, and the per-type schema — with the record's {@code Codec}
 * (the protobuf wire format) untouched and no new user-facing flag per format.
 *
 * <p>Default-on, per-file toggle (R1): each bridge resolves its enablement per {@code .proto} file
 * as {@code per-file option > global flag > built-in default(on)}.
 *
 * <p>Default-on-non-null (JSpecify): every type / member here is non-null by default, with
 * {@code @Nullable} the single escape — the project's cross-project default.
 */
@NullMarked
package bijnagte.recordbuf.plugin.bridge;

import org.jspecify.annotations.NullMarked;
