// recordbuf-options: distributes recordbuf_options.proto (the java_interface /
// java_enum_interface extension definitions) as a protoc import target. A proto
// author adds THIS module's src/main/proto to their `-I` include path; it is NOT
// a Gradle dependency of the generator (KTD5/R3). It has no Java sources: the
// .proto is shipped as a plain resource, so the published jar contains
// `recordbuf_options.proto` at its root. Consumers extract it (or unzip the jar
// onto their `-I` path). group/version co-version from the root (U5).
plugins {
    `java-library`
}

// The proto directory is the module's only content, exposed as a resource root so
// the jar carries it verbatim. The java plugin then produces the sources/javadoc
// jars Maven Central requires (empty, but present).
sourceSets {
    main {
        resources.srcDir("src/main/proto")
    }
}

// Spotless (inherited from the root) resolves google-java-format from Maven Central, so this module
// needs the same repository declaration as every other module.
repositories {
    mavenCentral()
}
