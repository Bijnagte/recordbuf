---
title: Oneof Identity Subtypes - Plan
type: feat
date: 2026-11-27
topic: oneof-identity-subtypes
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

# Oneof Identity Subtypes - Plan

## Goal Capsule

- **Objective:** For oneof cases whose type is a generated message or enum, make the generated type itself the sealed-interface subtype instead of wrapping it in a `<Cap>Value` record. Scalar cases and any case that cannot be an identity subtype keep the wrapper.
- **Product authority:** The decisions in this Product Contract. `STRATEGY.md` bounds scope: recordbuf stays a models-and-codecs generator.
- **Open blockers:** None. The two Open Decisions were resolved at U0 (see *U0 Result*).
- **Done when:** `./gradlew build` is green, the petstore sample's `Pet.Kind` oneof (`dog`, `cat`, `bird`) compiles with `Dog`/`Cat`/`Bird` as subtypes, every existing golden is byte-unchanged, and the new identity cases have round-trip coverage on the codec, Interop, Jackson and Avro paths.

---

## Product Contract

### Summary

A oneof `Pet.kind { Dog dog = 3; Cat cat = 4; Bird bird = 5; }` today generates `Pet.Kind.DogValue(Dog value)`, `CatValue`, `BirdValue`, each implementing the sealed `Kind`. Callers pattern-match on the wrapper, so the same `Dog` is reachable two ways (`pet.kind()` gives a `DogValue`, `pet.dog()` gives a `Dog`).

After this change the case is `Dog` itself: `Dog implements Pet.Kind` and `Kind permits Dog, Cat, Bird`. Callers match on `Dog` directly. Wire format does not change.

### Requirements

- R1. A oneof case whose member type is a generated **message** or **enum** is an identity subtype when the eligibility rule (R3) holds. The case type declares `implements <Iface>` and appears in the interface's `permits`. No `<Cap>Value` record is emitted for it.
- R2. A oneof case whose member type is a **scalar** (including `string`, `bytes`, numeric, `bool`) always keeps its `<Cap>Value` wrapper. Java scalars are final JDK types that cannot implement a generated interface.
- R3. **Eligibility is pairwise and symmetric.** A case (oneof `O` declared in file `F`, member type `T` declared in file `G`) is an identity subtype iff all of:
  1. `T` is a message or enum;
  2. both `F` and `G` are in this invocation's `file_to_generate`;
  3. `T`'s Java package equals the Java package of `O`'s interface;
  4. `T` is not the message that declares `O`. The sealed interface is nested in the declaring record, and a class cannot implement its own member type, so a direct self-reference keeps its wrapper.

  Every file's output depends only on this predicate, so the record that implements `Iface` and the `permits` clause naming it always agree, even when generation is split across invocations. Otherwise the case falls back to the wrapper on both sides.
- R4. A message may be a case in several oneofs. Its record then implements several sealed interfaces. A message may also be a case of a oneof declared inside itself; that self-referential case is not identity-eligible (R3.4) and keeps its wrapper, but it must still compile and round-trip.
- R5. **Wire format is unchanged.** Protobuf binary, proto3 JSON, and Avro binary output are byte-identical for every existing fixture and golden.
- R6. **Jackson JSON is unchanged** for the existing goldens (`.jackson.json`). The identity case must reproduce the current flattened shape, or the change is a stop condition (see Open Decisions).
- R7. The generator is deterministic. Output for a file must not depend on the order of `file_to_generate` or `proto_file`.
- R8. NullAway, JSpecify, Checkstyle, Spotless and `-Werror` stay clean on the generated code.

### Scope Boundaries

**In scope**
- `CodeGenerator` / `FileTable`: the eligibility predicate (R3), exposed to every writer.
- `RecordSourceMapping.emitOneof` and its surrounding record members (constructor, copy constructor, Jackson component).
- `CodecSourceMapping`, `InteropSourceMapping`, `AvroConverterSourceMapping`, and the Jackson writer paths for oneof cases.
- Fixtures: a new identity-eligible oneof in `oneof.proto` (or an extension of it), a case that must stay wrapped, and a cross-package case that must stay wrapped.
- Sample: `samples/petstore` picks up the new shape.
- README and `docs/solutions/` note for the rule.

