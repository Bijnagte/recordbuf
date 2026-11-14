package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A {@code case <patterns> -> { ... }} arm of a {@link Switch}. Can be constructed via the public
 * {@link #of(String, Element...)} / {@link #of(List, Element...)} factories or via {@code
 * Switch.Builder.caseArm(Case)}. It is internal clause data, not a top-level {@link Element}: the
 * renderer reaches it via {@code Switch.cases()} and lays it out between the opening brace and the
 * optional {@link Switch#defaultBody()}. Body braces follow the parent's auto-brace rule: an empty
 * {@link #body()} renders {@code case <p> -> {}}, a single element renders un-braced {@code case
 * <p> -> stmt;}, and more braces {> {@code case <p> -> {...}}}.
 */
public record Case(List<String> patterns, List<Element> body) {

  public Case {
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** Create a case arm with one pattern and one or more body elements. */
  public static Case of(String pattern, Element... body) {
    return new Case(List.of(pattern), body == null ? List.of() : List.of(body));
  }

  /** Create a case arm with several patterns and one or more body elements. */
  public static Case of(List<String> patterns, Element... body) {
    return new Case(
        patterns == null ? List.of() : patterns, body == null ? List.of() : List.of(body));
  }
}
