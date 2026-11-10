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
 * <p>That split -- structural AST at the type/member level, line-emission at the method-body level
 * -- is the "structural AST, line-emission bodies" hybrid the user specified for M4.
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

    void collect(Type t) {
      t.implementsFqns().forEach(ci::use);
      if (t instanceof Type.Interface iface) {
        iface.extendsFqns().forEach(ci::use);
        // M4-C2b-2b-12: permitsFqns are pre-resolved relative references
        // (e.g. "Choice.NameValue") or bare short names; the renderer emits them verbatim
        // because nested types within the interface are not import-eligible.
      }
      for (Member mem : t.members()) {
        switch (mem) {
          case Member.Field f -> ci.use(f.type());
          case Member.Method mt -> {
            ci.use(mt.returnType());
            mt.thrown().forEach(ci::use);
          }
          case Member.Nested n -> collect(n.type());
          default -> {}
        }
      }
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
      List<Member.Field> comps = new ArrayList<>();
      List<Member> bodyMembers = new ArrayList<>();
      for (Member m : r.members()) {
        if (m instanceof Member.Field f) comps.add(f);
        else bodyMembers.add(m);
      }

      out.append(indent(indent));
      if (r.javadoc().present()) out.append(javadoc(r.javadoc().raw(), indent)).append('\n');
      if (r.deprecated()) out.append('@').append("Deprecated\n").append(indent(indent));
      out.append(r.mods().withSpace()).append("record ").append(r.name()).append('(');
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
        renderBodyMember(bodyMembers.get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- enum ------------------------------------------------------------ */

    private void renderEnum(Type.Enum e, int indent, StringBuilder out) {
      int body = indent + 1;
      List<Member.EnumConstant> consts = new ArrayList<>();
      List<Member> rest = new ArrayList<>();
      for (Member m : e.members()) {
        if (m instanceof Member.EnumConstant c) consts.add(c);
        else rest.add(m);
      }
      out.append(indent(indent));
      if (e.javadoc().present()) out.append(javadoc(e.javadoc().raw(), indent)).append('\n');
      if (e.deprecated()) out.append('@').append("Deprecated\n").append(indent(indent));
      out.append(e.mods().withSpace()).append("enum ").append(e.name());
      if (!e.implementsFqns().isEmpty()) {
        out.append(" implements ").append(joinResolved(e.implementsFqns()));
      }
      out.append(" {\n");
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
        if (c.ctorArgs() != null && !c.ctorArgs().isEmpty()) out.append(c.ctorArgs());
        out.append(i + 1 == consts.size() ? ";" : ",").append('\n');
      }
      for (int i = 0; i < rest.size(); i++) {
        if (i > 0 || !consts.isEmpty()) out.append('\n');
        renderBodyMember(rest.get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- class --------------------------------------------------------- */

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
      if (c.members().isEmpty()) {
        out.append(" {}\n");
        return;
      }
      out.append(" {\n");
      for (int i = 0; i < c.members().size(); i++) {
        if (i > 0) out.append('\n');
        renderBodyMember(c.members().get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- interface -------------------------------------------------- */

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
      if (iface.members().isEmpty()) {
        out.append(" {}\n");
        return;
      }
      out.append(" {\n");
      for (int i = 0; i < iface.members().size(); i++) {
        if (i > 0) out.append('\n');
        renderBodyMember(iface.members().get(i), body, out);
      }
      out.append(indent(indent)).append('}').append('\n');
    }

    /* ---- body members -------------------------------------------------- */

    private void renderBodyMember(Member mem, int indent, StringBuilder out) {
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
          // M4-C2b-2b-5: @Override annotation before the modifier line.
          if (m.override()) out.append("@Override\n").append(indent(indent));
          String sig = m.mods().withSpace() + ci.use(m.returnType()) + " " + m.name() + m.params();
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
            out.append(c.mods().withSpace()).append(c.name()).append(c.params()).append(" {}\n");
          } else {
            out.append(c.mods().withSpace()).append(c.name()).append(c.params()).append(" {\n");
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
        case Member.EnumConstant ignored -> {}
      }
    }

    /* ---- helpers --------------------------------------------------------- */

    /** Render one body statement at the block's indent -- the idiom layout pass lives here. */
    private void renderStatement(int body, Statement s, StringBuilder out) {
      switch (s) {
        case Statement.Fragment fr -> out.append(indent(body)).append(fr.text()).append('\n');
      }
    }

    private String joinResolved(List<String> fqns) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < fqns.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(ci.use(fqns.get(i)));
      }
      return sb.toString();
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
