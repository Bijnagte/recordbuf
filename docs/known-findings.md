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

## `ProtobufMarshaller.of` does not accept a generated `fromBytes`

`ProtobufMarshaller.of(Function<byte[], T>)` is documented as taking the record's generated
`fromBytes`. A generated `fromBytes` declares `throws IOException`, so `Pet::fromBytes` does not
compile as a `Function`. The runtime's own test passes because its `Point` type has a decoder that
does not throw. The pet store sample wraps the decoder in its own `ServiceDefinitions.Decoder` that
rethrows as `UncheckedIOException` (`samples/petstore`). A decoder type that may throw `IOException`
would remove the wrapper.

## Avro class trust is the consumer's job

Reading an Avro record back through a generated `<Name>Avro.fromByteBuffer` fails with a
`SecurityException` ("This class is not trusted to be included in Avro schemas") until the generated
package is trusted, either with `-Dorg.apache.avro.SERIALIZABLE_PACKAGES=<package>` or through
`ClassSecurityValidator.setGlobal`. The README's *Bindings* section does not mention it. The fixtures
set the system property in their test tasks, and the pet store sample sets the validator in code
(`AvroEventLog`).

