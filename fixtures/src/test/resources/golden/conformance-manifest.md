# Conformance manifest

This manifest inventories the scenario groups of the official protobuf conformance suite at tag `v36.2` (the tag that matches the project's protobuf-java `4.36.2`), taken from [`conformance/binary_json_conformance_suite.cc`](https://raw.githubusercontent.com/protocolbuffers/protobuf/v36.2/conformance/binary_json_conformance_suite.cc): the groups `RunAllTests` runs for each message type, plus `RunSuiteImpl`'s extra groups. The suite is mirrored as cases in the Java suite, not executed: Google's C++ runner is not adopted. Following a later upstream tag is a manual refresh of this file.

Each row is one scenario group, not one upstream case. `status` is one of:

- `covered`: a Java test mirrors the group. `reference` lists `fully.qualified.TestClass#method` entries, comma separated. `GapLedgerTest` checks that each class and method exists.
- `gap`: recordbuf does not satisfy the group yet, or the mirror is not written yet. `reference` lists the id of the [gap ledger](gap-ledger.md) row that carries the reason, comma separated. A ledger row of kind `conformance-only` must be named by at least one `gap` row here, so a row left in the ledger after its groups are mirrored fails the test.
- `not-applicable`: the group does not apply to recordbuf. `reference` is the reason: proto2, editions, MessageSet, delimited-field and text-format groups, performance runs, and JSON parse-rejection and serialize-rejection groups that only exercise Google's `JsonFormat` (R15: the JSON path under test is `JsonFormat` to the protobuf-java message, then the generated `Interop` converter, so Google-owned rejection is not a recordbuf gap).

`category` is `binary`, `json` or `text-format`. Groups are named after the upstream function that runs them (`TestValidDataForType`) or, for scenarios inside a function, the upstream test-name prefix. A cell must not contain `|`.

A `covered` group is mirrored, not necessarily passing: a case recordbuf cannot satisfy yet runs as a dynamic test that aborts with the id of the [gap ledger](gap-ledger.md) row that explains it, and fails if it starts passing (the mirror's table of known gaps is in the test class). So a gap row stays in the ledger as a construct row while the cases it explains are covered here.

U21 to U25 flip `gap` rows to `covered` as they mirror groups, and delete the `conformance-only` ledger rows that no longer apply.

A `gap` row can name only ledger ids, not a test, so a JSON group that is mirrored but gated is listed here as `gap` and its mirror is named in this paragraph. Every JSON scenario targets `TestAllTypesProto3`, which has no generated `Interop` converter while `interop-all-types` is open, so the recordbuf half of a mirrored scenario (`JsonFormat` to the protobuf-java message, `Interop.from`, the record, `Interop.to`, `JsonFormat` back) cannot run yet. [`JsonCoreConformanceTest`](../../java/bijnagte/recordbuf/fixture/conformance/JsonCoreConformanceTest.java) mirrors the `RunJsonTests` top level (`HelloWorld`, `IgnoreUnknownJson*`), `RunJsonTestsForFieldNameConvention`, `RunJsonTestsForNonRepeatedTypes`, `RunJsonTestsForRepeatedTypes` and the `IgnoreUnknownEnumStringValue*` scenarios of `RunJsonTestsForUnknownEnumStringValues`. [`JsonWktConformanceTest`](../../java/bijnagte/recordbuf/fixture/conformance/JsonWktConformanceTest.java) mirrors `RunJsonTestsForNullTypes`, `RunJsonTestsForWrapperTypes` (wrappers, `Duration` and `Timestamp`), `RunJsonTestsForFieldMask`, `RunJsonTestsForStruct`, `RunJsonTestsForValue` and `RunJsonTestsForAny`; its `Any` scenarios resolve their types through a type registry. The two classes share their scenario model and halves in `JsonConformance`. In each class, each scenario has a `portCheck` test that runs now against protobuf-java alone (proving the port is faithful) and a `recordbuf` test that aborts with `interop-all-types` (or with the id of the commented-out construct the scenario names) and runs for real once the gap closes. A group's `gap` row flips to `covered` in the change that closes `interop-all-types`, when its test class becomes its reference.

| group | category | status | reference |
| --- | --- | --- | --- |
| TestPrematureEOFForType | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryMalformedConformanceTest#prematureEofForType |
| TestIllegalTags | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryMalformedConformanceTest#illegalTags |
| TestIllegalLengths | binary | not-applicable | Named in the plan, but absent from `binary_json_conformance_suite.cc` at v36.2: no such group exists at the pinned tag |
| TestUnmatchedGroup | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryMalformedConformanceTest#unmatchedGroup |
| TestUnknownWireType | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryMalformedConformanceTest#unknownWireType |
| TestInvalidUtf8String | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryMalformedConformanceTest#invalidUtf8String |
| TestValidDataForType | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#validDataForType |
| TestValidDataForRepeatedScalarMessage | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#validDataForRepeatedScalarMessage |
| TestValidDataForMapType | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#validDataForMapType |
| TestOverwriteMessageValueMap | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#overwriteMessageValueMap |
| TestValidDataForOneofType | binary | gap | oneof-snake-case-member |
| TestMergeOneofMessage | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryBehaviorConformanceTest#mergeOneofMessage |
| TestOneofMessage | binary | gap | oneof-snake-case-member |
| TestUnknownMessage | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryBehaviorConformanceTest#unknownMessage |
| TestUnknownOrdering | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryBehaviorConformanceTest#unknownOrdering |
| RunRecursionLimitTests | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryBehaviorConformanceTest#recursionLimit |
| RunBinaryPerformanceTests | binary | not-applicable | Opt-in performance run (`performance_` flag), not part of the correctness suite |
| RunJsonTests top level (HelloWorld, IgnoreUnknownJson*) | json | gap | interop-all-types |
| RunJsonTests top level (RejectTopLevelNull) | json | not-applicable | Google-owned (R15): `JsonFormat` rejects the input before `Interop` runs |
| RunJsonTestsForFieldNameConvention | json | gap | interop-all-types, field-name-uppercase-start |
| RunJsonTestsForFieldNameConvention (malformed syntax: unquoted names, trailing commas, comments, missing commas, duplicate names) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForNonRepeatedTypes | json | gap | interop-all-types, oneof-snake-case-member, map-message-value, enum-alias-name-collision |
| RunJsonTestsForNonRepeatedTypes (out-of-range and non-integer numbers, bad literals) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForRepeatedTypes | json | gap | interop-all-types |
| RunJsonTestsForRepeatedTypes (single value for repeated field) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForNullTypes | json | gap | map-message-value, interop-all-types |
| RunJsonTestsForNullTypes (null as repeated element or map key or value) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForWrapperTypes | json | gap | interop-all-types |
| RunJsonTestsForWrapperTypes (Duration and Timestamp parse rejection, and serialize rejection of out-of-range or non-canonical messages) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForFieldMask | json | gap | interop-all-types |
| RunJsonTestsForFieldMask (invalid characters, and serialize rejection of paths that do not round trip) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForStruct | json | gap | interop-all-types |
| RunJsonTestsForStruct (StructDeepNesting200 parse rejection) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForValue | json | gap | null-value-field, oneof-snake-case-member, interop-all-types |
| RunJsonTestsForValue (ListValueDeepNesting200 and ValueDeepNesting200 parse rejection, NaN and Infinity number value serialize rejection) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForAny | json | gap | interop-all-types |
| RunJsonTestsForAny (bad type URL, empty type and value) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForUnknownEnumStringValues (IgnoreUnknownEnumStringValue*) | json | gap | interop-all-types, map-enum-value |
| RunJsonTestsForUnknownEnumStringValues (RejectUnknownEnumStringValue*) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForStoresDefaultPrimitive | json | not-applicable | Proto2 only: `RunJsonTests` calls it for proto2 message types, not for proto3 |
| RunJsonTestsForReservedFields | json | not-applicable | Defined in the suite but not called by `RunJsonTests` at v36.2 |
| TestAllTypesProto2 (binary groups) | binary | not-applicable | Proto2: recordbuf mirrors proto3 only (R13) |
| TestAllTypesProto2 (JSON groups) | json | not-applicable | Proto2: recordbuf mirrors proto3 only (R13) |
| TestAllTypesProto3Editions and TestAllTypesProto2Editions (binary groups) | binary | not-applicable | Editions: recordbuf mirrors proto3 only (R13) |
| TestAllTypesProto3Editions and TestAllTypesProto2Editions (JSON groups) | json | not-applicable | Editions: recordbuf mirrors proto3 only (R13) |
| RunMessageSetTests | binary | not-applicable | MessageSet is a proto2 construct |
| RunDelimitedFieldTests (TestAllTypesEdition2023) | binary | not-applicable | Editions delimited-field encoding: not proto3 |
| RunUnstableTests (TestAllTypesEditionUnstable) | binary | not-applicable | Editions unstable features: not proto3 |
| RunUtf8ValidationTests (TestAllTypesEdition2023) | binary | not-applicable | Editions UTF-8 validation feature: not proto3 |
| Text-format conformance (text_format_conformance_suite.cc) | text-format | not-applicable | Text format is not a recordbuf format |
