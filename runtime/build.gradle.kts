plugins {
    `java-library`
}

group = "bijnagte.recordbuf"
version = "0.0.1"

java {
    // Pin the JDK via toolchain, matching the root/generator builds (Temurin 25).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Generated code that calls the runtime also reaches protobuf's
    // CodedInputStream/CodedOutputStream through Encoder.out()/Decoder.in(), so
    // protobuf must flow transitively to every consumer of the runtime (R4).
    api(libs.protobuf)

    // Direct mechanical-contract tests for the framing helpers (AE1 oracle lives in
    // the fixtures interop suite; these pin the helpers themselves in isolation).
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
