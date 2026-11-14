package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@code switch (subject) { case p -> { ... } default -> { ... } }} element in arrow ({@code ->})
 * form. The renderer owns the case and default body braces by an auto-brace rule (DEC-3 / B4): an
 * empty arm renders as {@code -> {}}, a single-element arm renders un-braced as {@code case p ->
 * stmt;} (so it can also act as a switch-expression yield arm), and a multi-element arm braces {
 * case p -> {...}}; an empty {@link #cases()} list omits the case arms and an empty {@link
 * #defaultBody()} omits the terminal {@code default} clause. Modelled on {@link If} (where {@link
 * ElseIf} is the per-clause type and {@code elseBody} is the terminal alternative); the {@link
 * Case} arms and the terminal {@code default} clause are attached via {@link #on(String)}.
 *
 * <p>The {@link #subject()} is a {@link RawExpr}, so the collect phase can contribute type refs and
 * the render phase can resolve positional args. Use {@link #on(String, Object[])} for
 * {@code @N}-templated subjects.
 */
public record Switch(RawExpr subject, List<Case> cases, List<Element> defaultBody)
    implements Element {

  public Switch {
    cases = cases == null ? List.of() : List.copyOf(cases);
    defaultBody = defaultBody == null ? List.of() : List.copyOf(defaultBody);
  }

  /** Entry point: seed a builder with a static subject expression string. */
  public static Builder on(String subject) {
    return new Builder(RawExpr.of(subject));
  }

  /** Entry point: seed a builder with a {@code @N}-templated subject expression string. */
  public static Builder on(String subjectTemplate, Object... subjectArgs) {
    return new Builder(RawExpr.of(subjectTemplate, subjectArgs));
  }

  /** An accumulating, ergonomic {@link Switch} builder; the subject is supplied at construction. */
  public static final class Builder {
    private final RawExpr subject;
    private List<Case> cases = new ArrayList<>();
    private List<Element> defaultBody = new ArrayList<>();

    public Builder(RawExpr subject) {
      Objects.requireNonNull(subject);
      this.subject = subject;
    }

    /** Append a case arm with one pattern and one or more body elements; calls accumulate. */
    public Builder caseArm(String pattern, Element... body) {
      cases.add(new Case(List.of(pattern), body == null ? List.of() : List.of(body)));
      return this;
    }

    /** Append a case arm with several patterns and one or more body elements; calls accumulate. */
    public Builder caseArm(List<String> patterns, Element... body) {
      cases.add(new Case(patterns, body == null ? List.of() : List.of(body)));
      return this;
    }

    /** Append a pre-built case arm; calls accumulate. */
    public Builder caseArm(Case c) {
      cases.add(c);
      return this;
    }

    /** Append an existing sequence of case arms; calls accumulate. */
    public Builder caseArm(List<Case> arms) {
      List<Case> next = new ArrayList<>(this.cases);
      if (arms != null) next.addAll(arms);
      this.cases = next;
      return this;
    }

    /** Append one or more default-arm body elements; calls accumulate. */
    public Builder defaultBody(Element... s) {
      List<Element> next = new ArrayList<>(this.defaultBody);
      for (Element st : s) next.add(st);
      this.defaultBody = next;
      return this;
    }

    /** Append an existing sequence of default-arm body elements; calls accumulate. */
    public Builder defaultBody(List<Element> s) {
      List<Element> next = new ArrayList<>(this.defaultBody);
      if (s != null) next.addAll(s);
      this.defaultBody = next;
      return this;
    }

    /** Construct the {@link Switch}. */
    public Switch build() {
      return new Switch(subject, cases, defaultBody);
    }
  }
}
