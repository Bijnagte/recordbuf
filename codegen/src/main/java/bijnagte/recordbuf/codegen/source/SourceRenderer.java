package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.util.List;

/**
 * Renders a {@link Type} tree to Java source, top-down. Import resolution is two-phase (collect the
 * whole tree into a {@link TypeCollector}, {@link TypeCollector#finalizeResolution() finalize},
 * then render). Structure is rendered from the AST; method/constructor/static-block bodies are the
 * {@code List} of {@link Element}s (idiom nodes + the {@link Fragment} escape) joined verbatim in
 * document order.
 *
 * <p>The renderer emits valid Java <em>without any indentation</em>: it writes tokens and newlines
 * but no leading whitespace, and leaves all whitespace layout (indentation, blank lines, brace
 * style, Javadoc alignment) to the {@link Formatter} the caller passes to {@link #render(JavaFile,
 * Formatter)}. This is why a generated surface is fed through a GJF formatter -- the renderer owns
 * structure, the formatter owns layout. When no formatter is applied (the no-arg / identity path,
 * used by unit tests) the output is a compact-but-valid source the compiler accepts as-is.
 *
 * <p>The renderer dispatches on the concrete {@link Type} variant and renders that variant's own
 * typed component lists directly -- a record's {@code components}, an enum's {@code enumConstants},
 * a class's fields/constructors/static blocks -- in canonical order. It never assembles or
 * re-partitions a flat member stream; each variant stores exactly the members it can have, and the
 * per-list helpers render them.
 */
public final class SourceRenderer {

  private SourceRenderer() {}

  /** Renders a whole file with no formatter -- compact-but-valid source, no indentation. */
  public static String render(JavaFile file) {
    return render(file, Formatter.IDENTITY);
  }

  /**
   * Renders a whole file, then post-processes it through {@code formatter}. The render pass itself
   * emits no indentation, so the produced surface is valid for the compiler whether or not a
   * formatter is applied; when one is (GJF in production) it owns all whitespace layout.
   */
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
    p.render(file.top(), out);
    return formatter.format(out.toString());
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

    void render(Type t, StringBuilder out) {
      switch (t) {
        case Type.Record r -> renderRecord(r, out);
        case Type.Class c -> renderClass(c, out);
        case Type.Enum e -> renderEnum(e, out);
        case Type.Interface iface -> renderInterface(iface, out);
      }
    }