**Out of scope**
- Scalar cases (R2). Revisit only if a scalar-holding wrapper is replaced by a boxed generated type, which is not planned.
- Changing the Avro schema or the protobuf descriptor.
- Identity subtypes across multiple protoc invocations as a supported mode. R3 makes the fallback safe, not identity.

### Key Decisions

- **Fallback is per case, not per oneof.** A oneof can mix identity and wrapper cases. Each case is decided independently by R3.
- **Enums are identity-eligible.** An enum can implement a sealed interface and appear in `permits`. Java treats it as implicitly final, or implicitly sealed when constants have bodies. Both satisfy the permits rule.
- **Identity is decided from the pair, not a global index.** A pre-pass over `proto_file` is still needed to resolve types, but eligibility is evaluated per (oneof, case) pair against `file_to_generate`.
- **API break is accepted.** `Pet.Kind.DogValue` disappears for identity cases. The project is pre-1.0 (`0.0.x`), so this ships as a breaking change noted in the release notes. No deprecated alias is generated.
- **`Unwrapable` lives on the wrapper, not the interface.** The sealed interface is a bare marker. Each wrapper record implements `Unwrapable<T>` with the member's concrete type (`Unwrapable<String>`, `Unwrapable<Far>`); identity subtypes are read as-is, wrappers through `instanceof Unwrapable<?>`. Chosen over `extends Unwrapable<Object>` on the interface (an erased, less discoverable API).
- **Self-referential cases are not identity.** A record cannot implement its own nested sealed interface type, so R3.4 excludes direct self-references; the wrapper shape (with its `Unwrapable` member) covers them. The earlier spike assumption that Java accepts this was wrong for records.

### Open Decisions (resolved at U0)

1. **Open-enum unknown numbers: resolved. Identity for all enums.** The codec already routes an unknown number away from the case. `CodecSourceMapping.emitOneofDecodeCase` (~L1737) calls `forNumberOrNull`, adds the number to the unknown-field carrier, and leaves the oneof unset, so it re-emits on encode. `InteropSourceMapping.emitOneofFrom` (~L573) does the same with `forNumberOrNull(...) == null ? null`. The identity case keeps both guards and assigns the enum constant in place of the wrapper, so no number is lost. Nothing in the wire path depended on the wrapper for this.

2. **Jackson shape: resolved. Per-member getters, not unwrapping.** A spike (`/tmp/compound-engineering/oneof-identity/spike/Spike.java`, `Spike2.java`, Jackson 2.23.0) tested four shapes against today's wrapper output `{"name","<member>":{...},"<discriminator>"}`:
   - plain `@JsonUnwrapped` on the subtype-typed component: flattens the subtype's own fields (`{"bark":...}`), and an enum case fails with `kind$` leaking. Rejected.
   - `@JsonTypeInfo(WRAPPER_OBJECT)` on the sealed interface: nests as `{"kind":{"dog":{...}}}`. Rejected, different shape.
   - `@JsonTypeInfo(WRAPPER_OBJECT)` plus `@JsonUnwrapped`: fails at serialization (`Unwrapped property requires use of type information`). Rejected.
   - **Chosen:** the component is `@JsonIgnore`, and each identity member gets a `@JsonProperty(member.jsonName())` getter returning the subtype or `null`. The discriminator getter stays. The creator already takes one parameter per member, so it only changes the value it builds. Output matched today's shape, including key order (`{"name","dog","kind"}`), and round-tripped for both a message and an enum case. An unset oneof emits no member key under `@JsonInclude(NON_NULL)`.

### U0 Result

- The target shape compiles with `javac -Xlint:all -Werror` on Java 25: `sealed interface` with `permits` naming a record and an enum, and both implementing it.
- Java requires the enum to be declared `implements <Iface>`. The generator must emit that on the enum, not only in `permits`.
- Avro: `ridesValueWrapper` is already false for plain message members, so message identity needs no schema or converter change. Enum members still ride the shared wrapper value union, so the converter must wrap the enum constant the way it wraps today. The schema is unchanged.
- Gate passed. U1 may start.

