package dev.recordbuf.plugin.writer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minimal indented Java source writer.
 *
 * <p>Tracks current indentation and a per-file set of fully-qualified import
 * names. Callers add code via {@link #line(String) line(...)} and structural
 * blocks via {@link #block(Block, String...) block(...)}. Imports that are
 * actually used are emitted automatically on {@link #toString()}.
 *
 * <p>This is intentionally simple — it is not a parser-aware Java code
 * generator. We avoid line continuations, generics spanning lines, and
 * anything that requires the writer to "know" about the language.
 */
public final class JavaWriter {
    private final StringBuilder sb;
    private int indent = 0;
    private final Set<String> imports = new LinkedHashSet<>();
    private final List<String> headerLines = new ArrayList<>();

    public JavaWriter() {
        this.sb = new StringBuilder(1024);
    }

    /** Add a multi-line comment block emitted before the package/import section. */
    public JavaWriter header(String line) {
        headerLines.add(line);
        return this;
    }

    /** Mark a fully-qualified type as imported. Simple type names are emitted as single-line imports. */
    public JavaWriter importType(String fqName) {
        if (fqName != null && !fqName.isBlank() && !fqName.startsWith("java.lang.")) {
            imports.add(fqName);
        }
        return this;
    }

    /** Current indent level (0 = top of file). Callers may need it for
     * manual indentation in fixed-structure code. */
    public int indent() { return indent; }

    /** Set the indent level. */
    public JavaWriter indent(int n) { this.indent = n; return this; }

    /** Add a raw line of code (no indentation management beyond the current level). */
    public JavaWriter line() {
        sb.append('\n');
        return this;
    }

    public JavaWriter line(String text) {
        for (int i = 0; i < indent; i++) sb.append("    ");
        sb.append(text).append('\n');
        return this;
    }

    /** Run {@code body} with indentation incremented and the block wrapped in
     * {@code open} / {@code close} on their own lines. */
    public void block(String open, String close, Consumer<JavaWriter> body) {
        int prev = indent;
        indent++;
        line(open);
        body.accept(this);
        indent = prev;
        line(close);
    }

    /** Emit a single-line block: {@code open + " " + close}. */
    public void inlineBlock(String open, String close) {
        line(open + " " + close);
    }

    @Override
    public String toString() {
        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);

        // 1. Leading comment header (license, generated-by, etc.)
        if (!headerLines.isEmpty()) {
            pw.print("/*\n");
            for (String l : headerLines) pw.println(" * " + l);
            pw.println(" */\n");
        }

        // 2. Package (caller is responsible for emitting the `package ...;` line
        //    itself; we leave it for them so the structure stays flexible).

        // 3. Imports
        if (!imports.isEmpty()) {
            List<String> sorted = new ArrayList<>(imports);
            sorted.sort(null);
            boolean first = true;
            for (String imp : sorted) {
                if (!first) pw.println();
                first = false;
                pw.println("import " + imp + ";");
            }
            pw.println();
        }

        // 4. Body
        pw.print(sb.toString());
        pw.flush();
        return out.toString();
    }

}
