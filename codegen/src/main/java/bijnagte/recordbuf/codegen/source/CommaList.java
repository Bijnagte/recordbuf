package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.ResolutionContext;
import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.List;

/**
 * A renderable, comma-separated list of expression pieces. Each element is rendered against {@code
 * ctx} and the results are joined with {@code ", "} at render time, so the elements stay as
 * unresolved {@link Formattable}s through the import-collection phase instead of being pre-rendered
 * to strings (the two-pass {@code render(ctx)} + {@code .collect(ci)} workaround this type
 * replaces).
 *
 * <p>Elements may be any {@link Formattable} ({@link TypeRef}, nested {@link RawExpr}) or a {@link
 * String} (rendered verbatim) — the same positional semantics as {@link RawExpr}.
 */
public record CommaList(List<Object> elements) implements Formattable {

  /** Create a {@code CommaList} from varargs. */
  public static CommaList of(Object... elements) {
    return new CommaList(List.of(elements));
  }

  /** Create a {@code CommaList} from a list. */
  public static CommaList of(List<?> elements) {
    return new CommaList(List.copyOf(elements));
  }

  @Override
  public String render(ResolutionContext ctx) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < elements().size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(renderArg(elements().get(i), ctx));
    }
    return sb.toString();
  }

  @Override
  public void collect(TypeCollector collector) {
    for (Object element : elements()) {
      if (element instanceof Formattable f) f.collect(collector);
    }
  }

  // Mirrors RawExpr#renderArg's positional dispatch: TypeRef / RawExpr / Formattable are resolved
  // against ctx; a bare String renders verbatim (no import needed).
  private static String renderArg(Object arg, ResolutionContext ctx) {
    if (arg instanceof TypeRef tr) return tr.render(ctx);
    if (arg instanceof RawExpr r) return r.render(ctx);
    if (arg instanceof Formattable f) return f.render(ctx);
    return String.valueOf(arg);
  }
}
