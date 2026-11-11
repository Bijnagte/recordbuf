package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link Type} tree to Java source, top-down. Import resolution is two-phase (collect the
 * whole tree into a {@link TypeCollector}, {@link TypeCollector#finalizeResolution() finalize},
 * then render). Structure is rendered from the AST; method/constructor/static-block bodies are the
 * {@code List} of {@link Statement}s (idiom nodes + the {@link Statement.Fragment} escape) joined
 * verbatim at the body's indent via the renderer's {@code renderStatement} layout pass.
 *
 * <p>The renderer never re-partitions a flat member stream: each {@link Type} stores its body in
 * typed component lists, and the renderer reads them directly. It switches exhaustively over the
 * {@link Member} variants when it lays out a body. The canonical body order is fields, static
 * blocks, constructors, methods, then nested types; a record's components head its body and an
 * enum's constants head the enum body.
 */
public final class SourceRenderer {

  private static final String INDENT = "    ";

  private SourceRenderer() {}

  /** Renders a whole file -- the two-phase import pass + the file skeleton. */
  public static String render(JavaFile file) {
    Pass p = new Pass(file.ci());
    p.collect(file.top());
    file.ci().finalizeResolution();

    StringBuilder out = new StringBuilder(8192);
    if (file.packageName() != null && !file.packageName().isBlank()) {
      out.append("package ").append(file.packageName()).append(";\n\n");
    }
    List<String> imps = file.ci().importLines();
    for (String l : imps) out.append(l).append('\n');
    if (!imps.isEmpty()) out.append('\n');
    p.render(file.top(), 0, out);
    return out.toString();
  }

  private static String indent(int level) {
    return INDENT.repeat(level);
  }

  /* ---- the render pass --------------------------------------------------- */

  private static final class Pass {
    private final TypeCollector ci;

    Pass(TypeCollector ci) {
      this.ci = ci;
    }

    /**
     * The collect phase: read the type's typed body lists and register import references. Records
     * contribute components instead of fields; nested types recurse.
     */
    void collect(Type t) {
      t.implementsFqns().forEach(ci::use);
      if (t instanceof Type.Interface iface) {
        iface.extendsFqns().forEach(ci::use);
        // permitsFqns are pre-resolved relative references
        // (e.g. "Choice.NameValue") or bare short names; the renderer emits them verbatim
        // because nested types within the interface are not import-eligible.
      }
      for (Member.Field c : t.components()) ci.use(c.type());
      for (Member.Field f : t.fields()) ci.use(f.type());
      for (Member.Method m : t.methods()) {
        ci.use(m.returnType());
        m.thrown().forEach(ci::use);
      }
      for (Member.Nested n : t.nested()) collect(n.type());
    }

    void render(Type t, int indent, StringBuilder out) {
      if (t instanceof Type.Record r) {
        renderRecord(r, indent, out);
      } else if (t instanceof Type.Enum e) {
        renderEnum(e, indent, out);
      } else if (t instanceof Type.Class c) {
        renderClass(c, indent, out);
      } else if (t instanceof Type.Interface iface) {
        renderInterface(iface, indent, out);
      } else {
        throw new IllegalStateException("unsupported Type: " + t.getClass());
      }
    }

    /* ---- record ---------------------------------------------------------- */

    private void renderRecord(Type.Record r, int indent, StringBuilder out) {
      int body = indent + 1;
      List<Member> bodyMembers = bodyMembers(r);

      out.append(indent(indent));
      if (r.javadoc().present()) out.append(javadoc(r.javadoc().raw(), indent)).append('\n');
      if (r.deprecated()) out.append('@').append("Deprecated\n").append(indent(indent));
      out.append(r.mods().withSpace()).append("record ").append(r.name()).append('(');
      var comps = r.components();
      if (comps.isEmpty()) {
        out.append(')');
      } else {
        for (int i = 0; i < comps.size(); i++) {
          out.append('\n').append(indent(body));
          Member.Field f = comps.get(i);
          if (f.javadoc().present()) {
            out.append(javadoc(f.javadoc().raw(), body)).append('\n').append(indent(body));
          }
          if (f.deprecated()) {
            out.append('@').append("Deprecated\n").append(indent(body));
          }
          out.append(ci.use(f.type())).append(' ').append(f.name());
          out.append(i + 1 == comps.size() ? "\n)" : ",");
        }
      }
      if (!r.implementsFqns().isEmpty()) {
        out.append(" implements ").append(joinResolved(r.implementsFqns()));
      }
      if (bodyMembers.isEmpty()) {
        out.append(" {}");
        return;
      }
      out.append(" {\n");
      for (int i = 0; i < bodyMembers.size(); i++) {
        if (i > 0) out.append('\n');
        renderMember(bodyMembers.get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- enum ------------------------------------------------------------ */

    private void renderEnum(Type.Enum e, int indent, StringBuilder out) {
      int body = indent + 1;
      List<Member> bodyMembers = bodyMembers(e);
      out.append(indent(indent));
      if (e.javadoc().present()) out.append(javadoc(e.javadoc().raw(), indent)).append('\n');
      if (e.deprecated()) out.append('@').append("Deprecated\n").append(indent(indent));
      out.append(e.mods().withSpace()).append("enum ").append(e.name());
      if (!e.implementsFqns().isEmpty()) {
        out.append(" implements ").append(joinResolved(e.implementsFqns()));
      }
      out.append(" {\n");
      var consts = e.enumConstants();
      for (int i = 0; i < consts.size(); i++) {
        Member.EnumConstant c = consts.get(i);
        // The optional per-constant Javadoc first (it renders its own indent); a leading
        // `indent(body)` before it would double-indent the Javadoc's first line.
        if (c.javadoc().present()) {
          out.append(javadoc(c.javadoc().raw(), body)).append('\n');
        }
        out.append(indent(body));
        if (c.deprecated()) out.append('@').append("Deprecated\n").append(indent(body));
        out.append(c.name());
        if (!c.ctorArgs().isEmpty())
          out.append("(").append(String.join(", ", c.ctorArgs())).append(")");
        out.append(i + 1 == consts.size() ? ";" : ",").append('\n');
      }
      for (int i = 0; i < bodyMembers.size(); i++) {
        if (i > 0 || !consts.isEmpty()) out.append('\n');
        renderMember(bodyMembers.get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- class --------------------------------------------------------- */

    private void renderClass(Type.Class c, int indent, StringBuilder out) {
      int body = indent + 1;
      List<Member> bodyMembers = bodyMembers(c);
      out.append(indent(indent));
      // Annotations render first, each on its own indented line, before the Javadoc.
      for (String ann : c.annotations()) {
        out.append(indent(indent)).append(ann).append('\n');
      }
      if (c.javadoc().present()) {
        out.append(javadoc(c.javadoc().raw(), indent)).append('\n');
      }
      if (c.deprecated()) {
        out.append('@').append("Deprecated\n").append(indent(indent));
      }
      out.append(c.mods().withSpace()).append("class ").append(c.name());
      if (!c.extendsFqns().isEmpty()) {
        out.append(" extends ").append(joinResolved(c.extendsFqns()));
      }
      if (!c.implementsFqns().isEmpty()) {
        out.append(" implements ").append(joinResolved(c.implementsFqns()));
      }
      if (bodyMembers.isEmpty()) {
        out.append(" {}\n");
        return;
      }
      out.append(" {\n");
      for (int i = 0; i < bodyMembers.size(); i++) {
        if (i > 0) out.append('\n');
        renderMember(bodyMembers.get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- interface -------------------------------------------------- */

    private void renderInterface(Type.Interface iface, int indent, StringBuilder out) {
      int body = indent + 1;
      List<Member> bodyMembers = bodyMembers(iface);
      out.append(indent(indent));
      if (iface.javadoc().present()) {
        out.append(javadoc(iface.javadoc().raw(), indent)).append('\n');
      }
      if (iface.deprecated()) {
        out.append('@').append("Deprecated\n").append(indent(indent));
      }
      out.append(iface.mods().withSpace()).append("interface ").append(iface.name());
      if (!iface.extendsFqns().isEmpty()) {
        out.append(" extends ").append(joinResolved(iface.extendsFqns()));
      }
      if (!iface.permitsFqns().isEmpty()) {
        out.append(" permits ").append(String.join(", ", iface.permitsFqns()));
      }
      if (bodyMembers.isEmpty()) {
        out.append(" {}\n");
        return;
      }
      out.append(" {\n");
      for (int i = 0; i < bodyMembers.size(); i++) {
        if (i > 0) out.append('\n');
        renderMember(bodyMembers.get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /**
     * The type body in canonical order: fields, static blocks, constructors, methods, nested. A
     * record contributes no fields of its own (its head is {@link Type#components()}), so this is
     * its body. Assembled from the typed component lists only — never a re-partition of a flat
     * member stream.
     */
    private List<Member> bodyMembers(Type t) {
      List<Member> out = new ArrayList<>();
      out.addAll(t.fields());
      out.addAll(t.staticBlocks());
      out.addAll(t.constructors());
      out.addAll(t.methods());
      out.addAll(t.nested());
      return out;
    }

    /* ---- body members -------------------------------------------------- */

    /** Render one body member. Exhaustive over the {@link Member} sealed hierarchy. */
    private void renderMember(Member mem, int indent, StringBuilder out) {
      int body = indent + 1;
      switch (mem) {
        case Member.Field f -> {
          out.append(indent(indent));
          if (f.javadoc().present()) out.append(javadoc(f.javadoc().raw(), indent)).append('\n');
          if (f.deprecated()) out.append('@').append("Deprecated\n").append(indent(indent));
          out.append(f.mods().withSpace());
          // type + initializer are pre-resolved short form (the mapping ran them through the
          // collector), so render verbatim -- a generic type like Map<Integer, Color> would be
          // mangled by a second ci.use, which strips generic args.
          out.append(f.type()).append(' ').append(f.name());
          if (f.initializer() != null && !f.initializer().isBlank()) {
            out.append(" = ").append(f.initializer());
          }
          out.append(";\n");
        }
        case Member.Method m -> {
          out.append(indent(indent));
          if (m.javadoc().present()) out.append(javadoc(m.javadoc().raw(), indent)).append('\n');
          // @Override annotation before the modifier line.
          if (m.override()) out.append("@Override\n").append(indent(indent));
          String sig =
              m.mods().withSpace()
                  + ci.use(m.returnType())
                  + " "
                  + m.name()
                  + renderParams(m.params());
          if (!m.thrown().isEmpty()) {
            sig = sig + " throws " + String.join(", ", m.thrown().stream().map(ci::use).toList());
          }
          if (m.abstractDecl()) {
            out.append(sig).append(";\n");
            return;
          }
          out.append(sig).append(" {\n");
          for (Statement s : m.body()) renderStatement(body, s, out);
          out.append(indent(indent)).append("}\n");
        }
        case Member.Constructor c -> {
          out.append(indent(indent));
          if (c.javadoc().present()) out.append(javadoc(c.javadoc().raw(), indent)).append('\n');
          if (c.body().isEmpty()) {
            out.append(c.mods().withSpace())
                .append(c.name())
                .append(renderParams(c.params()))
                .append(" {}\n");
          } else {
            out.append(c.mods().withSpace())
                .append(c.name())
                .append(renderParams(c.params()))
                .append(" {\n");
            for (Statement s : c.body()) renderStatement(body, s, out);
            out.append(indent(indent)).append("}\n");
          }
        }
        case Member.StaticBlock s -> {
          out.append(indent(indent)).append("static {\n");
          for (Statement st : s.body()) renderStatement(body, st, out);
          out.append(indent(indent)).append("}\n");
        }
        case Member.Nested n -> render(n.type(), indent, out);
        // Enum constants are heads of an enum body; they are never part of a type body, so this
        // arm is unreachable but keeps the switch exhaustive over the sealed hierarchy.
        case Member.EnumConstant ignored -> {}
      }
    }

    /* ---- helpers --------------------------------------------------------- */

    /** Render one body statement at the block's indent -- the idiom layout pass lives here. */
    private void renderStatement(int body, Statement s, StringBuilder out) {
      switch (s) {
        case Statement.Fragment fr -> out.append(indent(body)).append(fr.text()).append('\n');
        case Statement.Return r ->
            out.append(indent(body))
                .append(r.expr() == null ? "return;" : "return " + render(r.expr()) + ";")
                .append('\n');
        case Statement.Throw t ->
            out.append(indent(body))
                .append("throw")
                .append(' ')
                .append(render(t.expr()))
                .append(';')
                .append('\n');
        case Statement.ExprStatement e ->
            out.append(indent(body)).append(render(e.expr())).append(";\n");
        case Statement.LocalVar lv ->
            out.append(indent(body))
                .append(lv.type())
                .append(' ')
                .append(lv.name())
                .append(lv.init() == null ? ";" : " = " + render(lv.init()) + ";")
                .append('\n');
        case Statement.Comment c ->
            out.append(indent(body)).append("// ").append(c.text()).append('\n');
        case If i -> renderIf(body, i, out);
        case While w -> renderWhile(body, w, out);
        case For f -> renderFor(body, f, out);
        case Switch sw -> renderSwitch(body, sw, out);
      }
    }

    /**
     * Render an {@link If} control-flow chain -- the {@code if}, its {@code else-if} clauses, and
     * an optional terminal {@code else}. Each clause renders its own braces ({@code } else if
     * (...)} / {@code } else {}} on the preceding close-brace line) and lays its statement body out
     * at the next indent via {@link #renderStatement}; an empty branch list renders no clause.
     */
    private void renderIf(int body, If ifStmt, StringBuilder out) {
      out.append(indent(body)).append("if (").append(ifStmt.condition()).append(") {\n");
      for (Statement s : ifStmt.body()) renderStatement(body + 1, s, out);
      for (ElseIf ei : ifStmt.elseIf()) {
        out.append(indent(body)).append("} else if (").append(ei.condition()).append(") {\n");
        for (Statement s : ei.body()) renderStatement(body + 1, s, out);
      }
      if (!ifStmt.elseBody().isEmpty()) {
        out.append(indent(body)).append("} else {\n");
        for (Statement s : ifStmt.elseBody()) renderStatement(body + 1, s, out);
      }
      out.append(indent(body)).append("}\n");
    }

    /**
     * Render a {@link While} loop -- the {@code while (condition) {}} header and its statement body
     * at the next indent, closed by its own brace. A {@code while} has no branch and no optional
     * {@code else} clause, so it always closes on its own; an empty body renders the empty-loop
     * case {@code while (c) {}}.
     */
    private void renderWhile(int body, While w, StringBuilder out) {
      out.append(indent(body)).append("while (").append(w.condition()).append(") {\n");
      for (Statement s : w.body()) renderStatement(body + 1, s, out);
      out.append(indent(body)).append("}\n");
    }

    /**
     * Render a for-each loop -- the {@code for (pattern : iterable) {}} header and its statement
     * body at the next indent, closed by its own brace. A for-each always closes on its own; an
     * empty body renders the empty-loop case {@code for (p : x) {}}.
     */
    private void renderFor(int body, For f, StringBuilder out) {
      out.append(indent(body))
          .append("for (")
          .append(f.pattern())
          .append(" : ")
          .append(f.iterable())
          .append(") {\n");
      for (Statement s : f.body()) renderStatement(body + 1, s, out);
      out.append(indent(body)).append("}\n");
    }

    /**
     * Render a {@code switch} statement -- the {@code switch (subject) {}} header, its {@link Case}
     * arms and terminal {@code default ->} arm, each at the next indent, closed by its own brace.
     * Arm braces are decided by arm size (auto-brace): an empty arm renders {@code -> {}}, a
     * single-statement arm renders un-braced {@code -> stmt;} (required for switch-expression
     * yields), and a multi-statement arm braces {@code -> { … }}.
     */
    private void renderSwitch(int body, Switch sw, StringBuilder out) {
      int arm = body + 1;
      out.append(indent(body)).append("switch (").append(sw.subject()).append(") {\n");
      for (Case c : sw.cases()) renderCase(arm, c, out);
      // Render the terminal default arm only when it has a body, mirroring If's optional elseBody:
      // an empty defaultBody omits the default clause entirely.
      if (!sw.defaultBody().isEmpty()) renderDefaultArm(arm, sw.defaultBody(), out);
      out.append(indent(body)).append("}\n");
    }

    private void renderCase(int arm, Case c, StringBuilder out) {
      out.append(indent(arm))
          .append("case ")
          .append(String.join(", ", c.patterns()))
          .append(" -> ");
      renderArmBody(arm, c.body(), out);
    }

    private void renderDefaultArm(int arm, List<Statement> body, StringBuilder out) {
      out.append(indent(arm)).append("default -> ");
      renderArmBody(arm, body, out);
    }

    /**
     * Render one arm's body -- the auto-brace rule the user chose: empty renders {@code -> {}}, one
     * statement renders un-braced {@code -> stmt;} (so it can yield as a switch-expression arm),
     * more than one braces {@code -> { … }}, each body statement at the next inner indent.
     */
    private void renderArmBody(int arm, List<Statement> body, StringBuilder out) {
      if (body.isEmpty()) {
        out.append("{}\n");
      } else if (body.size() == 1) {
        out.append(renderBodyInline(body.get(0))).append('\n');
      } else {
        out.append("{\n");
        for (Statement s : body) renderStatement(arm + 1, s, out);
        out.append(indent(arm)).append("}\n");
      }
    }

    /**
     * Render a single-statement arm body inline (no leading indent, no trailing newline) so it can
     * sit on the arm's {@code case … -> } line. For a {@link Statement.Fragment} the leading
     * whitespace is stripped so the renderer's layout owns the indentation; a structured body is
     * rendered at zero indent as a best-effort fallback.
     */
    private String renderBodyInline(Statement s) {
      if (s instanceof Statement.Fragment fr) return fr.text().stripLeading();
      StringBuilder sb = new StringBuilder();
      renderStatement(0, s, sb);
      return sb.toString().stripLeading().replaceAll("\\s+$", "");
    }

    /** Render an expression to its one-line textual form (no terminator, no leading indent). */
    private String render(Expression e) {
      switch (e) {
        case Expression.Fragment f -> {
          return f.text();
        }
        case Expression.Literal l -> {
          return l.text();
        }
        case Expression.Member m -> {
          return m.receiver() + "." + m.name();
        }
        case Expression.Call c -> {
          return render(c.receiver()) + "(" + joinExpr(c.args()) + ")";
        }
        case Expression.New n -> {
          return "new " + n.type() + "(" + joinExpr(n.args()) + ")";
        }
        case Expression.Parens p -> {
          return "(" + render(p.inner()) + ")";
        }
        case Expression.Ternary t -> {
          return render(t.cond()) + " ? " + render(t.a()) + " : " + render(t.b());
        }
        case Expression.Binary b -> {
          return render(b.l()) + " " + b.op() + " " + render(b.r());
        }
        case Expression.Assign a -> {
          return render(a.l()) + " = " + render(a.r());
        }
      }
    }

    private String joinExpr(List<Expression> args) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < args.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(render(args.get(i)));
      }
      return sb.toString();
    }

    private String joinResolved(List<String> fqns) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < fqns.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(ci.use(fqns.get(i)));
      }
      return sb.toString();
    }

    /**
     * Render a parameter list, owned by the renderer: an empty list renders as {@code ()};
     * otherwise the {@code (type name)} elements are joined with {@code , } inside the parens. Each
     * {@link Param} type is a pre-resolved short-form string, emitted verbatim (a second {@code
     * ci.use} would mangle a generic like {@code List<T>}).
     */
    private String renderParams(List<Param> params) {
      if (params.isEmpty()) return "()";
      StringBuilder sb = new StringBuilder("(");
      for (int i = 0; i < params.size(); i++) {
        Param p = params.get(i);
        if (i > 0) sb.append(", ");
        sb.append(p.type()).append(' ').append(p.name());
      }
      return sb.append(')').toString();
    }
  }

  /**
   * Package-private: render a Javadoc block comment from raw text. Null or blank {@code raw}
   * returns the empty string (renders nothing).
   */
  static String javadoc(String raw, int indent) {
    if (raw == null || raw.isBlank()) return "";
    StringBuilder sb = new StringBuilder();
    sb.append(indent(indent)).append("/**\n");
    for (String l : raw.strip().lines().toList()) {
      if (l.isEmpty()) {
        sb.append(indent(indent)).append(" *\n");
      } else {
        sb.append(indent(indent)).append(" * ").append(escapeHtml(l)).append('\n');
      }
    }
    sb.append(indent(indent)).append("*/");
    return sb.toString();
  }

  static String escapeHtml(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '&') out.append("&amp;");
      else if (c == '<') out.append("&lt;");
      else if (c == '>') out.append("&gt;");
      else out.append(c);
    }
    return out.toString();
  }
}