---

## Implementation Units

### U0. Spike and decision gate

**Goal:** Settle the two Open Decisions with evidence, and prove the generated shape compiles before the writers change.

**Files:** scratch under `/tmp/compound-engineering/oneof-identity/`; no committed changes except notes.

**Approach:**
- Hand-write the target shape for `Pet.Kind` with `Dog`, `Cat`, `Bird` as records, and an open enum case, and compile it with `javac -Werror` under Java 25. Confirm `permits`, `implements`, and enum-as-subtype compile.
- Trace unknown enum numbers through `CodecSourceMapping` (decode of an enum oneof case), `InteropSourceMapping` (`forNumber` in `from`), and `AvroConverterSourceMapping`. Record which paths lose the number.
- Hand-write the identity Jackson shape and diff its output against `oneof.jackson.json` (or the nearest existing golden).

**Verification:** A short decision note in `docs/solutions/` or the PR description with the chosen option for each Open Decision. The gate is passed only when both are resolved.

### U1. Eligibility predicate

**Goal:** One function, `identity(oneof, member)`, that all writers call.

**Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/CodeGenerator.java`, `.../plugin/types/FileTable.java` (or the nearest type-resolution class), `.../plugin/model/Oneof.java`, new unit test under `generator/src/test/`.

**Approach:**
- Build the predicate from R3. Read `file_to_generate` from the request and store it on the run context, so every writer sees the same set.
- Resolve `member`'s declaring file and Java package via the existing `FileTable` lookups. Do not re-parse descriptors.
- Expose `Oneof.isIdentity(Field member)` or an equivalent on the context. It must not be cached per file in a way that depends on generation order (R7).

**Test scenarios:**
- Message and enum members in the same package and same run → identity.
- Scalar member → wrapper, regardless of run.
- Message in another package → wrapper.
- Message whose file is in `proto_file` but not `file_to_generate` → wrapper.
- Result is identical with `file_to_generate` reordered.

### U2. Record emission

**Goal:** Identity cases are `implements` + `permits`, with no wrapper record.

**Files:** `generator/.../writer/RecordSourceMapping.java` (`emitOneof` ~L558–636, the wrapper helpers ~L678–690, constructor and copy constructor ~L910–1031, Jackson branch ~L715–830).

**Approach:**
- In `emitOneof`, for each member: if identity, add `iface.permitsType(memberRef)` and skip the nested `<Cap>Value` record. Else keep the existing code path.
- Add `implementsFqn(ifaceName)` on the member's own record or enum builder. This must be emitted from the member's writer, not the oneof's, so it lands in the member's file.
- Update the constructor and copy constructor so the case component is typed as `Iface` and the value is the subtype instance directly.
- The `value()` default still returns `this` for identity cases. Confirm no member field named `value` collides with the default method (record accessor `Dog value()` overrides `Object value()` covariantly, which is legal).

**Test scenarios:** Compile the petstore `Pet` with identity cases; compile a message that is a case of itself; a message that is a case of two oneofs.

### U3. Codec

**Goal:** Encode and decode identity cases with the same wire bytes.

**Files:** `generator/.../writer/CodecSourceMapping.java` (`~L271–276` encode, `~L1669–1674` and `~L1726–1773` decode/arm helpers).

**Approach:** Replace the `valueRecordName` wrapper lookup with the member's own type for identity cases. For messages, reuse the existing nested-message encode/decode path. For enums, use the existing enum-number path, with the U0 decision for unknown numbers.

**Verification:** Golden byte comparison (protobuf binary) passes unchanged.

### U4. Interop

**Goal:** `Interop.from` and `Interop.to` map identity cases directly.

**Files:** `generator/.../writer/InteropSourceMapping.java` (`~L565–595`).

**Approach:** For an identity case, `from` yields the subtype instance (`new` is not needed; the generated `Interop.from` of the message already returns the record). `to` switches on the subtype with a type pattern instead of `Iface.<Cap>Value v`.

**Verification:** Existing `OneofRoundTripTest` plus new identity cases pass.

### U5. Jackson

**Goal:** Identity cases serialize to the same JSON as today (R6).

**Files:** `generator/.../writer/RecordSourceMapping.java` (Jackson component, ~L700–830), `json/` runtime if a serializer hook is needed.

**Approach:** Follow the U0 decision. Keep the discriminator name from `json_oneof`. Verify with the existing `.jackson.json` goldens, which must be byte-unchanged.

**Verification:** `./gradlew :recordbuf-fixtures:test` with the golden matrix. A changed Jackson golden is a stop condition.

### U6. Avro converter

**Goal:** Avro conversion of identity cases; schema unchanged.

**Files:** `generator/.../writer/AvroConverterSourceMapping.java` (`~L453–503`, `~L656`, `~L933`), `AvroRegistry.java` and `AvroSchemaGenerator.java` only if the registry key for a message type changes (it should not; check first).

**Approach:** The converter's `ridesValueWrapper` decision now applies per case. For identity cases, convert the subtype directly. The Avro union index is unchanged because the schema is unchanged.

**Verification:** `.avro.hex` and `.avro.json` goldens unchanged.

### U7. Fixtures, sample, and goldens

**Goal:** Lock the new shape in the fixtures and the sample.

**Status: done.** New standalone fixture protos `fixtures/src/main/proto/identity.proto` (Holder/Part/Node/Kind/Shelter incl. a nested-message oneof and a self-referential oneof) and `identity_far.proto` (cross-package `Far`), plus `IdentitySubtypeTest` (identity assertions, wrapper assertions, wire/Jackson round-trips, protobuf-java byte parity). No golden family was registered for the identity fixtures: the structural assertions are the locks, and the existing goldens under the other families are unchanged.

**Files:** `fixtures/src/main/proto/identity.proto` (new identity-eligible oneof plus a wrapped scalar case and a cross-package wrapped case, if a second package fixture exists), `samples/petstore/src/main/proto/petstore/v1/petstore.proto` (no proto change expected; the `Pet.kind` oneof is the target), `fixtures/src/test/...`, `samples/petstore/src/test/...`.

**Approach:** Add new goldens only for new cases (adding a file is allowed; changing an existing golden is a stop condition, per `AGENTS.md`). Run `./gradlew :recordbuf-fixtures:seedGoldens` only for missing files, and review any new Jackson golden by hand. Update structural tests that assert `DogValue`-style names.

**Verification:** `git diff --stat` on existing golden files is empty.

### U8. Docs

**Goal:** Document the rule and the breaking change.

**Files:** `README.md` (oneof section), `docs/solutions/` new note, release notes if a changelog exists, `CONCEPTS.md` if "identity subtype" needs a glossary entry.

**Approach:** State R2/R3 in user terms: which cases are identity, which keep wrappers, and why (Java scalars, cross-package, cross-run). Show a before/after snippet for `Pet.Kind`.

---

## Verification (whole change)

1. `./gradlew build` green from the repo root.
2. `./gradlew :recordbuf-generator:installDist`, then regenerate `samples/petstore` with the launcher; confirm `Pet.Kind.DogValue` is gone and `Dog implements Pet.Kind`.
3. `git diff` shows no changes under existing golden directories.
4. Round-trip tests pass for protobuf binary, proto3 JSON, Jackson, and Avro for each identity case, including the enum case.
5. Determinism: generating with `file_to_generate` in two orders yields identical output.

## Risks

| Risk | Mitigation |
|---|---|
| Open-enum unknown numbers are lost | U0 gate; default to wrapper for open enums |
| Jackson shape drifts from goldens | U0 diff against existing goldens; stop condition if unreproducible |
| Split-invocation output diverges | R3 pairwise predicate; test with reordered `file_to_generate` |
| Cross-package or self-referential cases don't compile | Same-package rule in R3; self-case test in U2 |
| Breaking API for users matching `DogValue` | Release note; pre-1.0 versioning; no alias generated |
| Golden churn hides a real change | Stop condition on any existing-golden diff |
