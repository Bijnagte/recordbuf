rootProject.name = "recordbuf"

// Co-versioned subprojects; flat role-named dirs under the repo root
// (recordbuf-runtime -> runtime/, etc.). KTD1.
include(":recordbuf-runtime")
project(":recordbuf-runtime").projectDir = File(rootDir, "runtime")
