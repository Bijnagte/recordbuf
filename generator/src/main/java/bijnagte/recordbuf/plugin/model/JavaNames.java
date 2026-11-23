package bijnagte.recordbuf.plugin.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Naming helpers. We use simple, predictable rules:
 *
 * <ul>
 *   <li>{@link #cap(String)} capitalizes the first letter (for type names).
 *   <li>{@link #camel(String)} converts {@code foo_bar_baz} → {@code fooBarBaz}.
 *   <li>{@link #snakeUpper(String)} produces an UPPER_SNAKE constant name.
 * </ul>
 */
public final class JavaNames {
  private JavaNames() {}

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
    return s.toUpperCase(Locale.ROOT).replace('-', '_');
  }

  /**
   * Converts a protobuf field name to protobuf-java's accessor stem (the name part after get/set in
   * method names like {@code getField0Name5}), following protoc's Java naming rule.
   *
   * <p>Protoc's rule (UnderscoresToCamelCase with first letter capitalized):
   *
   * <ul>
   *   <li>Underscores are dropped and cause the next letter (if any) to be capitalized.
   *   <li>Digits cause the next letter (if any) to be capitalized.
   *   <li>The first character is always capitalized.
   * </ul>
   *
   * <p>Examples: {@code field_name} → {@code FieldName}, {@code field0name5} → {@code Field0Name5},
   * {@code field_0_name6} → {@code Field0Name6}.
   *
   * @param protoName the proto field name (typically snake_case), or null
   * @return the accessor stem for use in protobuf-java method names, or null if protoName is null
   */
  public static @Nullable String protobufJavaAccessor(@Nullable String protoName) {
    if (protoName == null || protoName.isEmpty()) {
      return protoName;
    }
    StringBuilder out = new StringBuilder(protoName.length());
    boolean capNext = true; // First character is always capitalized.
    for (int i = 0; i < protoName.length(); i++) {
      char c = protoName.charAt(i);
      if (c == '_') {
        capNext = true; // Skip underscore, capitalize next letter.
      } else if (Character.isDigit(c)) {
        out.append(c);
        capNext = true; // Capitalize next letter after digit.
      } else if (Character.isLetter(c)) {
        out.append(capNext ? Character.toUpperCase(c) : c);
        capNext = false;
      } else {
        // Other characters (should not occur in valid proto field names).
        out.append(c);
        capNext = false;
      }
    }
    return out.toString();
  }
}
