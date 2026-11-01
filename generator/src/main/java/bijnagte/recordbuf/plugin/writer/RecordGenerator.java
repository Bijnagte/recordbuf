package bijnagte.recordbuf.plugin.writer;

import java.util.List;

/**
 * Shared helper for the {@code implements X, Y} suffix on a generated record's
 * and enum's type header. The two generators each own their type bodies; only
 * this header suffix is factored out so records and enums stay consistent.
 */
public final class RecordGenerator {

    private RecordGenerator() {
    }

    /**
     * The {@code implements X, Y} suffix for a record or enum header, or the empty string when the
     * type declares no interfaces (so a marker interface is emitted byte-for-byte unchanged). The
     * names are emitted fully qualified — no import is added — to keep the generator decoupled from
     * the user's interface types, which live on the consumer's classpath.
     */
    public static String implementsClause(List<String> interfaces) {
        if (interfaces.isEmpty()) {
            return "";
        }
        return " implements " + String.join(", ", interfaces);
    }
}
