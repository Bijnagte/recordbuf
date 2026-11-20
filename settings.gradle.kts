rootProject.name = "recordbuf"

// Six co-versioned subprojects; flat role-named dirs under the repo root
// (recordbuf-runtime -> runtime/, recordbuf-json -> json/, etc.). KTD1.
include(":recordbuf-runtime")
project(":recordbuf-runtime").projectDir = File(rootDir, "runtime")

include(":recordbuf-options")
project(":recordbuf-options").projectDir = File(rootDir, "options")

include(":recordbuf-fixtures")
include(":recordbuf-avro")
project(":recordbuf-fixtures").projectDir = File(rootDir, "fixtures")

include(":recordbuf-generator")
project(":recordbuf-generator").projectDir = File(rootDir, "generator")

include(":recordbuf-codegen")
project(":recordbuf-codegen").projectDir = File(rootDir, "codegen")

include(":recordbuf-json")
project(":recordbuf-json").projectDir = File(rootDir, "json")
