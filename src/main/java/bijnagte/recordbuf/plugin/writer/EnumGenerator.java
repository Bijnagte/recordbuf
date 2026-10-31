package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.Enum;

/**
 * Emits a Java enum from a {@link Enum} model. We keep the proto-style enum
 * value names and add a {@code getNumber()} accessor plus a reverse-number
 * lookup used by the codec when decoding.
 */
public final class EnumGenerator {

    /** Emit a top-level enum declaration at the current writer indent. */
    public void write(JavaWriter w, Enum e) {
        String name = e.name();
        if (e.deprecated()) w.line("@Deprecated");
        w.line("public enum " + name + " {");

        // Foo(0), Bar(1);
        for (int i = 0; i < e.constants().size(); i++) {
            var c = e.constants().get(i);
            char sep = (i == e.constants().size() - 1) ? ';' : ',';
            w.line(c.name() + "(" + c.number() + ")" + sep);
        }
        w.line();
        w.line("private final int number;");
        w.line();
        w.line(name + "(int number) { this.number = number; }");
        w.line();
        w.line("public int getNumber() { return number; }");
        w.line();
        w.line("// Reverse lookup used by the codec when decoding from wire.");
        w.line("private static final java.util.Map<Integer, " + name + "> BY_NUMBER = new java.util.HashMap<>();");
        w.block("static {", "}", ww -> {
            for (var c : e.constants()) {
                ww.line("BY_NUMBER.put(" + c.number() + ", " + c.name() + ");");
            }
        });
        w.line();
        w.line("public static " + name + " forNumber(int number) {");
        w.line("    return BY_NUMBER.getOrDefault(number, " + firstConstantName(e) + ");");
        w.line("}");
        w.line("}");
    }

    private static String firstConstantName(Enum e) {
        return e.constants().isEmpty() ? "UNRECOGNIZED" : e.constants().get(0).name();
    }
}
