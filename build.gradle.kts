plugins {
    java
    application
}

group = "bijnagte.recordbuf"
version = "0.0.1"

java {
    // Pin the JDK via toolchain (per the user's java-gradle-build guidance),
    // not sourceCompatibility/targetCompatibility. The active JDK is Temurin 25;
    // toolchain lets Gradle reuse it without a separate install.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Protobuf runtime -- used by the plugin (FileDescriptorProto) and on the
    // GENERATED code's classpath (CodedInputStream/CodedOutputStream). Bumped
    // to 4.36.2 to match the protoc 36.2 that generated PluginProtos.java.
    implementation(libs.protobuf)
    implementation(libs.protobuf.util)

    // JSpecify nullability annotations.  compileOnly because the generated
    // record/codec types (independent modules) pull JSpecify transitively.
    compileOnly(libs.jspecify)

    // Record-builder generates <Record>With interfaces for the plugin's OWN
    // model records (Field/Message/Enum/ProtoFile/EnumConstant), NOT for
    // generated user-facing output (that stays dependency-free). core carries
    // the @RecordBuilder/@RecordBuilder.* annotations onto the compile classpath;
    // processor is the annotation processor itself.
    compileOnly(libs.record.builder.core)
    annotationProcessor(libs.record.builder.processor)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "bijnagte.recordbuf.plugin.Main"
    applicationName = "protoc-gen-recordbuf"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The plugin is consumed by `protoc` via:
//
//   protoc --plugin=protoc-gen-recordbuf=$PWD/build/scripts/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew installDist` that script lives at build/scripts/protoc-gen-recordbuf
// and is a self-contained launcher (it puts the project classpath on `$CLASSPATH`
// and execs `java -cp ... bijnagte.recordbuf.plugin.Main`).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "bijnagte.recordbuf.plugin.Main",
            "Implementation-Title" to "recordbuf",
            "Implementation-Version" to project.version,
        )
    }
}
