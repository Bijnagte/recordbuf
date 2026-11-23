# Conformance manifest

This manifest inventories the scenario groups of the official protobuf conformance suite at tag `v36.2` (the tag that matches the project's protobuf-java `4.36.2`), taken from [`conformance/binary_json_conformance_suite.cc`](https://raw.githubusercontent.com/protocolbuffers/protobuf/v36.2/conformance/binary_json_conformance_suite.cc): the groups `RunAllTests` runs for each message type, plus `RunSuiteImpl`'s extra groups. The suite is mirrored as cases in the Java suite, not executed: Google's C++ runner is not adopted. Following a later upstream tag is a manual refresh of this file.

Each row is one scenario group, not one upstream case. `status` is one of:

- `covered`: a Java test mirrors the group. `reference` lists `fully.qualified.TestClass#method` entries, comma separated. `GapLedgerTest` checks that each class and method exists.
- `gap`: recordbuf does not satisfy the group yet, or the mirror is not written yet. `reference` lists the id of the [gap ledger](gap-ledger.md) row that carries the reason, comma separated. A ledger row of kind `conformance-only` must be named by at least one `gap` row here, so a row left in the ledger after its groups are mirrored fails the test.
- `not-applicable`: the group does not apply to recordbuf. `reference` is the reason: proto2, editions, MessageSet, delimited-field and text-format groups, performance runs, and JSON parse-rejection groups that only exercise Google's `JsonFormat` (R15: the JSON path under test is `JsonFormat` to the protobuf-java message, then the generated `Interop` converter, so Google-owned rejection is not a recordbuf gap).

`category` is `binary`, `json` or `text-format`. Groups are named after the upstream function that runs them (`TestValidDataForType`) or, for scenarios inside a function, the upstream test-name prefix. A cell must not contain `|`.

A `covered` group is mirrored, not necessarily passing: a case recordbuf cannot satisfy yet runs as a dynamic test that aborts with the id of the [gap ledger](gap-ledger.md) row that explains it, and fails if it starts passing (the mirror's table of known gaps is in the test class). So a gap row stays in the ledger as a construct row while the cases it explains are covered here.

U21 to U25 flip `gap` rows to `covered` as they mirror groups, and delete the `conformance-only` ledger rows that no longer apply.

| group | category | status | reference |
| --- | --- | --- | --- |
| TestPrematureEOFForType | binary | gap | conformance-binary-malformed |
| TestIllegalTags | binary | gap | conformance-binary-malformed |
| TestIllegalLengths | binary | not-applicable | Named in the plan, but absent from `binary_json_conformance_suite.cc` at v36.2: no such group exists at the pinned tag |
| TestUnmatchedGroup | binary | gap | conformance-binary-malformed |
| TestUnknownWireType | binary | gap | conformance-binary-malformed |
| TestInvalidUtf8String | binary | gap | conformance-binary-malformed |
| TestValidDataForType | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#validDataForType |
| TestValidDataForRepeatedScalarMessage | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#validDataForRepeatedScalarMessage |
| TestValidDataForMapType | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#validDataForMapType |
| TestOverwriteMessageValueMap | binary | covered | bijnagte.recordbuf.fixture.conformance.BinaryValidDataConformanceTest#overwriteMessageValueMap |
| TestValidDataForOneofType | binary | gap | oneof-snake-case-member |
| TestMergeOneofMessage | binary | gap | oneof-snake-case-member |
| TestOneofMessage | binary | gap | oneof-snake-case-member |
| TestUnknownMessage | binary | gap | conformance-binary-unknowns |
| TestUnknownOrdering | binary | gap | conformance-binary-unknowns |
| RunRecursionLimitTests | binary | gap | conformance-binary-recursion |
| RunBinaryPerformanceTests | binary | not-applicable | Opt-in performance run (`performance_` flag), not part of the correctness suite |
| RunJsonTests top level (HelloWorld, IgnoreUnknownJson*) | json | gap | interop-all-types |
| RunJsonTests top level (RejectTopLevelNull) | json | not-applicable | Google-owned (R15): `JsonFormat` rejects the input before `Interop` runs |
| RunJsonTestsForFieldNameConvention | json | gap | interop-all-types, field-name-uppercase-start |
| RunJsonTestsForFieldNameConvention (malformed syntax: unquoted names, trailing commas, comments, missing commas, duplicate names) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForNonRepeatedTypes | json | gap | interop-all-types |
| RunJsonTestsForNonRepeatedTypes (out-of-range and non-integer numbers, bad literals) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForRepeatedTypes | json | gap | interop-all-types |
| RunJsonTestsForRepeatedTypes (single value for repeated field) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForNullTypes | json | gap | null-value-field, interop-all-types |
| RunJsonTestsForNullTypes (null as repeated element or map key or value) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForWrapperTypes | json | gap | interop-all-types |
| RunJsonTestsForWrapperTypes (Duration and Timestamp parse rejection) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForFieldMask | json | gap | interop-all-types |
| RunJsonTestsForFieldMask (invalid characters) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForStruct | json | gap | interop-all-types |
| RunJsonTestsForValue | json | gap | interop-all-types |
| RunJsonTestsForAny | json | gap | interop-all-types |
| RunJsonTestsForAny (bad type URL, empty type and value) | json | not-applicable | Google-owned (R15) |
| RunJsonTestsForUnknownEnumStringValues (IgnoreUnknownEnumStringValue*) | json | gap | interop-all-types |
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
