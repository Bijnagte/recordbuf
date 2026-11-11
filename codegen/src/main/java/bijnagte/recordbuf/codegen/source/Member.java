package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A member of a type body. The pure-Java AST is structural here: a member is a declaration (a
 * component {@link Field}, an {@link EnumConstant}, a nested {@link Type}, a {@link Method}, a
 * {@link Constructor}, a {@link StaticBlock}) the enclosing {@link Type} owns. The proto logic
 * assembles these from its model; the module never sees a proto.
 *
 * <p>A {@link Method method} or {@link Constructor} carries a body that is a {@code List} of {@link
 * Statement}s -- control-flow and statement idioms ({@code If}/{@code For}/.../ {@code Return}/
 * {@code Throw}) plus the {@link Statement.Fragment} escape -- which the {@link SourceRenderer}
 * lays out; it is not a flat list of hand-indented strings.
 *
 * <p>Each variant is built via a hand-written, ergonomic nested {@code Builder}: named modifier
 * toggles ({@code public_()}/{@code static_()}/{@code final_()}/...), collection accumulators
 * ({@code thrown()}/{@code body()} ...), and the raw component setters. Compact constructors still
 * seed optional fields to their null-safe defaults, so {@code new Member.Method(...)} callers work
 * unchanged.
 */
public sealed interface Member {

  /**
   * A record component ({@code type name}) or a type-body field. When {@code initializer} is
   * non-blank it renders after the name as {@code = initializer} -- the {@code BY_NUMBER}
   * reverse-lookup field is {@code ... BY_NUMBER = new HashMap<>();}; blank means no initializer.
   *
   * <p>The {@code type} and {@code initializer} strings are <em>pre-resolved short form</em>: the
   * caller has already run their type references through the {@code TypeCollector} and inlined each
   * short name, and the renderer emits them verbatim.
   */
  record Field(
      Javadoc javadoc,
      Modifiers mods,
      String type,
      String name,
      boolean deprecated,
      String initializer)
      implements Member {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // javadoc -> empty (renders nothing), mods -> none, initializer -> blank. `deprecated` is a
    // primitive (false by default); `type`/`name` are required.
    public Field {
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      mods = mods == null ? Modifiers.none() : mods;
      initializer = initializer == null ? "" : initializer;
    }

    /** A fresh field builder. */
    public static Builder builder() {
      return new Builder();
    }

    /** An accumulating, ergonomic {@link Field} builder. */
    public static final class Builder {
      private Javadoc javadoc;
      private final Modifiers.Builder mods = Modifiers.builder();
      private String type;
      private String name;
      private boolean deprecated;
      private String initializer;

      /** The field's Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Set the (pre-resolved short-form) type. */
      public Builder type(String t) {
        this.type = t;
        return this;
      }

      /** Set the name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /** Mark the field {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Set the initializer expression (blank/absent means none). */
      public Builder initializer(String i) {
        this.initializer = i;
        return this;
      }

      /** Set the {@code public} visibility. */
      public Builder public_() {
        mods.public_();
        return this;
      }

      /** Set the {@code private} visibility. */
      public Builder private_() {
        mods.private_();
        return this;
      }

      /** Set the visibility dimension directly. */
      public Builder visibility(Modifiers.Visibility v) {
        mods.visibility(v);
        return this;
      }

      /** Set the {@code static} flag. */
      public Builder static_() {
        mods.static_();
        return this;
      }

      /** Set the {@code final} flag. */
      public Builder final_() {
        mods.final_();
        return this;
      }

      /** Set the {@code sealed} flag. */
      public Builder sealed_() {
        mods.sealed_();
        return this;
      }

      /** Set the {@code default} flag. */
      public Builder default_() {
        mods.default_();
        return this;
      }

      /** Construct the {@link Field}. */
      public Member.Field build() {
        return new Member.Field(javadoc, mods.build(), type, name, deprecated, initializer);
      }
    }
  }