    private void renderRecord(Type.Record r, StringBuilder out) {
      renderAnnotations(r.annotations(), out);
      if (r.javadoc().present()) out.append(javadoc(r.javadoc().raw())).append('\n');
      out.append(r.mods().withSpace()).append("record ").append(r.name()).append('(');
      var comps = r.components();
      if (comps.isEmpty()) {
        out.append(')');
      } else {
        for (int i = 0; i < comps.size(); i++) {
          out.append('\n');
          Component f = comps.get(i);
          renderAnnotations(f.annotations(), out);
          if (f.javadoc().present()) {
            out.append(javadoc(f.javadoc().raw())).append('\n');
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
      renderBlockList(r.staticBlocks(), first, out);
      renderCtorList(r.constructors(), first, out, r.name());
      renderMethodList(r.methods(), first, out);
      renderNestedList(r.nested(), first, out);
      out.append('}').append('\n');
    }

    private void renderClass(Type.Class c, StringBuilder out) {
      // The uniform annotation model renders first, then the legacy raw-annotation string escape
      // hatch (rawAnnotations) -- each on its own line, before the Javadoc.
      renderAnnotations(c.annotations(), out);
      for (String ann : c.rawAnnotations()) {
        out.append(ann).append('\n');
      }
      if (c.javadoc().present()) {
        out.append(javadoc(c.javadoc().raw())).append('\n');
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
      renderFieldList(c.fields(), first, out);
      renderBlockList(c.staticBlocks(), first, out);
      renderCtorList(c.constructors(), first, out, c.name());
      renderMethodList(c.methods(), first, out);
      renderNestedList(c.nested(), first, out);
      out.append('}').append('\n');
    }

    private void renderEnum(Type.Enum e, StringBuilder out) {
      renderAnnotations(e.annotations(), out);
      if (e.javadoc().present()) out.append(javadoc(e.javadoc().raw())).append('\n');
      out.append(e.mods().withSpace()).append("enum ").append(e.name());
      if (!e.implementsFqns().isEmpty()) {
        out.append(" implements ").append(joinResolved(e.implementsFqns()));
      }
      out.append(" {\n");
      var consts = e.enumConstants();
      for (int i = 0; i < consts.size(); i++) {
        EnumConstant c = consts.get(i);
        // The optional per-constant Javadoc first (a formatter owns its alignment); the constant
        // name + ctor args follow on the same line at zero indent.
        renderAnnotations(c.annotations(), out);
        if (c.javadoc().present()) {
          out.append(javadoc(c.javadoc().raw())).append('\n');
        }
        out.append(c.name());
        if (!c.ctorArgs().isEmpty())
          out.append("(").append(String.join(", ", c.ctorArgs())).append(")");
        out.append(i + 1 == consts.size() ? ";" : ",").append('\n');
      }
      // A member-bearing enum with NO constants still needs a `;` right after the opening brace
      // (Java requires a constant list or a `;` to open the member section); without it the first
      // body member is a syntax error. GJF -- which now parses every rendered surface -- surfaces
      // this; emit the `;` so a constants-less enum with fields/ctors/methods stays valid Java.
      if (consts.isEmpty()
          && (e.fields().isEmpty() == false
              || !e.staticBlocks().isEmpty()
              || !e.constructors().isEmpty()
              || !e.methods().isEmpty()
              || !e.nested().isEmpty())) {
        out.append(";\n");
      }
      // A body only follows the constants when they exist; when the enum has constants the first
      // body member is separated from them by a blank line (first starts false), otherwise the
      // first
      // body member renders with no leading blank.
      boolean[] first = {consts.isEmpty()};
      renderFieldList(e.fields(), first, out);
      renderBlockList(e.staticBlocks(), first, out);
      renderCtorList(e.constructors(), first, out, e.name());
      renderMethodList(e.methods(), first, out);
      renderNestedList(e.nested(), first, out);
      out.append('}').append('\n');
    }

    private void renderInterface(Type.Interface iface, StringBuilder out) {
      renderAnnotations(iface.annotations(), out);
      if (iface.javadoc().present()) {
        out.append(javadoc(iface.javadoc().raw())).append('\n');
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
      renderMethodList(iface.methods(), first, out);
      renderNestedList(iface.nested(), first, out);
      out.append('}').append('\n');
    }

    /* ---- per-list body rendering --------------------------------------
     * Each helper renders one typed list into the shared output, separating members by a blank line.
     * `first` threads the "has a prior member been rendered in this body?" flag across the per-list
     * calls so the very first member of a body takes no leading blank line and later members do. A
     * formatter owns indentation, so only the blank-line separation survives here.
     */

    private void renderFieldList(List<Component> fields, boolean[] first, StringBuilder out) {
      for (Component f : fields) {
        if (!first[0]) out.append('\n');
        renderField(f, out);
        first[0] = false;
      }
    }

    private void renderBlockList(List<StaticBlock> blocks, boolean[] first, StringBuilder out) {
      for (StaticBlock b : blocks) {
        if (!first[0]) out.append('\n');
        renderStaticBlock(b, out);
        first[0] = false;
      }
    }

    private void renderCtorList(
        List<Constructor> ctors, boolean[] first, StringBuilder out, String enclosing) {
      for (Constructor c : ctors) {
        if (!first[0]) out.append('\n');
        renderConstructor(c, out, enclosing);
        first[0] = false;
      }
    }

    private void renderMethodList(List<Method> methods, boolean[] first, StringBuilder out) {
      for (Method m : methods) {
        if (!first[0]) out.append('\n');
        renderMethod(m, out);
        first[0] = false;
      }
    }

    private void renderNestedList(List<Type> nested, boolean[] first, StringBuilder out) {
      for (Type n : nested) {
        if (!first[0]) out.append('\n');
        render(n, out);
        first[0] = false;
      }
    }

    /* ---- single member renderers --------------------------------------- */

    /**
     * Render one class/enum body field. Its type + initializer are pre-resolved short form, so they
     * are emitted verbatim -- a second {@code ci.use} would mangle a generic like {@code
     * Map<Integer, Color>}.
     */
    private void renderField(Component f, StringBuilder out) {
      renderAnnotations(f.annotations(), out);
      if (f.javadoc().present()) out.append(javadoc(f.javadoc().raw())).append('\n');
      out.append(f.mods().withSpace());
      out.append(f.type()).append(' ').append(f.name());
      if (f.initializer() != null && !f.initializer().isBlank()) {
        out.append(" = ").append(f.initializer());
      }
      out.append(";\n");
    }

    private void renderStaticBlock(StaticBlock s, StringBuilder out) {
      out.append("static {\n");
      for (Element st : s.body()) renderStatement(st, out);
      out.append('}').append('\n');
    }

    /** Render a node's uniform annotation list: each {@code @Name(args)} on its own line. */
    private void renderAnnotations(List<Annotation> anns, StringBuilder out) {
      for (Annotation a : anns) {
        out.append('@').append(ci.use(a.type()));
        if (!a.args().isEmpty()) {
          out.append('(');
          for (int i = 0; i < a.args().size(); i++) {
            if (i > 0) out.append(", ");
            out.append(renderArg(a.args().get(i)));
          }
          out.append(')');
        }
        out.append('\n');
      }
    }

    /**
     * Render one annotation argument: the value(s), positionally (no {@code name =}) when the name
     * is null/blank/"value", else {@code name = value}. A single value renders as a quoted string;
     * several as a {@code {...}} array literal. GJF owns the final layout.
     */
    private String renderArg(Arg arg) {
      StringBuilder sb = new StringBuilder();
      String name = arg.name();
      boolean positional = name == null || name.isBlank() || "value".equals(name);
      if (!positional && !arg.values().isEmpty()) sb.append(name).append('=');
      List<String> vals = arg.values();
      if (vals.size() == 1) {
        sb.append('"').append(vals.get(0)).append('"');
      } else {
        sb.append('{');
        for (int i = 0; i < vals.size(); i++) {
          if (i > 0) sb.append(", ");
          sb.append('"').append(vals.get(i)).append('"');
        }
        sb.append('}');
      }
      return sb.toString();
    }

    private void renderConstructor(Constructor c, StringBuilder out, String enclosing) {
      if (c.javadoc().present()) out.append(javadoc(c.javadoc().raw())).append('\n');
      if (c.body().isEmpty()) {
        out.append(c.mods().withSpace())
            .append(enclosing)
            .append(renderParams(c.params()))
            .append("{}\n");
        return;
      }
      out.append(c.mods().withSpace())
          .append(enclosing)
          .append(renderParams(c.params()))
          .append(" {\n");
      for (Element s : c.body()) renderStatement(s, out);
      out.append('}').append('\n');
    }

    private void renderMethod(Method m, StringBuilder out) {
      // Annotations render first (e.g. @Override), before the Javadoc + the modifier line.
      renderAnnotations(m.annotations(), out);
      if (m.javadoc().present()) out.append(javadoc(m.javadoc().raw())).append('\n');
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
      for (Element s : m.body()) renderStatement(s, out);
      out.append('}').append('\n');
    }

    /* ---- helpers --------------------------------------------------------- */

    /**
     * Render one body element -- the idiom layout pass lives here; a formatter owns indentation.
     */
    private void renderStatement(Element s, StringBuilder out) {
      switch (s) {
        case Fragment fr -> out.append(fr.text()).append('\n');
        case Return r ->
            out.append(r.expr() == null ? "return;" : "return " + r.expr() + ";").append('\n');
        case Throw t -> out.append("throw ").append(t.expr()).append(';').append('\n');
        case ExprStatement e -> out.append(e.expr()).append(";\n");
        case LocalVar lv ->
            out.append(lv.type())
                .append(' ')
                .append(lv.name())
                .append(lv.init() == null ? ";" : " = " + lv.init() + ";")
                .append('\n');
        case Comment c -> out.append("// ").append(c.text()).append('\n');
        case If i -> renderIf(i, out);
        case While w -> renderWhile(w, out);
        case For f -> renderFor(f, out);
        case Switch sw -> renderSwitch(sw, out);
      }
    }

    /**
     * Render an {@link If} control-flow chain -- the {@code if}, its {@code else-if} clauses, and
     * an optional terminal {@code else}. Each clause renders its own braces ({@code } else if
     * (...)} / {@code } else {}} on the preceding close-brace line) and lays its element body out
     * via {@link #renderStatement}; an empty branch list renders no clause.
     */
    private void renderIf(If ifStmt, StringBuilder out) {
      out.append("if (").append(ifStmt.condition()).append(") {\n");
      for (Element s : ifStmt.body()) renderStatement(s, out);
      for (ElseIf ei : ifStmt.elseIf()) {
        out.append("} else if (").append(ei.condition()).append(") {\n");
        for (Element s : ei.body()) renderStatement(s, out);
      }
      if (!ifStmt.elseBody().isEmpty()) {
        out.append("} else {\n");
        for (Element s : ifStmt.elseBody()) renderStatement(s, out);
      }
      out.append('}').append('\n');
    }

    /**
     * Render a {@link While} loop -- the {@code while (condition) {}} header and its element body,
     * closed by its own brace. A {@code while} has no branch and no optional {@code else} clause,
     * so it always closes on its own; an empty body renders the empty-loop case {@code while (c)
     * {}}.
     */
    private void renderWhile(While w, StringBuilder out) {
      out.append("while (").append(w.condition()).append(") {\n");
      for (Element s : w.body()) renderStatement(s, out);
      out.append('}').append('\n');
    }

    /**
     * Render a for-each loop -- the {@code for (pattern : iterable) {}} header and its element
     * body, closed by its own brace. A for-each always closes on its own; an empty body renders the
     * empty-loop case {@code for (p : x) {}}.
     */
    private void renderFor(For f, StringBuilder out) {
      out.append("for (").append(f.pattern()).append(" : ").append(f.iterable()).append(") {\n");
      for (Element s : f.body()) renderStatement(s, out);
      out.append('}').append('\n');
    }

    /**
     * Render a {@code switch} element -- the {@code switch (subject) {}} header, its {@link Case}
     * arms and terminal {@code default ->} arm, closed by its own brace. Arm braces are decided by
     * arm size (auto-brace): an empty arm renders {@code -> {}}, a single-element arm renders
     * un-braced {@code -> stmt;} (required for switch-expression yields), and a multi-element arm
     * braces {@code -> { … }}.
     */
    private void renderSwitch(Switch sw, StringBuilder out) {
      out.append("switch (").append(sw.subject()).append(") {\n");
      for (Case c : sw.cases()) renderCase(c, out);
      // Render the terminal default arm only when it has a body, mirroring If's optional elseBody:
      // an empty defaultBody omits the default clause entirely.
      if (!sw.defaultBody().isEmpty()) renderDefaultArm(sw.defaultBody(), out);
      out.append('}').append('\n');
    }

    private void renderCase(Case c, StringBuilder out) {
      out.append("case ").append(String.join(", ", c.patterns())).append(" -> ");
      renderArmBody(c.body(), out);
    }

    private void renderDefaultArm(List<Element> body, StringBuilder out) {
      out.append("default -> ");
      renderArmBody(body, out);
    }

    /**
     * Render one arm's body -- the auto-brace rule the user chose: empty renders {@code -> {}}, one
     * element renders un-braced {@code -> stmt;} (so it can yield as a switch-expression arm), more
     * than one braces {@code -> { … }}.
     */
    private void renderArmBody(List<Element> body, StringBuilder out) {
      if (body.isEmpty()) {
        out.append("{}\n");
      } else if (body.size() == 1) {
        out.append(renderBodyInline(body.get(0))).append('\n');
      } else {
        out.append("{\n");
        for (Element s : body) renderStatement(s, out);
        out.append('}').append('\n');
      }
    }

    /**
     * Render a single-element arm body inline (no leading whitespace, no trailing newline) so it
     * can sit on the arm's {@code case … -> } line. For a {@link Fragment} the trailing whitespace
     * is trimmed so it ends the arm line cleanly; a structured body is rendered then trimmed as a
     * best-effort fallback.
     */
    private String renderBodyInline(Element s) {
      if (s instanceof Fragment fr) return fr.text().stripTrailing();
      StringBuilder sb = new StringBuilder();
      renderStatement(s, sb);
      return sb.toString().stripTrailing();
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
   * Render a Javadoc block comment from raw text. Null or blank {@code raw} returns the empty
   * string (renders nothing). Emitted at zero indentation -- a formatter aligns it.
   */
  static String javadoc(String raw) {
    if (raw == null || raw.isBlank()) return "";
    StringBuilder sb = new StringBuilder();
    sb.append("/**\n");
    for (String l : raw.strip().lines().toList()) {
      if (l.isEmpty()) {
        sb.append(" *\n");
      } else {
        sb.append(" * ").append(escapeHtml(l)).append('\n');
      }
    }
    sb.append("*/");
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
