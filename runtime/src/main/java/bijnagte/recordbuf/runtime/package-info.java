/**
 * The runtime the generated codecs lean on: the mechanical I/O-framing surface
 * (encode/decode scaffolding, packed buffer/drain, tag/{@code skipField}
 * primitives, and the per-wire default-value table) that a generated codec used
 * to spell by hand. A generated {@code Codec} stays thin — it keeps only its
 * per-message {@code switch(fieldNum)} dispatch and per-field read/write table —
 * and calls these helpers by fully-qualified name.
 *
 * <p>Generated code references this package as text (fully-qualified, no imports),
 * the same way it already names {@code com.google.protobuf.CodedOutputStream};
 * the generator does not link this module at build time.
 */
package bijnagte.recordbuf.runtime;
