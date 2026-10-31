plugins {
    java
    application
    // io.soabase.record-builder annotation processor (version via catalog) will
    // be wired in via `alias(libs.plugins.recordBuilder)` once a Java 25–
    // compatible version is pinned — see gradle/libs.versions.toml. Deferred to
    // a follow-up commit pending web access.
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
    implementation("com.google.protobuf:protobuf-java:4.29.3")
    implementation("com.google.protobuf:protobuf-java-util:4.29.3")

    // Annotation source for @NullMarked / @Nullable / @MonotonicNonNull.
    // compileOnly: the *generated* user code will need these annotations on
    // its classpath too; keep the dep out of the plugin's runtime classpath.
    compileOnly("org.jspecify:jspecify:1.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // Entry point of the protoc plugin.
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
