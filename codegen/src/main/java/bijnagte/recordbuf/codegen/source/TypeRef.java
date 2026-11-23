package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.ResolutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// ClassInitializationDeadlock is suppressed on this type: the boxed-primitive constants declared
// below are initialized by the nested Named/Array/Primitive constructors, which reference neither
// each other nor these constants, so no static-initialization cycle exists and the
// conservatively-flagged deadlock cannot occur.

/**
 * A structured type reference that the source AST can hold in a position instead of a pre-resolved
 * short-name string. A {@code TypeRef} is created with a fully-qualified name and resolves lazily
 * to a short name (with import) or a fully-qualified name — depending on collision — at render
 * time.
 *
 * <p>Three variants: {@link Named} (a {@code FQN} reference), {@link Primitive} (a Java primitive
 * type like {@code int} or {@code void}), and {@link Array} (an element type with {@code []}
 * suffix).
 *
 * <p>Resolution (R9 of the 003 plan): a {@link Named} in {@code java.lang} or the declaring package
 * renders without an import; every other {@link Named} is imported; a {@code shortName} shared by
 * two or more distinct fully-qualified names renders in fully-qualified form at every site.
 * Primitives and arrays render by their bare name regardless of the declaring package.
 *
 * <p>For import-line purposes {@link #fqn()} returns the bare fully-qualified name (no generic
 * suffix). At a type position {@link #resolved(String, Set) resolved} returns the form that should
 * be emitted at that site — short (when uncollided) or fully-qualified (when collided).
 */
@SuppressWarnings("ClassInitializationDeadlock")
public sealed interface TypeRef extends Formattable {

  /** The bare fully-qualified name, with no generic suffix (for {@code import} lines). */
  String fqn();

  /** Just the short class name (for collision detection). Primitives return their rendered name. */
  String shortName();

  /**
   * Resolve to the form emitted at a type position at the top level of a file (no enclosing class
   * scope): a short name for an uncollided type, a fully-qualified name for a collided type.
   * Primitives and arrays ignore the collision context (they always render bare).
   *
   * @param declaringPackage the package of the file being generated ({@code null} means the default
   *     package)
   * @param collidedShortNames the set of short names that are shared by two or more distinct FQNs
   * @return the resolved name to emit at this position
   */
  default String resolved(String declaringPackage, Set<String> collidedShortNames) {
    return resolved(declaringPackage, collidedShortNames, List.of());
  }

  /**
   * Scope-aware {@link #resolved(String, Set)} for a type position rendered <em>inside</em> the
   * enclosing types {@code scope} (outermost first). A qualified name whose outermost enclosing
   * type is one of those renders without that leading segment — a member type of an enclosing class
   * is in simple-name scope inside it, so the qualifier is redundant. Primitives and arrays pass
   * the scope to the element/variant resolution where it can apply.
   *
   * @param declaringPackage the package of the file being generated ({@code null} means the default
   *     package)
   * @param collidedShortNames the set of short names that are shared by two or more distinct FQNs
   * @param scope the enclosing type names of the use site, outermost first
   * @return the resolved name to emit at this position
   */
  default String resolved(
      String declaringPackage, Set<String> collidedShortNames, List<String> scope) {
    return resolved(declaringPackage, collidedShortNames);
  }

  /**
   * Default render — delegates to {@link #resolved(String, Set, List)} unpacked via {@link
   * ResolutionContext}. Override in a variant only if it needs different render behavior from its
   * resolution form.
   */
  @Override
  default String render(ResolutionContext ctx) {
    return resolved(ctx.declaringPackage(), ctx.collidedShortNames(), ctx.scope());
  }

  // --- factories ---------------------------------------------------------------

