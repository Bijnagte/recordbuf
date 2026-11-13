package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * An {@code else if (condition) { ... }} clause held by an {@link If}'s else-if chain. Constructed
 * exclusively through {@code If.Builder.elseIf(condition, body...)}; it is internal clause data,
 * not a top-level {@link Element}: the renderer reaches it via {@code If.elseIf()} and lays it out
 * between the {@code if} body's close brace and the terminal {@code else} body.
 */
record ElseIf(RawExpr condition, List<Element> body) {

  ElseIf {
    body = body == null ? List.of() : List.copyOf(body);
  }
}
