# Jackson JSON support for generated records — implementation plan

**Product contract** — authored in the 2026-11-20 ce-brainstorm session; that draft is superseded by this
document, and §1 restates the contract as the single source of truth
**Date:** 2026-11-20 · **Status:** IMPLEMENTED (migrated to the Jackson 3.x line 2026-11-21; see Current state)
**Related plans:** `2026-11-17-012` Avro structural twin (landed; source of the shelved envelope),
`2026-11-07-001` native WKT (dynamic-WKT fail-loud it lifts), `2026-11-06-001` Avro schema-jackson
refactor (the generator already runs on a Jackson tree for `.avsc` — R10 rides the same seam).

---
## Current state

Implemented, then migrated onto the **Jackson 3.x** line (2026-11-21): the catalog pins
`jackson = "3.1.8"` via `tools.jackson:jackson-bom`, and a separate `jackson-annotations = "2.21"`
version keeps the `com.fasterxml.jackson` annotations jar (the 3.x line stays on its own 2.21.x
cadence; the handoff's `2.21.0` coordinate does not exist on Central). Consequences of the split
world: `:recordbuf-json` and the generator run on `tools.jackson` (core/databind), while the
**generated code and `JsonPayload` keep the `com.fasterxml.jackson.annotation` annotations** — a
deliberate decision, since databind 3.x reads that very package (verified from its
`AnnotationIntrospector`). The generated `tools.jackson.databind.JsonNode`/`ObjectNode`/`ArrayNode`/
`NullNode` references live on the 3.x `tools.*` package. `JsonNode.TextNode` is `StringNode` in
3.x, `registerModule` is gone in favor of
`om.rebuild().addModule(m).build()`, `JsonNode.fields()` is `properties()`, and
`writeXField` conveniences are `writeName(...).writeX(...)`.

The migration also fixed a latent bug 2.x masked: the generator's Timestamp `.avsc` fragment
literal carried a stray trailing `}` that 2.x `readTree` silently tolerated and 3.x rejects —
removed in `AvroSchemaGenerator`.

Note: P3's original "bump to 2.23.0" pin is superseded by this 3.x line; the
`2026-11-06-001` Avro schema-jackson plan's 2.22.1-BOM-alignment rationale is likewise historical.

## 1. Product contract

From the brainstorm. `R##` IDs are stable references for the units below.

- **R1 — Per-file opt-in.** A `recordbuf.jackson` file option (bool, proto tag 50004) is the single
  gate. Opted-in file ⇒ Jackson JSON surface on its records; every other file ⇒ today's output.
- **R2 — Dependency rule.** Opt-in changes what generated code depends on: records carry Jackson
  annotations and the module pulls in Jackson. Files that don't opt in keep byte-identical,
  dependency-free output.
- **R3 — Dynamic WKTs.** `Struct` → `ObjectNode`, `Value` → `JsonNode`, `ListValue` →
  `ArrayNode`, `Any` → `JsonNode` record fields (lifts the R11 fail-loud for opted-in files
  only). `Any` retains its type URL through the carrier; the node holds the unpacked value.
- **R4 — Bean-style JSON.** Field JSON names default to the proto wire name (snake_case) via
  `@JsonProperty`; record Java names are unchanged. null-valued optional fields serialize as
  explicit JSON `null`.
- **R5 — Oneofs.** Flat: the set of member properties at top level plus a `<oneofName>Type`
  discriminator property whose value is the chosen member's name. The per-member wrapper records
  are unwrapped (no `choice` nesting). Overridable per oneof (v1: discriminator property name,
  tag 50008).
- **R6 — Unknown JSON properties retained** via any-getter/any-setter (wire-honest parity with
  the binary unknown-field carrier).
- **R7 — Wire invariant.** Protobuf binary framing is untouched; the codec is the only binary
  path. The JSON path is an orthogonal, additive surface.
- **R8 — Temporal shape.** `Timestamp`/`Duration` fields default to ISO-8601 strings; a
  per-field `recordbuf.jackson_format` option (tag 50005, `"json"` | `"canonical"`) selects
  proto3-canonical rendering instead.
- **R9 — Default-off invariance.** For any file without the option, generated output is
  byte-identical to today's and free of any Jackson import.
- **R10 — Jackson × Avro.** A file enabling BOTH bridges renders dynamic WKT fields as an opaque
  envelope in the Avro twin (the structural-twin plan's shelved envelope returns); all other
  fields keep the structural-twin mapping.

## 2. Scope decisions

**In (v1):** the file toggle; the `:recordbuf-json` runtime module (Jackson payload/WKT
conversion + temporal codecs); node-typed dynamic WKT fields; `@JsonProperty`/`@JsonIgnore`
annotation emission; any-getter/any-setter retention; flat+discriminator oneofs with the
50008 name knob; the 50005 temporal knob (`json` ISO / `canonical`); the Avro envelope;
options-proto declarations for all six new tags (v2 ones declared, not wired); fixture files +
acceptance tests; CONCEPTS.md refresh.

**Out (v2+):** field rename (`json_name`, 50006) and ignore (`json_ignored`, 50007) knobs
(declared in the proto, unwired); per-oneof discriminator value style (`json_oneof_values`,
50009); `Any` type-URL registration for re-resolution; binary unknown *fields* re-emitted
through JSON (undefined in v1 — a record decoded from binary carrying the binary carrier
serializes known fields + captured JSON extras, dropping binary-only unknown fields, matching
protobuf canonical JSON behavior); other bridge formats; a Jackson 3.x (`tools.jackson`) line;
the `-Werror` gate re-enable (a separate checkpoint — it is currently OFF in the root
`build.gradle.kts` for the in-flight functional checkpoint).

## 3. Codebase context (verified against the tree)

- **Modules** (flat role-named dirs, `settings.gradle.kts`): `:recordbuf-generator` (`generator/`),
  `:recordbuf-runtime` (`runtime/`), `:recordbuf-options` (`options/`, proto only),
  `:recordbuf-codegen` (`codegen/`), `:recordbuf-avro` (`avro/`), `:recordbuf-fixtures`
  (`fixtures/`). A new `:recordbuf-json` → `json/` follows the same pattern (co-versioned from the
  root `subprojects {}` block, KTD3).
- **Options proto** (`options/src/main/proto/recordbuf_options.proto`): `recordbuf.protobuf_java`
  (50001, file), `recordbuf.avro` (50002, file), `recordbuf.java_interfaces` (50003, message); all
  `@edgenu.ambrosia` + `buildable=false`. New options 50004–50009 extend the same namespace.
- **Model loading** (`generator/.../model/ModelLoader.java`): per-option readers
  `protobufJavaFor` / `avroToggleFor` / `interfacesFor` each prefer the COMPILED path (a resolved
  extension arrives as an unknown field at its tag) with an `UninterpretedOption` dotted-name
  fallback. `referenceType()` resolves message/enum field Java types; a dynamic WKT fails loud
  via `Objects.requireNonNull(wkt.javaType(), ...)` — the seam this plan lifts for opted-in
  files.
- **WKT registry** (`generator/.../types/WellKnownTypes.java`): `Entry(fqn, Kind, javaType,
  wire, zeroDefault)`, `Kind {STRUCTURED, WRAPPER, BYTES_WRAPPER, DYNAMIC_UNSUPPORTED}`,
  `isDynamic()`.
- **Codegen AST** (`codegen/.../source/`): `Annotation(TypeRef, List<Arg>)` + `Arg(name, values)`
  render `@X("v")` / `@X(name = "v")` with the renderer owning quoting and imports — every Jackson
  annotation the contract needs is expressible without AST changes.
- **Record writer** (`generator/.../writer/RecordSourceMapping.java`): components from fields;
  oneof = `@Nullable` record component of a nested sealed interface (`extends Unwrapable<Object>`)
  whose per-member records are `<Cap>Value(<T> value)` implementing the interface (`emitOneof`);
  the unknown-field carrier is a record component (`:recordbuf-runtime` `UnknownFields`).
- **Codec writer** (`generator/.../writer/CodecSourceMapping.java`): WKT wire framing delegates to
  `:recordbuf-runtime` `runtime.wkt.Wkt`; dynamic WKTs currently never reach it (fail loud earlier,
  in `referenceType`).
- **Avro bridge** (landed structural twin, `generator/.../writer/AvroSchemaGenerator.java` +
  `AvroConverterSourceMapping.java` + `:recordbuf-avro`): all-shapes mapping (oneof = null-first
  union with a shared value wrapper, maps = pair-entry records, enums → string, uint64 → long,
  unknown carrier 1:1 array); dynamic WKTs fail loud at registry/field level — the seam R10
  replaces for opted-in files. The generator builds `.avsc` as a Jackson JSON tree
  (`2026-11-06-001`), so a Jackson pin already exists in the catalog.
- **Build gates (root `build.gradle.kts`)**: `withPlugin("java")` auto-applies Spotless +
  Checkstyle + `-Werror` (the last currently commented OFF) to every Java subproject — a new
  module inherits all three. Error Prone/NullAway is wired per module (mirror `:recordbuf-runtime`'s
  block; `OnlyNullMarked=true`, no `JSpecifyMode`).
- **Fixtures** (`fixtures/build.gradle`, Groovy): two generate tasks — `generateProto` (main:
  record → `.rec` sibling, protobuf-java ref at natural pkg, options proto on include dirs) and
  `generateTestProto` (iface_marker: no java builtin, WKT dir extracted from the managed
  protobuf-java jar). EP exclusions trim Avro-specific generated records; `.rec` output stays in
  full EP scope. Test block trusts the fixture package for Avro `SERIALIZABLE_PACKAGES`.
- **Jackson pin** (`gradle/libs.versions.toml`): `jackson = "2.22.1"` already exists (the
  generator's `.avsc` tree build; `jackson-databind` is otherwise pulled transitively by
  avro-compiler).

## 4. Plan decisions (resolved this session)

- **P1 — Envelope encoding (R10).** The envelope is a generated Avro record
  `RecordbufDynamicWkt { format: string, contentType: string, payload: string }`: `format` =
  `"json"`, `contentType` = the WKT proto FQN (`google.protobuf.Value`, …; for `Any`, the
  `type_url`), `payload` = the JSON text of the node. `JsonNode → string → JsonNode` is
  content-lossless (Jackson node equality is content-based). This is exactly the structural-twin
  plan's shelved `{format, content-type, payload}` shape — it returns unmodified.
- **P2 — Oneof mechanism (R5).** Serialization: `@JsonUnwrapped` on the oneof component,
  `@JsonProperty("<memberName>")` on each member wrapper's `value` component, plus a generated
  `<oneofName>Type()` discriminator getter. Deserialization: a generated `@JsonCreator` static
  factory (discriminator param + one param per member name + a `@JsonAnySetter`
  `Map<String, JsonNode>` sponge for the remaining fields; constructs the wrapper from the
  discriminator). If `@JsonUnwrapped` proves unreliable against the sealed-interface property on
  the pinned Jackson (U7 verification), fall back to a generated serializer companion — the JSON
  shape (the contract) is identical either way.
- **P3 — Jackson pin.** Bump the existing catalog `jackson` pin `2.22.1 → 2.23.0` (latest 2.x GA,
  verified on Maven Central 2026-11-08). One pin serves the generator, `:recordbuf-json`, and the
  fixtures; stays on the 2.x line (consumer-classpath ubiquity) — Jackson 3.x is a new
  coordinate/major and out of scope.
- **P4 — `:recordbuf-json` module contents.** `api jackson-databind`; `implementation
  protobuf-java` (the WKT *messages* the conversion helper uses); `api jspecify`; EP + NullAway
  (`@NullMarked` package, mirroring `:recordbuf-runtime`). Hosts: `JsonPayload` (shared
  `ObjectMapper`; `toJson(JsonNode)` / `fromJson(String)` for the P1 envelope), `DynamicWkt`
  (codec-side WKT ↔ node conversion built on the protobuf-java WKT messages — already on every
  consumer classpath because the codec needs it), and `JsonTemporal` (ISO + proto3-canonical
  serializers/deserializers for `Instant`/`Duration` — so generated temporal fields need only
  two annotations, no generated codec code).
- **P5 — Options tags.** 50004 `recordbuf.jackson` (file, bool, wired); 50005
  `recordbuf.jackson_format` (field, string, wired); 50006 `recordbuf.json_name` (field, string,
  reserved v2); 50007 `recordbuf.json_ignored` (field, bool, reserved v2); 50008
  `recordbuf.json_oneof` (oneof, string, wired); 50009 `recordbuf.json_oneof_values` (oneof,
  string, reserved v2). Reserved options are declared in the proto (forward-compat, protoc
  round-trips them) but the generator ignores them in v1.

## 5. Research sources

- Brainstorm contract: the 2026-11-20 ce-brainstorm session (its draft lived at this file path and
  was superseded by this document; its R1–R10 + call-outs are restated in §1 and §4).
- Code (read this session): `options/src/main/proto/recordbuf_options.proto`,
  `generator/.../model/ModelLoader.java`, `generator/.../types/WellKnownTypes.java`,
  `codegen/.../source/{Annotation,Arg}.java`, `generator/.../writer/{RecordSourceMapping,
  AvroSchemaGenerator}.java`, `bridge/AvroBridge.java`, `runtime/.../Unwrapable.java`,
  `fixtures/build.gradle`, root `build.gradle.kts`, `gradle/libs.versions.toml`.
- Durable docs: `docs/solutions/design-patterns/{unknown-field-capture-reemit,
  native-well-known-type-generation}.md`, `docs/plans/2026-11-17-012-feature-avro-structural-twin-plan.md`
  (envelope shelve, line 37), `docs/plans/2026-11-07-001-feat-well-known-types-native-plan.md`
  (R11 fail-loud + R8 WKT avro mapping), `CONCEPTS.md` (Dynamic WKT, Opaque envelope), AGENTS.md
  (tooling gates).
- Web: Maven Central `jackson-databind` metadata (latest 2.x GA = 2.23.0).

## 6. Workstream layout

Ten checkpoints, each one small feature slice with its own verification; every unit's work is
visible in the generated output or a passing test before the next begins.

| Unit | Checkpoint delivery | Depends on |
|------|--------------------|------------|
| U1 | The six options exist and resolve | — |
| U2 | The model carries a per-file `JacksonConfig` | U1 |
| U3 | `:recordbuf-json` module builds (payload + WKT + temporal helpers) | — |
| U4 | Opted-in records carry `@JsonProperty`/`@JsonIgnore`; off files byte-identical | U2, U3 |
| U5 | Dynamic WKT fields are node-typed and wire-round-trip | U2, U3 |
| U6 | Unknown JSON properties survive a JSON round-trip | U4 |
| U7 | Oneofs render flat with the `…Type` discriminator | U4 |
| U8 | Temporal fields: ISO default + `canonical` knob | U3, U4 |
| U9 | Jackson×Avro files render dynamic WKTs as the envelope | U3, U5 |
| U10 | Fixture files + AE1–AE5 + CONCEPTS refresh | U1–U9 |

## 7. Units

### U1 — Options proto: declare the Jackson option set (M)

- **What:** add the six options from P5 to `options/src/main/proto/recordbuf_options.proto`,
  matching the existing entries' `@edgenu.ambrosia` + `buildable=false` style and the
  "file / field / oneof" `option` scopes. Reserved-v2 entries carry a comment marking them
  declared-but-unwired.
- **Files:** `options/src/main/proto/recordbuf_options.proto` (+ its javadoc block header).
- **Verification:** protoc compiles the options proto (the fixtures' two generate tasks already
  include it — `./gradlew :recordbuf-fixtures:generateProto`); a generator unit test hands
  `ModelLoader` a hand-constructed `FileDescriptorProto` carrying each new option through the
  UNINTERPRETED path (the compiled path needs protoc, covered in U10) and asserts the raw values
  are read.

### U2 — Model + loader: the per-file `JacksonConfig` (M)

- **What:** a `JacksonConfig` model record (`plugin.model`) holding, per file: the toggle (bool),
  the field-knob map (field name → `jackson_format` value), and the oneof-knob map (oneof name →
  `json_oneof` discriminator name). `ProtoFile` gains `@Nullable JacksonConfig` (null = option
  absent; a distinct empty-but-present config = explicitly on with defaults). `ModelLoader` gains
  readers mirroring `protobufJavaFor`/`avroToggleFor`: compiled-tag-first + uninterpreted
  fallback, validating `jackson_format` ∈ {`json`, `canonical`} at load (unknown value fails
  loud with the option name in the message). `WellKnownTypes` gains the dynamic→node table
  (Struct→`ObjectNode`, Value→`JsonNode`, ListValue→`ArrayNode`, Any→`JsonNode` as
  `TypeRef`s) plus a `nodeType()` lookup, leaving `isDynamic()`/fail-loud untouched.
- **Files:** `plugin/model/{ProtoFile,JacksonConfig (new)}.java`, `plugin/model/ModelLoader.java`,
  `plugin/types/WellKnownTypes.java`, tests.
- **Verification:** `ModelLoader` unit tests: toggle absent ⇒ null config; file bool on ⇒ config
  present; field/oneof knobs via both paths; invalid `jackson_format` fails loud; `nodeType()`
  returns the four node `TypeRef`s and null for non-dynamic WKTs.

### U3 — The `:recordbuf-json` runtime module (M)

- **What:** new module `json/` (role-named dir): `settings.gradle.kts` include + root
  `subprojects {}` co-version; `java-library` with Java 25 toolchain, `api
  libs.jackson.databind`, `implementation libs.protobuf.java`, `api jspecify`, test dep
  JUnit; EP + NullAway wired exactly as `:recordbuf-runtime` (`OnlyNullMarked=true`, no
  `JSpecifyMode`); `@NullMarked` `package-info.java`. Spotless/Checkstyle inherit from the root
  `withPlugin("java")` blocks. Contents per P4: `JsonPayload` (one shared static `ObjectMapper`;
  `toJson(JsonNode)`, `JsonNode fromJson(String)`), `DynamicWkt` (per-WKT `JsonNode
  toNode(CodedInputStream)` / `void writeTo(CodedOutputStream, JsonNode)` — parses the WKT frame
  into the protobuf-java `Value`/`Struct`/`Any`/`NullValue`/`FieldMessage`/`ListValue` messages,
  converts recursively (kind oneof → `TextNode`/`DoubleNode`/`BooleanNode`/`NullNode`/
  `ObjectNode`/`ArrayNode`), and writes back with correct oneof kind selection), `JsonTemporal`
  (four static `JsonSerializer`/`JsonDeserializer` pairs: Instant/Duration × ISO/canonical;
  canonical = 3/6/9-digit fractional + `Z`, Duration `Ns` text).
- **Catalog:** bump `jackson = "2.23.0"` (P3).
- **Verification:** `./gradlew :recordbuf-json:build` green (EP/NullAway/Spotless/Checkstyle
  all run). Unit tests: Value/Struct/ListValue/Any ↔ node round-trips through real wire bytes
  (`writeTo` → `toByteString` → `parseFrom` → `toNode`); nested Struct/ListValue trees; `Any`
  type-URL preservation; `JsonPayload` string round-trip; canonical/ISO render + parse of known
  `Instant`/`Duration` values.

### U4 — Records: Jackson annotations on the bean surface (M)

- **What:** `RecordSourceMapping` consults `ProtoFile.jacksonConfig`: when present, every record
  component gets `@JsonProperty("<wireName>")` (the proto snake_case name, R4) and the
  unknown-field carrier component gets `@JsonIgnore`. Enums, maps, repeated, message and scalar
  fields need no other annotation in this unit. Emits via the existing `Annotation`/`Arg` AST;
  the renderer imports `com.fasterxml.jackson.annotation.*`. When the config is absent, emission
  is bit-for-bit unchanged (R9).
- **Files:** `generator/.../writer/RecordSourceMapping.java` (+ a small annotation-emission helper
  if the writer structure wants one), tests.
- **Verification:** generator unit tests: a synthetic jackson-on model renders the annotations in
  the right positions with the wire names; the identical jackson-off model renders byte-identical
  output (golden comparison against the pre-change rendering).

### U5 — Dynamic WKT fields: node-typed, wire round-trip (M)

- **What:** `ModelLoader.referenceType()` — when the file's config is present and the WKT is
  dynamic, return the U2 node `TypeRef` instead of failing loud (jackson-off keeps the exact
  fail-loud); repeated dynamic WKTs → `List<Node>`. `CodecSourceMapping` — a dynamic WKT field's
  encode/decode delegates to `:recordbuf-json` `DynamicWkt` (length-delimited WKT frame in/out,
  node built from / written through the protobuf-java WKT messages) instead of failing; presence
  follows submessage rules (`@Nullable` field, null when absent). `WellKnownTypes` gains a
  `dynamicEntry(fqn)` accessor so the codec writer can find the WKT.
- **Files:** `generator/.../model/ModelLoader.java`, `generator/.../writer/CodecSourceMapping.java`,
  `generator/.../types/WellKnownTypes.java`, tests.
- **Verification:** generator unit tests: emitted decode/encode call `DynamicWkt` for all four
  dynamic WKTs (singular + repeated); jackson-off dynamic WKT still fails loud with today's
  message. End-to-end bytes → record → bytes parity is proven in U10 (AE4).

### U6 — Unknown JSON properties: retained via any-getter/any-setter (M)

- **What:** on opted-in records, the retained unknown JSON properties are a record **component**
  `Map<String, JsonNode> jsonExtras` annotated `@JsonIgnore @JsonAnyGetter @JsonAnySetter`
  (plus `@Nullable` — Jackson passes null when no unknown properties were present) with a
  **compact (no-parameter) constructor** that normalizes every construction path — Jackson's
  implicit creator, direct `new`, and the builder (R1): null singular scalars are re-bound to
  their proto3 defaults (an explicit JSON null or a missing property; fields whose builder
  default is `null` — presence-tracked — and Java **primitive** components — never null,
  Jackson maps onto them — are skipped), the carrier `null` → `empty()`, and null extras → a
  fresh mutable map. The user-defined compact constructor **must be `public`** (javac access
  rule vs. the canonical one) and carries a generated Javadoc (the fixtures run EP
  `NotJavadoc` + `-Werror`). **No accessor methods** — Jackson honors the annotations on the
  record parameter/accessor: the map projects flat onto the JSON object on serialize and
  absorbs unrecognized properties into itself on deserialize (R6). The builder carries a
  matching `@Nullable` storage field + setter (defensively copied in the copy constructor),
  the codec's decode passes a `null` extras argument (a decoded record has an empty map — the
  retained bytes still round-trip through the binary `UnknownFields` carrier, R7), and its
  encode folds the map into the carrier via the `:recordbuf-json` `JsonExtras.fold` helper
  (idempotent: entries already in the carrier are not re-added).
  - **Known gap — `bytes` value mapping (unowned):** the plan never assigns the proto3-JSON
    base64 string mapping for `bytes` fields, and a plain `ObjectMapper` serializes the
    `Bytes` object as a Bean (`{"empty":true}`). A Jackson module or per-field (de)serializer
    is needed — **no unit currently owns it** (U8 is temporal-only); extend U8 or add a
    `:recordbuf-json` module.
    bytes field's Jackson round-trip is unsupported (U10 AE3 must not include one).
- **Files:** `generator/.../writer/RecordSourceMapping.java` (component + compact ctor +
  builder + `TypeRefs.JSON_NODE_MAP`), `codegen/.../source/Constructor.java` +
  `codegen/.../SourceRenderer.java` (the `compact` flag — public no-parameter constructor),
  `generator/.../writer/CodecSourceMapping.java` (decode
  null arg + encode fold), `json/.../json/JsonExtras.java` (the fold helper — a `:recordbuf-json`
  class, not a new `:recordbuf-runtime` class).
- **Verification:** generator unit tests: emission present iff opted in; jackson-off output
  byte-identical to the pre-feature output; `:recordbuf-json` unit tests for the fold
  (idempotency, varint framing); round-trip via a real `ObjectMapper` is proven in U10 (AE3).

### U7 — Oneofs: flat + discriminator (M)

- **What:** per P2. On opted-in records with oneofs: the oneof component gets `@JsonUnwrapped`
  (+ `@JsonIgnore`-style suppression of the property itself), each member wrapper's `value`
  component gets `@JsonProperty("<memberName>")`, the record gains a generated
  `<oneofName>Type()` getter (null when the oneof is unset; the chosen member's name otherwise,
  camelCase-ized member name as the brainstorm default), and a generated `@JsonCreator` static
  factory binds the discriminator + member params + the U6 any-sponge to reconstruct the record.
  The 50008 knob overrides the discriminator property name. The codec is untouched (R7).
- **Files:** `generator/.../writer/RecordSourceMapping.java`, U2 config plumbing for the oneof
  knob, tests.
- **Verification:** generator unit tests: annotation/creator shape for a 3-member oneof (scalar,
  enum, message members) with and without the 50008 override; the pinned-Jackson round-trip
  (`{"a":"blue","choiceType":"a"}` ↔ record, plus unset and member-mismatch cases) is proven in
  U10 (AE2). If `@JsonUnwrapped` misbehaves on the sealed-interface property, switch the
  serialization side to a generated serializer companion (P2 fallback) — the emitted JSON shape
  must be byte-identical between the two mechanisms.

### U8 — Temporal fields: ISO default + canonical knob (M)

- **What:** on opted-in records, `Instant`/`Duration` components (from `Timestamp`/`Duration`
  WKTs — the structured WKTs that are *always* native) get
  `@JsonSerialize(using = …)`/`@JsonDeserialize(using = …)` referencing `:recordbuf-json`
  `JsonTemporal`: ISO pair by default, canonical pair when the field's 50005 `jackson_format`
  says `"canonical"`. Both directions use the U3 module classes — no generated codec code.
- **Files:** `generator/.../writer/RecordSourceMapping.java`, tests.
- **Verification:** generator unit tests: ISO annotations by default, canonical under the knob;
  `:recordbuf-json` unit tests (U3) cover the render/parse behavior incl. the canonical
  3/6/9-group and `Ns` formats; fixture round-trip in U10 (AE4).

### U9 — Jackson × Avro: the opaque envelope (M)

- **What:** in `AvroSchemaGenerator` + `AvroConverterSourceMapping`, a field whose record Java
  type is a Jackson node type (only possible when the file is jackson-on) maps to the P1
  envelope: an always-present (repeated ⇒ array of) `RecordbufDynamicWkt` record in the schema;
  the converter builds/reads it via `JsonPayload` (`contentType` = the WKT FQN, or the `Any`
  `type_url`). The dynamic-WKT fail-loud in the schema generator stays for jackson-off files.
  The envelope `.avsc` record is emitted by the bridge like its other shared carriers (next to
  the oneof-value wrapper emission); the generated converter gains the `:recordbuf-json` import
  only in jackson+avro files.
- **Files:** `generator/.../writer/AvroSchemaGenerator.java`,
  `generator/.../writer/AvroConverterSourceMapping.java`, `generator/.../bridge/AvroBridge.java`
  (the envelope schema constant), tests.
- **Verification:** generator unit tests: jackson+avro file with a `Value` field ⇒ envelope in
  the schema + converter emits/reads it; the same file jackson-off ⇒ today's file-level
  fail-loud skip, unchanged. Avro-bytes round-trip of the envelope is proven in U10 (AE4).

### U10 — Fixtures: opt-in files + acceptance gates + docs (M)

- **What:** new fixture protos (test source set, iface_marker family): (a) a jackson-on file
  exercising every shape — scalars, optional/`@Nullable`, oneof (3 members incl. a message
  member), each of the four dynamic WKTs (singular + a repeated `Value`), `Timestamp`/`Duration`
  (default + `canonical`), a field with an unknown-property payload; (b) a jackson+avro variant
  of it for the envelope; (c) a jackson-off twin of (a) for the invariance gate. `fixtures` gains
  `jackson-databind` + `:recordbuf-json` (+ `jspecify`) test dependencies. JUnit tests run the
  generated code through a real `ObjectMapper` and `Codec`.
- **Files:** `fixtures/src/test/proto/*.proto` (2–3 files), `fixtures/src/test/java/...` (2–3
  test classes), `fixtures/build.gradle`, `CONCEPTS.md` (add the Jackson JSON surface + the
  Dynamic WKT / Opaque envelope entries per §12).
- **Verification:** AE1–AE5 (below) + `./gradlew build` fully green (Spotless, Checkstyle, EP,
  tests) + `spotlessApply` left nothing to do.

## 8. Dependencies and ordering

```
U1 ─→ U2 ─┬─→ U4 ─┬─→ U6
          │       ├─→ U7
          │       ├─→ U8
U3 ───────┼─→ U5 ─┴─→ U9
          └──────────────┬
                         └─→ U10
```

U3 is independent of U1/U2 and can start in parallel. U6–U9 are independent of each other once
U4/U5 land. U10 is the integration gate: everything else is unit-level, U10 is the end-to-end
proving unit.

## 9. Acceptance criteria

- **AE1 — Dependency rule (R2/R9).** An opted-in fixture record compiles with Jackson on the
  classpath; a jackson-off twin of the same proto generates **byte-identical** output with zero
  Jackson imports (golden diff).
- **AE2 — Oneof (R5).** `{"id":7,"a":"blue","choiceType":"a"}` ↔ record round-trips; a message
  member serializes as `{"id":7,"b":{…fields…},"choiceType":"b"}`; unset oneof emits neither
  member properties nor the discriminator; a `choiceType`/member mismatch fails with a clear
  Jackson error. The 50008 override renames the discriminator.
- **AE3 — Unknown properties (R6).** Parse JSON containing an unknown property → record;
  re-serialize → the property is present with its value and type intact.
- **AE4 — Round-trips (R3/R7/R8/R10).** (a) bytes → record → bytes parity for a message with
  node-typed `Value`/`Struct`/`ListValue`/`Any` fields (wire bytes re-encode; node content
  preserved); (b) JSON → record → JSON content-equal, incl. temporal fields in both formats;
  (c) for the jackson+avro fixture: record → Avro bytes → record with the node content preserved
  through the envelope.
- **AE5 — Default-off (R9).** `./gradlew build` green; no non-opted-in generated file contains a
  `com.fasterxml` import.

## 10. Verification strategy

- **Unit level (U1–U9):** generator/model/writer tests with synthetic models + hand-constructed
  `FileDescriptorProto`s (the established pattern for the uninterpreted-option path);
  `:recordbuf-json` tests on real wire bytes (U3); render goldens for annotation emission.
- **Integration level (U10):** the fixtures module is the end-to-end harness — `installDist` →
  protoc → generated `.rec` code compiled with EP/NullAway in full scope → JUnit round-trips
  through a real `ObjectMapper` (pinned 2.23.0) and the generated `Codec`/Avro converters.
- **Every checkpoint:** `./gradlew build` green before the next unit starts (the user's
  checkpoint rule: each unit is a shippable state).

## 11. Risks and pitfalls

- **`@JsonUnwrapped` on a sealed-interface record component** — Jackson's unwrapping has known
  seams with dynamic/abstract property types and with creator binding. Mitigation: P2's
  `@JsonCreator` factory owns deserialization anyway; the only unwrapped-usage is serialization,
  verified against the pinned 2.23.0 in U7 before U10 bets on it; fallback = generated
  serializer companion (contract-invariant).
- **Any-getter/setter × `@JsonCreator` interaction** — the U6 sponge must stay in scope when the
  U7 factory takes over construction (a creator with an any-setter param is supported, but the
  factory's explicit member params must shadow the member properties first). Verified in U7/U10.
- **NullAway scope on generated Jackson code** — the `.rec` output is `@NullMarked` (existing
  package-info); `@Nullable JsonNode` fields, the `@JsonAnySetter` map, and the envelope accessor
  nulls must be annotated in the *generator* so generated code passes EP/NullAway in the fixtures
  (full scope, per the fixtures' existing config).
- **EP exclusions in `fixtures/build.gradle`** — any new generated file *pattern* (e.g. a
  generated oneof-codec companion, if the P2 fallback fires) must be checked against the existing
  `excludedPaths` regexes; the `.rec` package stays in full EP scope by design.
- **`-Werror` is OFF** in the root build right now (in-flight functional checkpoint) — this plan
  must not re-enable it and must not add *new* javac/EP warnings to any module; re-enable stays a
  separate checkpoint.
- **Avro `SERIALIZABLE_PACKAGES`** — the envelope record is SpecificCompiler-generated in the
  fixture package, already covered by the existing trust line; a new package would not be.
- **Jackson pin bump 2.22.1 → 2.23.0** touches the generator's existing `.avsc` tree build
  (P3). Jackson 2.x is binary-compatible across patch/minor; the generator's `build` (incl. its
  tests) is the check. If it churns, revert P3 to "keep 2.22.1" — `:recordbuf-json` and the
  fixtures can share either.
- **Canonical temporal formatting** must match the proto3 JSON spec exactly (3/6/9-group
  fractional, `Z`, `Ns` for Duration) — spec the formats as constants with a unit test table,
  not ad-hoc `toString`.

## 12. Durable project notes

To land with U10 (CONCEPTS.md / AGENTS.md):
- **CONCEPTS:** add `Jackson JSON surface` (the opt-in per-file annotation/any-surface over a
  generated record); refresh `Dynamic WKT` (it now has a node-typed home in opted-in files and an
  opaque envelope in the Avro twin); add or refresh `Opaque envelope` as a landed term (P1 shape).
- **AGENTS.md:** the module map gains `:recordbuf-json` (`json/` — the Jackson runtime: payload,
  dynamic-WKT node conversion, temporal codecs); the catalog note gains the `jackson` bump (P3);
  the tools section gains one line — generated code may now reference Jackson + `:recordbuf-json`
  *when a file opts in* (the dependency-free rule holds per-file, not per-repo).