  /**
   * Parse a type reference string into a {@link TypeRef}.
   *
   * <p>Syntax: {@code [pkg.]Short<args>} where {@code args} is an optional comma-separated list of
   *
   * <p>Syntax: {@code [pkg.]Short<args>} where {@code args} is an optional comma-separated list of
   * nested type references. A {@code $} in the simple-name tail separates nested levels (the
   * binary-name form, as produced by {@link Class#getName()}). Also handles bare primitive names
   * (no dot, no angle brackets) and a trailing {@code []} array suffix, which parses as an {@link
   * Array} of the element type.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "java.util.HashMap"} → {@code Named("java.util", "HashMap", [])}
   *   <li>{@code "java.util.List<java.lang.Integer>"} → {@code Named("java.util", "List",
   *       [Named("java.lang", "Integer", [])])}
   *   <li>{@code "int"} → {@code Named("", "int", [])} (bare name, no package)
   *   <li>{@code "java.util.List<String>[]"} → {@code Array(Named("java.util", "List", [Named("",
   *       "String", [])]))}
   *   <li>{@code "com.foo.Outer$Inner"} → {@code Named("com.foo", ["Outer"], "Inner", [])}
   * </ul>
   */
  static TypeRef of(String ref) {
    // Array suffix → wrap the element type in an Array node.
    int arrIdx = ref.length() - 2;
    if (arrIdx >= 0 && ref.charAt(arrIdx) == '[' && ref.charAt(ref.length() - 1) == ']') {
      return new Array(of(ref.substring(0, ref.length() - 2)));
    }
    int lt = ref.indexOf('<');
    if (lt < 0) {
      int lastDot = ref.lastIndexOf('.');
      if (lastDot < 0) {
        return baseNamed("", ref);
      }
      return baseNamed(ref.substring(0, lastDot), ref.substring(lastDot + 1));
    }
    String base = ref.substring(0, lt);
    String argsStr = ref.substring(lt + 1, ref.lastIndexOf('>'));
    int lastDot = base.lastIndexOf('.');
    String pkg = lastDot < 0 ? "" : base.substring(0, lastDot);
    String short_ = lastDot < 0 ? base : base.substring(lastDot + 1);
    List<TypeRef> genericArgs = parseGenericArgs(argsStr);
    Named baseRef = baseNamed(pkg, short_);
    return new Named(pkg, baseRef.enclosing(), baseRef.shortName(), genericArgs);
  }

  /**
   * The base {@link Named} for a package plus the simple-name tail of a type string. A tail with no
   * {@code $} is the type itself; a {@code $}-separated tail is a nested type in binary-name form
   * (the shape of {@link Class#getName()}): each segment becomes one enclosing level, so {@code
   * baseNamed("com.foo", "Outer$Inner")} is {@code com.foo.Outer.Inner} — imported as {@code
   * com.foo.Outer}, rendered {@code Outer.Inner}.
   */
  private static Named baseNamed(String pkg, String tail) {
    String[] segments = tail.split("\\$");
    if (segments.length == 1) {
      return Named.ofBase(pkg, tail);
    }
    List<String> enclosing = new ArrayList<>(List.of(segments));
    String inner = enclosing.remove(enclosing.size() - 1);
    return new Named(pkg, enclosing, inner, List.of());
  }

