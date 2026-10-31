plugins {
    java
    application
}

group = "dev.recordbuf"
version = "0.0.1"

java {
    // Records + text blocks need 17+; we target 21 (LTS) as a baseline so the
    // produced classes run on 21+ JREs even when built on a newer JDK.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // Used by the plugin at generation time to parse FileDescriptorProto
    // messages and build Descriptors. NOT a runtime dep of generated code,
    // but generated codecs DO use com.google.protobuf.CodedInputStream /
    // CodedOutputStream, so users need protobuf-java on their classpath.
    implementation("com.google.protobuf:protobuf-java:4.29.3")
    implementation("com.google.protobuf:protobuf-java-util:4.29.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // Entry point of the protoc plugin.
    mainClass = "dev.recordbuf.plugin.Main"
    applicationName = "protoc-gen-recordbuf"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType(JavaCompile::class.java).configureEach {
    options.release = 21
}

// The plugin is consumed by `protoc` via:
//
//   protoc --plugin=protoc-gen-recordbuf=$PWD/build/scripts/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew installDist` that script lives at build/scripts/protoc-gen-recordbuf
// and is a self-contained launcher (it puts the project classpath on `$CLASSPATH`
// and execs `java -cp ... dev.recordbuf.plugin.Main`).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "dev.recordbuf.plugin.Main",
            "Implementation-Title" to "recordbuf",
            "Implementation-Version" to project.version,
        )
    }
}