  /**
   * An {@code enum} constant: {@code NAME} with optional ctor args {@code (…)} (the proto number).
   * The ctor args are a list of value expressions; the renderer wraps the list in parens, so a
   * caller supplies the args without parens. An empty list renders the bare {@code NAME}.
   */
  record EnumConstant(String name, List<String> ctorArgs, Javadoc javadoc, boolean deprecated)
      implements Member {
    // ctorArgs -> empty (renders the bare NAME when the list is empty); javadoc -> empty.
    // `deprecated` is a primitive (false by default); `name` is required.
    public EnumConstant {
      ctorArgs = ctorArgs == null ? List.of() : ctorArgs;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    }

    /** A fresh enum-constant builder. */
    public static Builder builder() {
      return new Builder();
    }

    /** An accumulating, ergonomic {@link EnumConstant} builder. */
    public static final class Builder {
      private String name;
      private final List<String> ctorArgs = new ArrayList<>();
      private Javadoc javadoc;
      private boolean deprecated;

      /** Set the constant name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /**
       * Append one or more ctor arg value expressions (e.g. the proto number). Calls accumulate,
       * and a single call may take several; no args renders the bare name. The renderer supplies
       * the surrounding parens.
       */
      public Builder ctorArgs(String... args) {
        for (String arg : args) {
          this.ctorArgs.add(arg);
        }
        return this;
      }

      /** The constant's Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Mark the constant {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Construct the {@link EnumConstant}. */
      public Member.EnumConstant build() {
        return new Member.EnumConstant(name, List.copyOf(ctorArgs), javadoc, deprecated);
      }
    }
  }

  /**
   * A method. {@code mods returnType name(params)[ throws thrown] { body }}. Built via the
   * hand-written fluent {@link Builder}, so a caller sets only the elements it needs; the
   * <em>compact constructor</em> seeds the optional fields (javadoc, modifiers, {@code throws}
   * types, body) to their defaults when the caller leaves them unset, which keeps the record's
   * accessors non-null. Each {@code thrown} type is resolved through the collector; a non-empty
   * list renders a {@code throws} clause between the params and the block. An {@code abstract}
   * method (the {@code interface} case) renders the declaration followed by {@code ;} and no block.
   */
  record Method(
      Javadoc javadoc,
      Modifiers mods,
      String returnType,
      String name,
      List<Param> params,
      List<String> thrown,
      List<Statement> body,
      boolean abstractDecl,
      boolean override)
      implements Member {
    // The builder leaves the optional fields unset (null); the compact constructor seeds them to
    // their defaults so a caller sets only what it needs and the accessors stay non-null.
    public Method {
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      mods = mods == null ? Modifiers.none() : mods;
      params = params == null ? List.of() : params;
      thrown = thrown == null ? List.of() : thrown;
      body = body == null ? List.of() : body;
      returnType = returnType == null ? "void" : returnType;
    }

    /**
     * Entry point: a method identified by {@link #name}, the one always-required field, taken at
     * construction — the optional elements accumulate as before.
     */
    public static Builder named(String name) {
      return new Builder(name);
    }

    /** An accumulating, ergonomic {@link Method} builder. */
    public static final class Builder {
      private Javadoc javadoc;
      private final Modifiers.Builder mods = Modifiers.builder();
      private String returnType;
      private final String name;
      private final List<Param> params = new ArrayList<>();
      private final List<String> thrown = new ArrayList<>();
      private final List<Statement> body = new ArrayList<>();
      private boolean abstractDecl;
      private boolean override;

      /** Seed the {@link #name} (the one always-required field); the rest accumulate. */
      public Builder(String name) {
        this.name = name;
      }

      /** The method's Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Set the return type. */
      public Builder returnType(String rt) {
        this.returnType = rt;
        return this;
      }

      /** Append one parameter (type + name); calls accumulate, and no params renders {@code ()}. */
      public Builder param(String type, String name) {
        this.params.add(new Param(type, name));
        return this;
      }

      /** Append one {@code throws} type. */
      public Builder thrown(String t) {
        this.thrown.add(t);
        return this;
      }

      /** Append one or more body statements; calls accumulate. */
      public Builder body(Statement... statements) {
        for (Statement s : statements) {
          this.body.add(s);
        }
        return this;
      }

      /** Append an existing sequence of body statements; calls accumulate. */
      public Builder body(Iterable<Statement> statements) {
        if (statements != null) {
          for (Statement s : statements) {
            this.body.add(s);
          }
        }
        return this;
      }

      /** Mark this an abstract declaration ({@code ;}, no block). */
      public Builder abstractDecl(boolean a) {
        this.abstractDecl = a;
        return this;
      }

      /** Render {@code @Override} before the modifier line. */
      public Builder override(boolean o) {
        this.override = o;
        return this;
      }

      /** Set the {@code public} visibility. */
      public Builder public_() {
        mods.public_();
        return this;
      }

      /** Set the {@code private} visibility. */
      public Builder private_() {
        mods.private_();
        return this;
      }

      /** Set the visibility dimension directly. */
      public Builder visibility(Modifiers.Visibility v) {
        mods.visibility(v);
        return this;
      }

      /** Set the {@code static} flag. */
      public Builder static_() {
        mods.static_();
        return this;
      }

      /** Set the {@code final} flag. */
      public Builder final_() {
        mods.final_();
        return this;
      }

      /** Set the {@code default} flag. */
      public Builder default_() {
        mods.default_();
        return this;
      }

      /** Construct the {@link Method}. */
      public Member.Method build() {
        return new Member.Method(
            javadoc,
            mods.build(),
            returnType,
            name,
            List.copyOf(params),
            List.copyOf(thrown),
            List.copyOf(body),
            abstractDecl,
            override);
      }
    }
  }

