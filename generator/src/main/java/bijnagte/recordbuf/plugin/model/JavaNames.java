package bijnagte.recordbuf.plugin.model;

import java.util.Locale;

/**
 * Naming helpers. We use simple, predictable rules:
 *
 * <ul>
 *   <li>{@link #cap(String)} capitalizes the first letter (for type names).</li>
 *   <li>{@link #camel(String)} converts {@code foo_bar_baz} → {@code fooBarBaz}.</li>
 *   <li>{@link #snakeUpper(String)} produces an UPPER_SNAKE constant name.</li>
 * </ul>
 */
public final class JavaNames {
    private JavaNames() { }

    public static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String camel(String s) {
        if (s == null || s.isEmpty()) return s;
        // Split on `_`, capitalize each segment except the first, re-join.
        StringBuilder out = new StringBuilder(s.length());
        boolean capNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                capNext = true;
            } else {
                out.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            }
        }
        return out.toString();
    }

    public static String snakeUpper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT).replace('-', '_');
    }

}
