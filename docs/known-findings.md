# Known findings not in the gap ledger

Generator findings from building the compatibility suite that are not protobuf constructs. The
[gap ledger](../fixtures/src/test/resources/golden/gap-ledger.md) cannot carry them: its rows
name constructs of the vendored `test_messages_proto3.proto` and conformance scenarios, and each
row needs a `GAP(<id>)` marker in a proto. Each finding below is a recordbuf option or converter
behaviour, so it is listed here until it is fixed.

## `json_ignored` is not enforced on read

`(recordbuf.json_ignored) = true` keeps a field out of the JSON output. The Jackson golden of
`jackson-knobs/knobs-ignored-field` shows that, and it reads back with the field cleared because
the golden does not contain it. On read the field is still bound: Jackson 3 sets the `@JsonIgnore`'d
component from the input, so a JSON object with `"secret": "x"` reads back with `secret = "x"`. The
generated `Knobs` record puts that component in its canonical constructor, with `@JsonIgnore` on
the parameter and no `@JsonCreator`; that is the likely cause, but it is not confirmed by a run.
No test reads an input that carries an ignored property, so no test catches this.

The field stays on the wire either way (`JacksonKnobsTest.jsonIgnored_staysOnTheWire`).

## Interop converter for singular proto3 enum fields

The generated `Interop` converter dereferences a singular enum field, which the record holds as
`@Nullable`, so NullAway rejects the converter at build time. Mapping an absent enum would also
produce the enum's zero value, not an absent field. A message with a singular enum field therefore
cannot carry `(recordbuf.protobuf_java)`.

`canonical_format.proto` `Sample` (its `kind` field) is kept off Interop for this reason. Its proto3
JSON goldens are checked structurally by `ProtoCanonicalJsonTest`, not as matrix cases (see the
note in `CanonicalFormatCases`). The `interop-all-types` row of the ledger lists other Interop
failures on `TestAllTypesProto3` and does not list this one.
