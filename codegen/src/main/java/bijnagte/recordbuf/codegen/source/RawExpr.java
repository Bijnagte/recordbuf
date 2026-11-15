package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.ResolutionContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An escape-hatch raw expression: a source-code template string with positional {@code @N}
 * placeholders filled by positional args, each resolved at render time.
 *
 * <p>Positional syntax:
 *
 * <ul>
 *   <li>{@code @N} (zero-based digit) — substitute {@code args[N]} resolved against {@code ctx}
 *   <li>{@code @@} — a literal {@code @}
 * </ul>
 *
 * <p>Args may be any {@link Formattable} ({@link TypeRef}, nested {@link RawExpr}), or a {@link
 * String} (rendered verbatim). At render time each arg is dispatched via {@code instanceof}:
 *
 * <ul>
 *   <li>{@link TypeRef} — {@link TypeRef#render(ResolutionContext)} (resolves to short/FQN)
 *   <li>{@link RawExpr} — {@link #render(ResolutionContext)} recursively
 *   <li>{@link Formattable} — {@link Formattable#render(ResolutionContext)}
 *   <li>{@link String} — {@code toString()}
 * </ul>
 *
 * <p>At collect time ({@link #referencedTypes()}), {@link Formattable} args contribute their own
 * {@code referencedTypes()} (unioned into the result); {@code String} args are skipped (no import
 * needed).
 *
 * <p>Fail-fast: {@link #of(String, Object...)} throws {@link IllegalArgumentException} at
 * construction if the template contains a positional {@code @N} whose index is out of bounds for
 * the arg list.
 */
public record RawExpr(String template, List<Object> args) implements Formattable {

  /**
   * Create a {@code RawExpr} from a template and varargs, with a fail-fast out-of-bounds check.
   *
   * @param template the source-code template with {@code @N} placeholders
   * @param args the positional argument list
   * @return a new {@link RawExpr}
   * @throws IllegalArgumentException if the template contains a {@code @N} with N &ge; args.length
   */
  public static RawExpr of(String template, Object... args) {
    var list = List.of(args);
    int maxIdx = maxPlaceholderIdx(template);
    if (maxIdx >= list.size()) {
      throw new IllegalArgumentException(
          "RawExpr @N out of bounds: template '"
              + template
              + "' requires N<="
              + maxIdx
              + " but got "
              + list.size()
              + " arg(s)");
    }
    return new RawExpr(template, list);
  }

  /** Create a {@code RawExpr} from a template and a list. */
  public static RawExpr of(String template, List<?> args) {
    int maxIdx = maxPlaceholderIdx(template);
    if (maxIdx >= args.size()) {
      throw new IllegalArgumentException(
          "RawExpr @N out of bounds: template '"
              + template
              + "' requires N<="
              + maxIdx
              + " but got "
              + args.size()
              + " arg(s)");
    }
    return new RawExpr(template, List.copyOf(args));
  }

  /** Create a {@code RawExpr} from a template with {@link TypeRef} args (convenience overload). */
  public static RawExpr of(String template, TypeRef... args) {
    return of(template, (Object[]) args);
  }

  /**
   * Render the template, substituting each {@code @N} position with the corresponding arg, resolved
   * against {@code ctx}.
   *
   * @param ctx the render-time resolution context
   * @return the substituted text
   */
  @Override
  public String render(ResolutionContext ctx) {
    if (args().isEmpty()) return template;
    StringBuilder sb = new StringBuilder(template.length() + 32);
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '@' && i + 1 < template.length()) {
        char next = template.charAt(i + 1);
        if (next == '@') {
          sb.append('@');
          i++;
        } else if (Character.isDigit(next)) {
          int end = i + 2;
          while (end < template.length() && Character.isDigit(template.charAt(end))) end++;
          int idx = Integer.parseInt(template.substring(i + 1, end));
          if (idx < args().size()) {
            Object arg = args().get(idx);
            sb.append(renderArg(arg, ctx));
          }
          i = end - 1;
        } else {
          sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * The type references this expression's args contribute, unioned from each {@link Formattable}
   * arg. {@code String} args are skipped (no import needed).
   */
  @Override
  public Set<TypeRef> referencedTypes() {
    Set<TypeRef> refs = new LinkedHashSet<>();
    for (Object arg : args()) {
      if (arg instanceof Formattable f) refs.addAll(f.referencedTypes());
      // String / primitives: skip — no import needed
    }
    return refs;
  }

  private static String renderArg(Object arg, ResolutionContext ctx) {
    if (arg instanceof TypeRef tr) return tr.render(ctx);
    if (arg instanceof RawExpr r) return r.render(ctx);
    if (arg instanceof Formattable f) return f.render(ctx);
    return String.valueOf(arg);
  }

  /**
   * Scan a template for {@code @N} placeholders and return the maximum index found, or {@code -1}
   * if the template contains no {@code @N} placeholders.
   */
  static int maxPlaceholderIdx(String template) {
    int max = -1;
    for (int i = 0; i < template.length(); i++) {
      if (template.charAt(i) == '@' && i + 1 < template.length()) {
        char next = template.charAt(i + 1);
        if (next == '@') {
          i++; // skip escaped @@
        } else if (Character.isDigit(next)) {
          int end = i + 2;
          while (end < template.length() && Character.isDigit(template.charAt(end))) end++;
          int idx = Integer.parseInt(template.substring(i + 1, end));
          max = Math.max(max, idx);
          i = end - 1;
        }
      }
    }
    return max;
  }
}
