package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A {@code case <patterns> -> { ... }} arm of a {@link Switch}. Constructed exclusively through
 * {@code Switch.Builder.caseArm(...)}; it is internal clause data, not a top-level {@link Element}:
 * the renderer reaches it via {@code Switch.cases()} and lays it out between the opening brace and
 * the optional {@link Switch#defaultBody()}. Body braces follow the parent's auto-brace rule: an
 * empty {@link #body()} renders {@code case <p> -> {}}, a single element renders un-braced {@code
 * case <p> -> stmt;}, and more braces {> {@code case <p> -> {...}}}.
 */
record Case(List<String> patterns, List<Element> body) {

  Case {
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
    body = body == null ? List.of() : List.copyOf(body);
  }
}
