// recordbuf-options: distributes recordbuf_options.proto (the java_interface /
// java_enum_interface extension definitions) as a protoc import target. A proto
// author adds THIS module's src/main/proto to their `-I` include path; it is NOT
// a Gradle dependency of the generator (KTD5/R3). It therefore carries no source
// sets and ships only the .proto — group/version co-version from the root (U5).
