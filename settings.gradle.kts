rootProject.name = "recordbuf"

// Four co-versioned subprojects; flat role-named dirs under the repo root
// (recordbuf-runtime -> runtime/, etc.). KTD1.
include(":recordbuf-runtime")
project(":recordbuf-runtime").projectDir = File(rootDir, "runtime")

include(":recordbuf-options")
project(":recordbuf-options").projectDir = File(rootDir, "options")

include(":recordbuf-fixtures")
project(":recordbuf-fixtures").projectDir = File(rootDir, "fixtures")