  /** A constructor: {@code mods name(params) { body }}. */
  record Constructor(
      Javadoc javadoc, Modifiers mods, String name, List<Param> params, List<Statement> body)
      implements Member {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // javadoc -> empty, mods -> none, body -> empty list (an empty ctor body, e.g. a private no-arg
    // Codec ctor). `name` and `params` are required.
    public Constructor {
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      mods = mods == null ? Modifiers.none() : mods;
      params = params == null ? List.of() : params;
      body = body == null ? List.of() : body;
    }

    /** A fresh constructor builder. */
    public static Builder builder() {
      return new Builder();
    }

    /** An accumulating, ergonomic {@link Constructor} builder. */
    public static final class Builder {
      private Javadoc javadoc;
      private final Modifiers.Builder mods = Modifiers.builder();
      private String name;
      private final List<Param> params = new ArrayList<>();
      private final List<Statement> body = new ArrayList<>();

      /** The constructor's Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Set the constructor name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /** Append one parameter (type + name); calls accumulate, and no params renders {@code ()}. */
      public Builder param(String type, String name) {
        this.params.add(new Param(type, name));
        return this;
      }

      /** Append one or more body statements; calls accumulate. */
      public Builder body(Statement... statements) {
        for (Statement s : statements) {
          this.body.add(s);
        }
        return this;
      }

      /** Append an existing sequence of body statements; calls accumulate. */
      public Builder body(Iterable<Statement> statements) {
        if (statements != null) {
          for (Statement s : statements) {
            this.body.add(s);
          }
        }
        return this;
      }

      /** Set the {@code public} visibility. */
      public Builder public_() {
        mods.public_();
        return this;
      }

      /** Set the {@code private} visibility. */
      public Builder private_() {
        mods.private_();
        return this;
      }

      /** Set the visibility dimension directly. */
      public Builder visibility(Modifiers.Visibility v) {
        mods.visibility(v);
        return this;
      }

      /** Set the {@code static} flag. */
      public Builder static_() {
        mods.static_();
        return this;
      }

      /** Set the {@code final} flag. */
      public Builder final_() {
        mods.final_();
        return this;
      }

      /** Construct the {@link Constructor}. */
      public Member.Constructor build() {
        return new Member.Constructor(
            javadoc, mods.build(), name, List.copyOf(params), List.copyOf(body));
      }
    }
  }

  /** A {@code static { ... }} initializer. */
  record StaticBlock(List<Statement> body) implements Member {}

  /** A nested type -- another {@link Type} owned by this type's body (a "fragment"). */
  record Nested(Type type) implements Member {}
}
