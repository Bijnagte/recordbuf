package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.List;

/**
 * Renders a {@link Type} tree to Java source, top-down. Import resolution is two-phase (collect the
 * whole tree into a {@link TypeCollector}, {@link TypeCollector#finalizeResolution() finalize},
 * then render). Structure is rendered from the AST; method/constructor/static-block bodies are the
 * {@code List} of {@link Element}s (idiom nodes + the {@link Fragment} escape) joined verbatim at
 * the body's indent via the renderer's {@code renderStatement} layout pass.
 *
 * <p>The renderer dispatches on the concrete {@link Type} variant and renders that variant's own
 * typed component lists directly -- a record's {@code components}, an enum's {@code enumConstants},
 * a class's fields/constructors/static blocks -- in canonical order. It never assembles or
 * re-partitions a flat member stream; each variant stores exactly the members it can have, and the
 * per-list helpers render them.
 */
public final class SourceRenderer {

  private static final String INDENT = "    ";

  private SourceRenderer() {}

  /** Renders a whole file -- the two-phase import pass + the file skeleton. */
  public static String render(JavaFile file) {
    return render(file, Formatter.IDENTITY);
  }

  /** Renders a whole file, then post-processes it through {@code formatter}. */
  public static String render(JavaFile file, Formatter formatter) {
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
    return formatter.format(out.toString());
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
     * The collect phase: dispatch on the concrete {@link Type} variant and register import
     * references for the lists that variant owns, then recurse into its nested types. An enum's
     * constants and their constructor args are not import references; a class's {@code extends}
     * FQNs are not resolved here (only an interface's are), matching the emitted form.
     */
    void collect(Type t) {
      switch (t) {
        case Type.Record r -> {
          r.implementsFqns().forEach(ci::use);
          r.components().forEach(c -> ci.use(c.type()));
          r.methods().forEach(this::collectMethod);
        }
        case Type.Class c -> {
          c.implementsFqns().forEach(ci::use);
          c.fields().forEach(f -> ci.use(f.type()));
          c.methods().forEach(this::collectMethod);
        }
        case Type.Enum e -> {
          e.implementsFqns().forEach(ci::use);
          e.fields().forEach(f -> ci.use(f.type()));
          e.methods().forEach(this::collectMethod);
        }
        case Type.Interface iface -> {
          iface.extendsFqns().forEach(ci::use);
          iface.methods().forEach(this::collectMethod);
        }
      }
      for (Type n : t.nested()) collect(n);
    }

    private void collectMethod(Method m) {
      ci.use(m.returnType());
      m.thrown().forEach(ci::use);
    }

    /* ---- render dispatch & per-variant renderers ------------------------ */

    void render(Type t, int indent, StringBuilder out) {
      switch (t) {
        case Type.Record r -> renderRecord(r, indent, out);
        case Type.Class c -> renderClass(c, indent, out);
        case Type.Enum e -> renderEnum(e, indent, out);
        case Type.Interface iface -> renderInterface(iface, indent, out);
      }
    }

    private void renderRecord(Type.Record r, int indent, StringBuilder out) {
      int body = indent + 1;
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
          Component f = comps.get(i);
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
      boolean[] first = {true};
      boolean empty =
          r.staticBlocks().isEmpty()
              && r.constructors().isEmpty()
              && r.methods().isEmpty()
              && r.nested().isEmpty();
      if (empty) {
        out.append(" {}");
        return;
      }
      out.append(" {\n");
      renderBlockList(r.staticBlocks(), first, body, out);
      renderCtorList(r.constructors(), first, body, r.name(), out);
      renderMethodList(r.methods(), first, body, out);
      renderNestedList(r.nested(), first, body, out);
      out.append(indent(indent)).append('}').append('\n');
    }

    private void renderClass(Type.Class c, int indent, StringBuilder out) {
      int body = indent + 1;
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
      boolean[] first = {true};
      boolean empty =
          c.fields().isEmpty()
              && c.staticBlocks().isEmpty()
              && c.constructors().isEmpty()
              && c.methods().isEmpty()
              && c.nested().isEmpty();
      if (empty) {
        out.append(" {}\n");
        return;
      }
      out.append(" {\n");
      renderFieldList(c.fields(), first, body, out);
      renderBlockList(c.staticBlocks(), first, body, out);
      renderCtorList(c.constructors(), first, body, c.name(), out);
      renderMethodList(c.methods(), first, body, out);
      renderNestedList(c.nested(), first, body, out);
      out.append(indent(indent)).append('}').append('\n');
    }

    private void renderEnum(Type.Enum e, int indent, StringBuilder out) {
      int body = indent + 1;
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
        EnumConstant c = consts.get(i);
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
      // A body only follows the constants when they exist; when the enum has constants the first
      // body member is separated from them by a blank line (first starts false), otherwise the
      // first
      // body member renders with no leading blank.
      boolean[] first = {consts.isEmpty()};
      renderFieldList(e.fields(), first, body, out);
      renderBlockList(e.staticBlocks(), first, body, out);
      renderCtorList(e.constructors(), first, body, e.name(), out);
      renderMethodList(e.methods(), first, body, out);
      renderNestedList(e.nested(), first, body, out);
      out.append(indent(indent)).append('}').append('\n');
    }

    private void renderInterface(Type.Interface iface, int indent, StringBuilder out) {
      int body = indent + 1;
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
      boolean[] first = {true};
      boolean empty = iface.methods().isEmpty() && iface.nested().isEmpty();
      if (empty) {
        out.append(" {}\n");
        return;
      }
      out.append(" {\n");
      renderMethodList(iface.methods(), first, body, out);
      renderNestedList(iface.nested(), first, body, out);
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- per-list body rendering --------------------------------------
     * Each helper renders one typed list into the shared output, separating members by a blank line.
     * `first` threads the "has a prior member been rendered in this body?" flag across the per-list
     * calls so the very first member of a body takes no leading blank line and later members do.
     */

    private void renderFieldList(
        List<Component> fields, boolean[] first, int body, StringBuilder out) {
      for (Component f : fields) {
        if (!first[0]) out.append('\n');
        renderField(f, body, out);
        first[0] = false;
      }
    }

    private void renderBlockList(
        List<StaticBlock> blocks, boolean[] first, int body, StringBuilder out) {
      for (StaticBlock b : blocks) {
        if (!first[0]) out.append('\n');
        renderStaticBlock(b, body, out);
        first[0] = false;
      }
    }

    private void renderCtorList(
        List<Constructor> ctors, boolean[] first, int body, String enclosing, StringBuilder out) {
      for (Constructor c : ctors) {
        if (!first[0]) out.append('\n');
        renderConstructor(c, body, enclosing, out);
        first[0] = false;
      }
    }

    private void renderMethodList(
        List<Method> methods, boolean[] first, int body, StringBuilder out) {
      for (Method m : methods) {
        if (!first[0]) out.append('\n');
        renderMethod(m, body, out);
        first[0] = false;
      }
    }

    private void renderNestedList(List<Type> nested, boolean[] first, int body, StringBuilder out) {
      for (Type n : nested) {
        if (!first[0]) out.append('\n');
        render(n, body, out);
        first[0] = false;
      }
    }

    /* ---- single member renderers --------------------------------------- */

    /**
     * Render one class/enum body field. Its type + initializer are pre-resolved short form, so they
     * are emitted verbatim -- a second {@code ci.use} would mangle a generic like {@code
     * Map<Integer, Color>}.
     */
    private void renderField(Component f, int body, StringBuilder out) {
      out.append(indent(body));
      if (f.javadoc().present()) out.append(javadoc(f.javadoc().raw(), body)).append('\n');
      if (f.deprecated()) out.append('@').append("Deprecated\n").append(indent(body));
      out.append(f.mods().withSpace());
      out.append(f.type()).append(' ').append(f.name());
      if (f.initializer() != null && !f.initializer().isBlank()) {
        out.append(" = ").append(f.initializer());
      }
      out.append(";\n");
    }

    private void renderStaticBlock(StaticBlock s, int body, StringBuilder out) {
      out.append(indent(body)).append("static {\n");
      for (Element st : s.body()) renderStatement(body + 1, st, out);
      out.append(indent(body)).append("}\n");
    }

    private void renderConstructor(Constructor c, int body, String enclosing, StringBuilder out) {
      out.append(indent(body));
      if (c.javadoc().present()) out.append(javadoc(c.javadoc().raw(), body)).append('\n');
      if (c.body().isEmpty()) {
        out.append(c.mods().withSpace())
            .append(enclosing)
            .append(renderParams(c.params()))
            .append(" {}\n");
      } else {
        out.append(c.mods().withSpace())
            .append(enclosing)
            .append(renderParams(c.params()))
            .append(" {\n");
        for (Element s : c.body()) renderStatement(body + 1, s, out);
        out.append(indent(body)).append("}\n");
      }
    }

    private void renderMethod(Method m, int body, StringBuilder out) {
      out.append(indent(body));
      if (m.javadoc().present()) out.append(javadoc(m.javadoc().raw(), body)).append('\n');
      // @Override annotation before the modifier line.
      if (m.override()) out.append("@Override\n").append(indent(body));
      String sig =
          m.mods().withSpace() + ci.use(m.returnType()) + " " + m.name() + renderParams(m.params());
      if (!m.thrown().isEmpty()) {
        sig = sig + " throws " + String.join(", ", m.thrown().stream().map(ci::use).toList());
      }
      if (m.abstractDecl()) {
        out.append(sig).append(";\n");
        return;
      }
      out.append(sig).append(" {\n");
      for (Element s : m.body()) renderStatement(body + 1, s, out);
      out.append(indent(body)).append("}\n");
    }

    /* ---- helpers --------------------------------------------------------- */

    /** Render one body element at the block's indent -- the idiom layout pass lives here. */
    private void renderStatement(int body, Element s, StringBuilder out) {
      switch (s) {
        case Fragment fr -> out.append(indent(body)).append(fr.text()).append('\n');
        case Return r ->
            out.append(indent(body))
                .append(r.expr() == null ? "return;" : "return " + r.expr() + ";")
                .append('\n');
        case Throw t ->
            out.append(indent(body)).append("throw ").append(t.expr()).append(';').append('\n');
        case ExprStatement e -> out.append(indent(body)).append(e.expr()).append(";\n");
        case LocalVar lv ->
            out.append(indent(body))
                .append(lv.type())
                .append(' ')
                .append(lv.name())
                .append(lv.init() == null ? ";" : " = " + lv.init() + ";")
                .append('\n');
        case Comment c -> out.append(indent(body)).append("// ").append(c.text()).append('\n');
        case If i -> renderIf(body, i, out);
        case While w -> renderWhile(body, w, out);
        case For f -> renderFor(body, f, out);
        case Switch sw -> renderSwitch(body, sw, out);
      }
    }

    /**
     * Render an {@link If} control-flow chain -- the {@code if}, its {@code else-if} clauses, and
     * an optional terminal {@code else}. Each clause renders its own braces ({@code } else if
     * (...)} / {@code } else {}} on the preceding close-brace line) and lays its element body out
     * at the next indent via {@link #renderStatement}; an empty branch list renders no clause.
     */
    private void renderIf(int body, If ifStmt, StringBuilder out) {
      out.append(indent(body)).append("if (").append(ifStmt.condition()).append(") {\n");
      for (Element s : ifStmt.body()) renderStatement(body + 1, s, out);
      for (ElseIf ei : ifStmt.elseIf()) {
        out.append(indent(body)).append("} else if (").append(ei.condition()).append(") {\n");
        for (Element s : ei.body()) renderStatement(body + 1, s, out);
      }
      if (!ifStmt.elseBody().isEmpty()) {
        out.append(indent(body)).append("} else {\n");
        for (Element s : ifStmt.elseBody()) renderStatement(body + 1, s, out);
      }
      out.append(indent(body)).append("}\n");
    }

    /**
     * Render a {@link While} loop -- the {@code while (condition) {}} header and its element body
     * at the next indent, closed by its own brace. A {@code while} has no branch and no optional
     * {@code else} clause, so it always closes on its own; an empty body renders the empty-loop
     * case {@code while (c) {}}.
     */
    private void renderWhile(int body, While w, StringBuilder out) {
      out.append(indent(body)).append("while (").append(w.condition()).append(") {\n");
      for (Element s : w.body()) renderStatement(body + 1, s, out);
      out.append(indent(body)).append("}\n");
    }

    /**
     * Render a for-each loop -- the {@code for (pattern : iterable) {}} header and its element body
     * at the next indent, closed by its own brace. A for-each always closes on its own; an empty
     * body renders the empty-loop case {@code for (p : x) {}}.
     */
    private void renderFor(int body, For f, StringBuilder out) {
      out.append(indent(body))
          .append("for (")
          .append(f.pattern())
          .append(" : ")
          .append(f.iterable())
          .append(") {\n");
      for (Element s : f.body()) renderStatement(body + 1, s, out);
      out.append(indent(body)).append("}\n");
    }

    /**
     * Render a {@code switch} element -- the {@code switch (subject) {}} header, its {@link Case}
     * arms and terminal {@code default ->} arm, each at the next indent, closed by its own brace.
     * Arm braces are decided by arm size (auto-brace): an empty arm renders {@code -> {}}, a
     * single-element arm renders un-braced {@code -> stmt;} (required for switch-expression
     * yields), and a multi-element arm braces {@code -> { … }}.
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

    private void renderDefaultArm(int arm, List<Element> body, StringBuilder out) {
      out.append(indent(arm)).append("default -> ");
      renderArmBody(arm, body, out);
    }

    /**
     * Render one arm's body -- the auto-brace rule the user chose: empty renders {@code -> {}}, one
     * element renders un-braced {@code -> stmt;} (so it can yield as a switch-expression arm), more
     * than one braces {@code -> { … }}, each body element at the next inner indent.
     */
    private void renderArmBody(int arm, List<Element> body, StringBuilder out) {
      if (body.isEmpty()) {
        out.append("{}\n");
      } else if (body.size() == 1) {
        out.append(renderBodyInline(body.get(0))).append('\n');
      } else {
        out.append("{\n");
        for (Element s : body) renderStatement(arm + 1, s, out);
        out.append(indent(arm)).append("}\n");
      }
    }

    /**
     * Render a single-element arm body inline (no leading indent, no trailing newline) so it can
     * sit on the arm's {@code case … -> } line. For a {@link Fragment} the leading whitespace is
     * stripped so the renderer's layout owns the indentation; a structured body is rendered at zero
     * indent as a best-effort fallback.
     */
    private String renderBodyInline(Element s) {
      if (s instanceof Fragment fr) return fr.text().stripLeading();
      StringBuilder sb = new StringBuilder();
      renderStatement(0, s, sb);
      return sb.toString().stripLeading().replaceAll("\\s+$", "");
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
