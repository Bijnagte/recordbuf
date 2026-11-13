package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * An escape-hatch raw expression: a raw code template string with {@code %d} positional
 * placeholders filled by a list of {@link TypeRef} args, each resolved at render time.
 *
 * <p>{@code %%} renders as a literal {@code %}. A template with no {@code %d} renders verbatim.
 */
public record RawExpr(String template, List<TypeRef> args) {

  /** Create a {@code RawExpr} from a template and varargs. */
  public static RawExpr of(String template, TypeRef... args) {
    return new RawExpr(template, List.of(args));
  }

  /** Create a {@code RawExpr} from a template and a list of args. */
  public static RawExpr of(String template, List<TypeRef> args) {
    return new RawExpr(template, args);
  }

  /**
   * Render the template, substituting each {@code %d} position with the corresponding {@link
   * TypeRef} resolved against {@code ctx}.
   *
   * @param ctx the render-time resolution context
   * @return the substituted text
   */
  public String render(ResolutionContext ctx) {
    if (args.isEmpty()) {
      return template;
    }
    StringBuilder sb = new StringBuilder(template.length() + 32);
    int argIdx = 0;
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '%' && i + 1 < template.length()) {
        char next = template.charAt(i + 1);
        if (next == 'd') {
          sb.append(
              argIdx < args.size()
                  ? args.get(argIdx++).resolved(ctx.declaringPackage(), ctx.collidedShortNames())
                  : "%d");
          i++;
        } else if (next == '%') {
          sb.append('%');
          i++;
        } else {
          sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
