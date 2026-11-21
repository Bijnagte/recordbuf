import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    // Activates the default Error Prone check set; NullAway is added via the
    // `errorprone` configuration below (mirrors :recordbuf-runtime).
    alias(libs.plugins.gradle.errorprone)
}

group = "bijnagte.recordbuf"
version = "0.0.1"

java {
    // Pin the JDK via toolchain, matching the other modules (Temurin 25).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // JSpecify nullability annotations (mirrors :recordbuf-runtime).
    compileOnly(libs.jspecify)

    // NullAway runs as an Error Prone check (mirrors :recordbuf-runtime / :recordbuf-generator).
    errorprone(libs.nullaway)
    errorprone(libs.error.prone.core)

    // The Jackson node types are PART of this module's API: generated dynamic-WKT fields
    // are node-typed (Struct -> ObjectNode, Value/Any -> JsonNode, ListValue -> ArrayNode,
    // NullValue -> NullNode), and the J6 envelope payload is a JsonNode. Exposed via the
    // BOM + a direct databind api dependency so every consumer of :recordbuf-json gets
    // the node types (and one coherent Jackson version) transitively.
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)

    // The temporal format enum (bijnagte.recordbuf.codegen.source.JacksonTemporalFormat) that
    // generated temporal codecs switch on -- exposed so consumers of :recordbuf-json (and of
    // generated code) resolve it from the dependency-free codegen source spine.
    api(project(":recordbuf-codegen"))

    // Generated code that calls these helpers also reaches protobuf's well-known
    // types (Struct/Value/ListValue/NullValue/Any/Timestamp/Duration), so protobuf
    // must flow transitively to every consumer (mirrors the :recordbuf-runtime R4
    // decision).
    api(libs.protobuf)

    // The binary unknown-field carrier (bijnagte.recordbuf.runtime.UnknownFields): the
    // retained unknown JSON properties are folded into it for the wire (U6). Consumers of
    // :recordbuf-json already pull :recordbuf-runtime transitively (generated records
    // implement ProtobufMessage), so this adds nothing new to their classpaths.
    api(project(":recordbuf-runtime"))

    testImplementation(libs.jspecify)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Configure Error Prone + NullAway, mirroring :recordbuf-runtime. OnlyNullMarked=true
// restricts NullAway to our @NullMarked package; the Jackson/protobuf types stay out of
// scope, so the un-annotated JsonNode boundary is safe to dereference.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        error("UnnecessarilyFullyQualified")
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
    }
}
