rootProject.name = "petstore"

// Substitute the recordbuf modules with the ones in this repository, so the sample always builds
// against the current generator and runtime. A user's own project would not need this block: it
// would depend on the released io.github.bijnagte.recordbuf artifacts instead.
includeBuild("../..") {
    name = "recordbuf-root"
}
