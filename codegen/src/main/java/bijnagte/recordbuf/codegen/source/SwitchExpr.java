package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.ArrayList;
import java.util.List;

/**
 * A switch-expression node, usable as an initializer in a {@link LocalVar}. Unlike {@link Switch}
 * (a statement-level {@link Element} with a statement body), this is a self-contained multi-line
 * {@code Formattable} expression that renders the entire {@code switch (subject) { … } } block.
 *
 * <p>Each case arm is a single pattern {@code LABEL -> bodyExpr;} (the renderer appends {@code
 * ;\n}); the terminal {@code default -> defaultExpr;} clause is optional.
 */
public final class SwitchExpr implements Formattable {

  private final RawExpr subject;
  private final List<Arm> arms;
  private final Formattable defaultArm;

  private SwitchExpr(RawExpr subject, List<Arm> arms, Formattable defaultArm) {
    this.subject = subject;
    this.arms = List.copyOf(arms);
    this.defaultArm = defaultArm;
  }

  /** Entry point: seed a builder with a static subject expression string. */
  public static Builder on(String subject) {
    return new Builder(RawExpr.of(subject));
  }

  /** Entry point: seed a builder with a {@code @N}-templated subject expression string. */
  public static Builder on(String subjectTemplate, Object... subjectArgs) {
    return new Builder(RawExpr.of(subjectTemplate, subjectArgs));
  }

  /** A single case arm: a switch pattern label + a {@code Formattable} body expression. */
  public record Arm(String pattern, Formattable body) {}

  /** Accumulating builder. */
  public static final class Builder {
    private final RawExpr subject;
    private final List<Arm> arms = new ArrayList<>();
    private Formattable defaultArm;

    Builder(RawExpr subject) {
      this.subject = subject;
    }

    /**
     * Append one {@code case LABEL -> bodyExpr;} arm.
     *
     * @param label the switch case label (e.g. {@code "NAME"} for a {@code case NAME -> …} arm)
     * @param bodyExpr the body expression — rendered without a trailing {@code ;}
     */
    public Builder caseArm(String label, Formattable bodyExpr) {
      arms.add(new Arm(label, bodyExpr));
      return this;
    }

    /** Set the terminal {@code default -> bodyExpr;} arm. */
    public Builder defaultArm(Formattable bodyExpr) {
      this.defaultArm = bodyExpr;
      return this;
    }

    /** Build the {@code SwitchExpr}. */
    public SwitchExpr build() {
      return new SwitchExpr(subject, arms, defaultArm);
    }
  }

  @Override
  public void collect(TypeCollector ci) {
    if (subject instanceof RawExpr r) r.collect(ci);
    for (Arm arm : arms) {
      if (arm.body() instanceof RawExpr r) r.collect(ci);
    }
    if (defaultArm instanceof RawExpr r) r.collect(ci);
  }

  @Override
  public String render(ResolutionContext ctx) {
    StringBuilder sb = new StringBuilder("switch (").append(subject.render(ctx)).append(") {\n");
    for (Arm arm : arms) {
      sb.append("case ")
          .append(arm.pattern())
          .append(" -> ")
          .append(arm.body().render(ctx))
          .append(";\n");
    }
    if (defaultArm != null) {
      sb.append("default -> ").append(defaultArm.render(ctx)).append(";\n");
    }
    sb.append("}");
    return sb.toString();
  }
}