  /**
   * Parse a comma-separated generic argument list into a list of {@link TypeRef}s.
   *
   * <p>Handles nested angle brackets (e.g. {@code List<Map<String,int>}>): matching is done by
   * depth-tracked scanning, not simple comma splitting.
   */
  private static List<TypeRef> parseGenericArgs(String argsStr) {
    List<TypeRef> args = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < argsStr.length(); i++) {
      char c = argsStr.charAt(i);
      if (c == '<') depth++;
      else if (c == '>') depth--;
      else if (c == ',' && depth == 0) {
        args.add(of(argsStr.substring(start, i).trim()));
        start = i + 1;
      }
    }
    // Last segment (or the whole string if no comma was found)
    if (argsStr.isEmpty()) return List.of();
    args.add(of(argsStr.substring(start).trim()));
    return List.copyOf(args);
  }

  /**
   * Construct a {@link Named} reference from a package, a short name, and generic argument
   * references.
   *
   * @param pkg package prefix (empty string for the default package)
   * @param shortName the simple class/interface name
   * @param generics the generic type arguments
   * @return a new {@link Named} reference
   */
  static Named named(String pkg, String shortName, List<TypeRef> generics) {
    return new Named(pkg, List.of(), shortName, generics);
  }

  /** Convenience: {@link #named(String, String, List)} with varargs. */
  static Named named(String pkg, String shortName, TypeRef... generics) {
    return new Named(pkg, List.of(), shortName, List.of(generics));
  }

  /** Construct an {@link Array} reference wrapping an element type. */
  static Array array(TypeRef element) {
    return new Array(element);
  }

  // --- boxed primitive constants -----------------------------------------------

  /** {@code java.lang.Integer} — the boxed type for protobuf {@code int32}, {@code uint32}, etc. */
  Named INTEGER = Named.ofBase("java.lang", "Integer");

  /** {@code java.lang.Long} — the boxed type for protobuf {@code int64}, {@code uint64}, etc. */
  Named LONG = Named.ofBase("java.lang", "Long");

  /** {@code java.lang.Float} — the boxed type for protobuf {@code float}. */
  Named FLOAT = Named.ofBase("java.lang", "Float");

  /** {@code java.lang.Double} — the boxed type for protobuf {@code double}. */
  Named DOUBLE = Named.ofBase("java.lang", "Double");

  /** {@code java.lang.Boolean} — the boxed type for protobuf {@code bool}. */
  Named BOOLEAN = Named.ofBase("java.lang", "Boolean");

  /**
   * {@code java.lang.Short} — the boxed type for protobuf {@code sint32}/{@code sfixed32} (32-bit).
   */
  Named SHORT = Named.ofBase("java.lang", "Short");

  /** {@code java.lang.Byte} — the boxed type for a single proto byte. */
  Named BYTE = Named.ofBase("java.lang", "Byte");

  /** {@code java.lang.Character} — the boxed type for protobuf {@code uint32} as char. */
  Named CHARACTER = Named.ofBase("java.lang", "Character");

  /** {@code java.lang.String} — the boxed type for protobuf {@code string}. */
  Named STRING = Named.ofBase("java.lang", "String");

  /** {@code java.lang.Object} — the boxed type for an unresolvable scalar (default). */
  Named OBJECT = Named.ofBase("java.lang", "Object");

  /** {@code byte[]} — the byte array type used throughout codecs. */
  Array BYTES = array(Primitive.BYTE);

  /** {@code java.io.IOException} — the standard thrown clause for codec I/O methods. */
  Named IO_EXCEPTION = Named.ofBase("java.io", "IOException");

  /**
   * Resolve the boxed {@link Named} for a protobuf scalar type URL.
   *
   * <p>Replaces the duplicate {@code boxedScalarType}, {@code boxedScalarComponent}, and {@code
   * boxedScalarShortName} switch statements previously duplicated in {@code RecordSourceMapping}
   * and {@code CodecSourceMapping}.
   *
   * @param typeUrl the protobuf scalar type URL (e.g. {@code "int32"}, {@code "uint64"})
   * @return the shared {@link Named} boxed-primitive constant
   */
  static Named boxedFor(String typeUrl) {
    return switch (typeUrl) {
      case "int32", "uint32", "fixed32", "sfixed32", "sint32" -> INTEGER;
      case "int64", "uint64", "fixed64", "sfixed64", "sint64" -> LONG;
      case "float" -> FLOAT;
      case "double" -> DOUBLE;
      case "bool" -> BOOLEAN;
      case "string" -> STRING;
      default -> OBJECT;
    };
  }

  // --- implementations ---------------------------------------------------------

  /**
   * A fully-qualified type reference such as {@code java.util.HashMap}, {@code
   * java.util.List<String>}, or a nested type {@code foo.Bar.Baz}. Generic arguments are stored as
   * a list of nested {@link TypeRef}s; nesting is stored as the ordered chain of enclosing type
   * short names in {@link #enclosing()} (outermost first).
   *
   * @param pkg the declaring package (empty string for the default package)
   * @param enclosing the chain of enclosing type short names, outermost first; empty for a
   *     top-level type. A nested type {@code foo.Bar.Baz} is {@code pkg="foo", enclosing=["Bar"],
   *     shortName="Baz"}.
   * @param shortName the innermost (own) simple type name
   * @param generics the generic type arguments (empty for a non-generic type)
   */
  record Named(String pkg, List<String> enclosing, String shortName, List<TypeRef> generics)
      implements TypeRef {

    public Named {
      enclosing = List.copyOf(enclosing);
      generics = List.copyOf(generics);
    }

    /**
     * Internal factory that does not copy generics. Used by {@link #of(String)} for the base path.
     */
    static Named ofBase(String pkg, String shortName) {
      return new Named(pkg, List.of(), shortName, List.of());
    }

    @Override
    public String fqn() {
      return joinWithDot(pkg, enclosing, shortName);
    }

    @Override
    public String shortName() {
      return shortName;
    }

    /**
     * The FQN this type contributes as an import line: the type's own FQN for a top-level type, or
     * — for a nested type — the package plus its OUTERMOST enclosing type (Java imports that outer
     * type and references the nested path from it), so {@code com.foo.Bar.Baz.Qux} imports {@code
     * com.foo.Bar}.
     */
    public String importFqn() {
      if (enclosing.isEmpty()) return fqn();
      return joinWithDot(pkg, List.of(enclosing.get(0)), "");
    }

    /**
     * The collision key — the short name an import line is keyed on, i.e. the outermost type in the
     * nesting chain. For a top-level type that is {@link #shortName()}; for a nested type {@code
     * foo.Bar.Baz} it is {@code Bar} (the imported outer type), not {@code Baz}.
     */
    public String collisionKey() {
      return enclosing.isEmpty() ? shortName : enclosing.get(0);
    }

    /**
     * The form emitted at a use site when the type is un-collided (thus importable or in-package):
     * the nesting chain plus the short name (e.g. {@code Bar.Baz}), or just the short name for a
     * top-level type.
     */
    public String renderedShort() {
      return renderedInScope(List.of());
    }

    /**
     * The nesting-chain form of {@link #renderedShort()} with the leading segment dropped when it
     * is a member of the current lexical scope: the leading segment is in the scope chain ({@code
     * scope}, outermost first) AND the reference is in the scope's declaring package ({@code
     * scopePackage}) — a nested member inside its own enclosing type renders bare: {@code
     * Sample.Fixed} inside {@code Sample}, {@code Compat.Choice} inside {@code Compat}. The package
     * test keeps the rule sound for a same-NAMED type that is NOT the enclosing one: a top-level
     * sibling {@code Item} in name scope inside {@code Sample} must stay qualified for its own
     * nested member — {@code Item.ItemCodec} renders {@code Item.ItemCodec}, never {@code
     * ItemCodec} (a top-level class's nested members are only reachable via the qualifier, and the
     * unqualified inner name would be unresolvable).
     */
    public String renderedInScope(List<String> scope, String scopePackage) {
      int drop =
          (!enclosing.isEmpty() && scope.contains(enclosing.get(0)) && pkg.equals(scopePackage))
              ? 1
              : 0;
      return joinWithDot("", enclosing.subList(drop, enclosing.size()), shortName);
    }

    /**
     * {@link #renderedInScope(List, String)} with an empty scope package — for references whose
     * package is unknown or irrelevant to the call site.
     */
    public String renderedInScope(List<String> scope) {
      return renderedInScope(scope, "");
    }

    @Override
    public String resolved(
        String declaringPackage, Set<String> collidedShortNames, List<String> scope) {
      boolean isShort;
      if (pkg.equals("java.lang") || pkg.equals(declaringPackage)) {
        isShort = true; // java.lang or in-package: always short with no import
      } else if (collidedShortNames.contains(collisionKey())) {
        isShort = false; // collided: emit fully-qualified everywhere
      } else {
        isShort = true; // uncollided non-java.lang: emitted as short + imported
      }
      String base = isShort ? renderedInScope(scope, declaringPackage) : fqn();
      if (generics.isEmpty()) {
        return base;
      }
      StringBuilder sb = new StringBuilder(base.length() + 4);
      sb.append(base).append('<');
      for (int i = 0; i < generics.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(generics.get(i).resolved(declaringPackage, collidedShortNames, scope));
      }
      sb.append('>');
      return sb.toString();
    }

    // A qualified ref contributes itself: the collector resolves it and its generics when it
    // resolves the set. A bare short name (no package) contributes nothing — it is in-package or a
    // java.lang type, neither of which needs an import. A nested type in the default package is the
    // same: its enclosing type is in-package, so it too contributes no import.
    @Override
    public Set<TypeRef> referencedTypes() {
      if (pkg.isEmpty()) return Set.of();
      return Set.of((TypeRef) this);
    }

    /** A fully-qualified reference with generics. */
    public Named withGenerics(TypeRef... args) {
      return new Named(pkg, enclosing, shortName, List.of(args));
    }

    /** A fully-qualified reference with generics. */
    public Named withGenerics(List<TypeRef> generics) {
      return new Named(pkg, enclosing, shortName, generics);
    }

    /**
     * A nested type declared inside this one. Returns a new {@link Named} that extends this type's
     * nesting chain with {@link #shortName()} and uses {@code name} as the new innermost short
     * name, so {@code Named.named("foo", "Bar").sub("Baz")} is {@code foo.Bar.Baz}. The child
     * inherits the parent's package and nesting but starts its own (empty) generics — generics are
     * a render-time concern, not an import one, so a nested type does not carry the parent's
     * generics.
     */
    public Named sub(String name) {
      List<String> chain = new ArrayList<>(enclosing);
      chain.add(shortName);
      return new Named(pkg, chain, name, List.of());
    }

    /**
     * Join {@code pkg}, each {@code enclosing} segment, and {@code tail} with dots, dropping
     * blanks.
     */
    private static String joinWithDot(String pkg, List<String> enclosing, String tail) {
      StringBuilder sb = new StringBuilder();
      if (!pkg.isEmpty()) sb.append(pkg);
      for (String e : enclosing) {
        if (sb.length() > 0) sb.append('.');
        sb.append(e);
      }
      if (!tail.isEmpty()) {
        if (sb.length() > 0) sb.append('.');
        sb.append(tail);
      }
      return sb.toString();
    }
  }

  /**
   * A Java primitive type. Renders as the lowercase primitive keyword (e.g. {@code int},{@code
   * void}); always ignores the collision context.
   */
  enum Primitive implements TypeRef {
    VOID("void"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    SHORT("short"),
    CHAR("char"),
    BOOLEAN("boolean"),
    BYTE("byte");

    private final String keyword;

    Primitive(String keyword) {
      this.keyword = keyword;
    }

    @Override
    public String fqn() {
      return keyword;
    }

    @Override
    public String shortName() {
      return keyword;
    }

    @Override
    public String resolved(String declaringPackage, Set<String> collidedShortNames) {
      return keyword;
    }

    @Override
    public Set<TypeRef> referencedTypes() {
      // Primitives have no package — no import needed.
      return Set.of();
    }
  }

  /**
   * An array type ({@code T[]}). Renders as the element type's resolved form followed by {@code
   * []}. Always ignores the collision context — the collision is on the element type, not on the
   * {@code []} suffix.
   *
   * @param element the array element type
   */
  record Array(TypeRef element) implements TypeRef {

    @Override
    public String fqn() {
      return element.fqn() + "[]";
    }

    @Override
    public String shortName() {
      return element.shortName();
    }

    @Override
    public String resolved(String declaringPackage, Set<String> collidedShortNames) {
      return element.resolved(declaringPackage, collidedShortNames) + "[]";
    }

    @Override
    public Set<TypeRef> referencedTypes() {
      return element().referencedTypes();
    }
  }
}
