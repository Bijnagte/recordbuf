package bijnagte.recordbuf.plugin.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Collects the Java types a single generated source file references and resolves
 * each to either its short name (with a corresponding {@code import}) or its
 * fully-qualified name, then renders the file's deduplicated, ordered import
 * block.
 *
 * <p><b>Never imported:</b> {@code java.lang.*} types and types in the file's
 * own package resolve to their short name unconditionally and contribute no
 * import line. Every other referenced type is imported. <b>Collision:</b> a
 * short name shared by two or more distinct fully-qualified types is resolved to
 * its fully-qualified form at every reference site and contributes no import, so
 * the consumer's compiler disambiguates the types by their distinct packages.
 *
 * <p>Resolution has two phases. {@link #use(String)} is called for each type
 * reference while a file is generated; a collision depends on the whole referral
 * set, not on any single reference, so the set is only known once the file has
 * been walked end to end. The caller therefore generates the file once into a
 * throwaway writer to collect the set, calls {@link #finalizeResolution()}, and
 * generates again — after which {@link #use} returns collision-aware names and
 * {@link #importLines()} is final.
 */
public final class TypeImports {

    /** Import-eligible fully-qualified type names, first-seen order (drives grouping). */
    private final LinkedHashSet<String> importable = new LinkedHashSet<>();
    /** short name -> the distinct fully-qualified names seen under it (collision detection). */
    private final Map<String, LinkedHashSet<String>> byShort = new LinkedHashMap<>();
    /** short names shared by two or more distinct FQNs, so neither is imported. */
    private final LinkedHashSet<String> collidedShort = new LinkedHashSet<>();
    private final String filePackage;
    private boolean finalized = false;

    public TypeImports(String filePackage) {
        this.filePackage = filePackage == null ? "" : filePackage;
    }

    /**
     * Records a type reference and returns the name to emit at that site.
     *
     * <p>A bare type carrying no package segment — a primitive ({@code int},
     * {@code long}, ...), the {@code byte[]} array type, or a same-package /
     * {@code java.lang} short name — is returned unchanged and never imported.
     * Before {@link #finalizeResolution()} the return value is always the short
     * name; after finalization it is the fully-qualified name only when the
     * short name is shared by another distinct type.
     */
    public String use(String raw) {
        String fqn = stripGenerics(raw);
        int dot = fqn.lastIndexOf('.');
        if (dot < 0) return raw;                          // primitive / array / bare name
        String pkg = fqn.substring(0, dot);
        String simple = fqn.substring(dot + 1);
        if (pkg.equals("java.lang") || pkg.equals(filePackage)) {
            return simple;                                 // never imported
        }
        importable.add(fqn);
        LinkedHashSet<String> seen = byShort.computeIfAbsent(simple, k -> new LinkedHashSet<>());
        seen.add(fqn);
        if (seen.size() > 1) collidedShort.add(simple);
        return finalized && collidedShort.contains(simple) ? fqn : simple;
    }

    /** Marks the referral set complete; {@link #use} then returns collision-aware names. */
    public void finalizeResolution() {
        this.finalized = true;
    }

    /**
     * The file's import statements, each formatted {@code "import x.y.Z;"},
     * deduplicated and grouped: the {@code java.*} package first, a single blank
     * line, then every other package; alphabetical within each group. Collided
     * names are omitted (they are emitted in fully-qualified form). Empty when
     * nothing is imported.
     */
    public List<String> importLines() {
        TreeSet<String> java = new TreeSet<>();
        TreeSet<String> other = new TreeSet<>();
        for (String fqn : importable) {
            if (collidedShort.contains(shortName(fqn))) continue;   // collided -> FQN, no import
            if (fqn.startsWith("java.")) java.add(fqn);
            else other.add(fqn);
        }
        List<String> lines = new ArrayList<>();
        for (String fqn : java) lines.add("import " + fqn + ";");
        if (!java.isEmpty() && !other.isEmpty()) lines.add("");
        for (String fqn : other) lines.add("import " + fqn + ";");
        return lines;
    }

    private static String stripGenerics(String raw) {
        int lt = raw.indexOf('<');
        return lt < 0 ? raw : raw.substring(0, lt);
    }

    private static String shortName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
