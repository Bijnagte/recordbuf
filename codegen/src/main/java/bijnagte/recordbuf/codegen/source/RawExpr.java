package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * An escape-hatch expression piece: a raw template string with positional argument substitution.
 *
 * <p>The template uses {@code %d} as a placeholder token for the next argument and {@code %%} as an
 * escape for a literal {@code %}. Arguments are {@link ExprPiece}s, each rendered in order by
 * {@link #render(ResolutionContext)}. This design enables the escape-hatch-first approach (R7): a
 * raw string with zero args renders verbatim — byte-identical to today's {@code String} form.
 *
 * @param template the raw code template; {@code %d} positions are substituted with each arg
 * @param args the expression pieces rendered in order, substituted for each {@code %d} position
 */
public record RawExpr(String template, List<ExprPiece> args) implements ExprPiece {

  /** Create a {@code RawExpr} from a template and varargs of expression pieces. */
  public static RawExpr of(String template, ExprPiece... args) {
    return new RawExpr(template, List.of(args));
  }

  /** Create a {@code RawExpr} from a template and a list of expression pieces. */
  public static RawExpr of(String template, List<ExprPiece> args) {
    return new RawExpr(template, args);
  }

  /**
   * Render the template with each {@code %d} position filled from the corresponding arg's {@link
   * ExprPiece#render(ResolutionContext)}. A {@code %%} renders as a literal {@code %}.
   *
   * @param ctx the render-time resolution context
   * @return the substituted text
   */
  @Override
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
          if (argIdx < args.size()) {
            sb.append(args.get(argIdx++).render(ctx));
          } else {
            sb.append("%d"); // no more args; leave the placeholder as-is
          }
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
