package bijnagte.recordbuf.codegen.source;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * A member of a type body. The pure-Java AST is structural here: a member is a declaration (a
 * component {@link Field}, an {@link EnumConstant}, a nested {@link Type}, a {@link Method}, a
 * {@link Constructor}, a {@link StaticBlock}) the enclosing {@link Type} owns. The proto logic
 * assembles these from its model; the module never sees a proto.
 *
 * <p>A {@link Method method} or {@link Constructor} carries a body that is just a {@code List} of
 * {@link Line}s -- it does not model a nested expression-statement AST. That is the deliberate
 * hybrid: structure at the type/member level, line-emission at the method-body level.
 */
public sealed interface Member {

  /**
   * A record component ({@code type name}) or a type-body field. When {@code initializer} is
   * non-blank it renders after the name as {@code = initializer} -- the {@code BY_NUMBER}
   * reverse-lookup field is {@code ... BY_NUMBER = new HashMap<>();}; blank means no initializer.
   *
   * <p>The {@code type} and {@code initializer} strings are <em>pre-resolved short form</em>: the
   * caller has already run their type references through the {@code TypeCollector} and inlined each
   * short name (a generic field type like {@code Map<Integer, Color>} is passed fully spelled out,
   * because the collector strips generic arguments), and the renderer emits them verbatim.
   */
  @RecordBuilder
  record Field(
      Javadoc javadoc,
      Modifiers mods,
      String type,
      String name,
      boolean deprecated,
      String initializer)
      implements Member {
    // Optional fields stay null when a builder leaves them unset; the compact ctor seeds them:
    // javadoc -> empty (renders nothing), mods -> none, initializer -> blank. `deprecated` is a
    // primitive and defaults to false in the builder; `type`/`name` are required.
    public Field {
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      mods = mods == null ? Modifiers.none() : mods;
      initializer = initializer == null ? "" : initializer;
    }
  }

  /**
   * An {@code enum} constant: {@code NAME} with optional ctor args {@code (ctorArgs)} (the proto
   * number). A null/blank {@code ctorArgs} renders the bare {@code NAME}.
   */
  @RecordBuilder
  record EnumConstant(String name, String ctorArgs, Javadoc javadoc, boolean deprecated)
      implements Member {
    // Optional fields stay null when unset; the compact ctor seeds them: ctorArgs -> blank (renders
    // the bare NAME when the proto constant has no number), javadoc -> empty. `deprecated` is a
    // primitive (false by default); `name` is required.
    public EnumConstant {
      ctorArgs = ctorArgs == null ? "" : ctorArgs;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    }
  }

  /**
   * A method. {@code mods returnType name(params)[ throws thrown] { body }}. Built via the
   * {@code @RecordBuilder}-generated fluent {@link Builder}, so a caller sets only the elements it
   * needs; the <em>compact constructor</em> seeds the optional fields (javadoc, modifiers, {@code
   * throws} types, body) to their defaults when the caller leaves them unset, which keeps the
   * record's accessors non-null. Each {@code thrown} type is resolved through the collector; a
   * non-empty list renders a {@code throws} clause between the params and the block. An {@code
   * abstract} method (the {@code interface} case) renders the declaration followed by {@code ;} and
   * no block.
   */
  @RecordBuilder
  record Method(
      Javadoc javadoc,
      Modifiers mods,
      String returnType,
      String name,
      String params,
      List<String> thrown,
      List<Line> body,
      boolean abstractDecl,
      boolean override) // M4-C2b-2b-5: @Override before the modifier line
      implements Member {
    // The builder leaves the optional fields unset (null); the compact constructor seeds them to
    // their defaults so a caller sets only what it needs and the accessors stay non-null. (public
    // because Method is a public nested record, so its compact ctor must match its access.)
    public Method {
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      mods = mods == null ? Modifiers.none() : mods;
      thrown = thrown == null ? List.of() : thrown;
      body = body == null ? List.of() : body;
    }
  }

  /** A constructor: {@code mods name(params) { body }}. */
  @RecordBuilder
  record Constructor(Javadoc javadoc, Modifiers mods, String name, String params, List<Line> body)
      implements Member {
    // Optional fields stay null when unset; the compact ctor seeds them: javadoc -> empty, mods ->
    // none, body -> empty list (an empty method/ctor body, e.g. a private no-arg Codec ctor).
    // `name`
    // and `params` are required.
    public Constructor {
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      mods = mods == null ? Modifiers.none() : mods;
      body = body == null ? List.of() : body;
    }
  }

  /** A {@code static { ... }} initializer. */
  record StaticBlock(List<Line> body) implements Member {}

  /** A nested type -- another {@link Type} owned by this type's body (a "fragment"). */
  record Nested(Type type) implements Member {}
}
