package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A fragment of a source <em>expression</em> -- the right- or left-hand side of a statement, or an
 * argument / initializer. The {@link SourceRenderer} joins an expression's parts; it does not own
 * the statement terminator.
 *
 * <p>This is the recurring-expression half of the closed idiom set (DEC-3). The node set covers the
 * shapes the codegen idioms actually use: a bare {@link Literal} token, a dotted {@link Member}
 * access, a {@link Call}, a {@link New} construction, parenthesization, a {@link Ternary}, a {@link
 * Binary} operator, and an {@link Assign}. As with {@link Statement.Fragment}, {@link Fragment} is
 * the documented escape for a shape the closed set has not yet learned -- used only when a
 * sub-piece genuinely resists a node, and counted, not celebrated.
 */
public sealed interface Expression
    permits Expression.Fragment,
        Expression.Literal,
        Expression.Member,
        Expression.Call,
        Expression.New,
        Expression.Parens,
        Expression.Ternary,
        Expression.Binary,
        Expression.Assign {

  /**
   * Raw expression text emitted verbatim -- the escape hatch for a sub-expression the closed idiom
   * set has not yet modeled.
   */
  record Fragment(String text) implements Expression {}

  /**
   * A bare token -- an identifier, a literal constant, or a call-argument that carries no
   * surrounding operator of its own. Renders its text verbatim.
   */
  record Literal(String text) implements Expression {}

  /**
   * A dotted member access, {@code receiver.name}; {@code receiver} may be a type name, a variable,
   * or {@code this}. Renders {@code receiver + "." + name}.
   */
  record Member(String receiver, String name) implements Expression {}

  /**
   * A method call, {@code receiver(arg, arg, ...)}; the argument list supplies the parens. An empty
   * argument list renders {@code receiver()}.
   */
  record Call(Expression receiver, List<Expression> args) implements Expression {}

  /** A construction, {@code new type(args)}. */
  record New(String type, List<Expression> args) implements Expression {}

  /** Explicit grouping, {@code (inner)}. */
  record Parens(Expression inner) implements Expression {}

  /** A conditional expression, {@code cond ? a : b}. */
  record Ternary(Expression cond, Expression a, Expression b) implements Expression {}

  /** A binary operator, {@code l op r} (e.g. {@code a != null}, {@code a == 0}). */
  record Binary(Expression l, String op, Expression r) implements Expression {}

  /** An assignment, {@code l = r}. */
  record Assign(Expression l, Expression r) implements Expression {}

  /* ---- factory helpers: small, named, fluent ----------------------------------------------- */

  static Fragment frag(String text) {
    return new Fragment(text);
  }

  static Literal lit(String text) {
    return new Literal(text);
  }

  static Member member(String receiver, String name) {
    return new Member(receiver, name);
  }

  static Call call(Expression receiver, List<Expression> args) {
    return new Call(receiver, args);
  }

  static Call call(Expression receiver, Expression... args) {
    return new Call(receiver, List.of(args));
  }

  static New newExpr(String type, Expression... args) {
    return new New(type, args.length == 0 ? List.of() : List.of(args));
  }

  static Parens parens(Expression inner) {
    return new Parens(inner);
  }

  static Ternary ternary(Expression cond, Expression a, Expression b) {
    return new Ternary(cond, a, b);
  }

  static Binary binary(Expression l, String op, Expression r) {
    return new Binary(l, op, r);
  }

  static Assign assign(Expression l, Expression r) {
    return new Assign(l, r);
  }
}
