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
   * Resolve to the form emitted at a type position: a short name for an uncollided type, a
   * fully-qualified name for a collided type. Primitives and arrays ignore the collision context
   * (they always render bare).
   *
   * @param declaringPackage the package of the file being generated ({@code null} means the default
   *     package)
   * @param collidedShortNames the set of short names that are shared by two or more distinct FQNs
   * @return the resolved name to emit at this position
   */
  String resolved(String declaringPackage, Set<String> collidedShortNames);

  /**
   * Default render — delegates to {@link #resolved(String, Set)} unpacked via {@link
   * ResolutionContext}. Override in a variant only if it needs different render behavior from its
   * resolution form.
   */
  @Override
  default String render(ResolutionContext ctx) {
    return resolved(ctx.declaringPackage(), ctx.collidedShortNames());
  }

  // --- factories ---------------------------------------------------------------

  /**
   * Parse a type reference string into a {@link TypeRef}.
   *
   * <p>Syntax: {@code [pkg.]Short<args>} where {@code args} is an optional comma-separated list of
   * nested type references. Also handles bare primitive names (no dot, no angle brackets) and a
   * trailing {@code []} array suffix, which parses as an {@link Array} of the element type.
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
        return Named.ofBase("", ref);
      }
      return Named.ofBase(ref.substring(0, lastDot), ref.substring(lastDot + 1));
    }
    String base = ref.substring(0, lt);
    String argsStr = ref.substring(lt + 1, ref.lastIndexOf('>'));
    int lastDot = base.lastIndexOf('.');
    String pkg = lastDot < 0 ? "" : base.substring(0, lastDot);
    String short_ = lastDot < 0 ? base : base.substring(lastDot + 1);
    List<TypeRef> genericArgs = parseGenericArgs(argsStr);
    return named(pkg, short_, genericArgs);
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
    return new Named(pkg, shortName, List.copyOf(generics));
  }

  /** Convenience: {@link #named(String, String, List)} with varargs. */
  static Named named(String pkg, String shortName, TypeRef... generics) {
    return new Named(pkg, shortName, List.of(generics));
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
   * A fully-qualified type reference such as {@code java.util.HashMap} or {@code
   * java.util.List<String>}. Generic arguments are stored as a list of nested {@link TypeRef}s.
   *
   * @param pkg the package prefix (empty string for the default package)
   * @param shortName the simple class/interface name
   * @param generics the generic type arguments (empty for a non-generic type)
   */
  record Named(String pkg, String shortName, List<TypeRef> generics) implements TypeRef {

    public Named {
      generics = List.copyOf(generics);
    }

    /**
     * Internal factory that does not copy generics. Used by {@link #of(String)} for the base path.
     */
    static Named ofBase(String pkg, String shortName) {
      return new Named(pkg, shortName, List.of());
    }

    @Override
    public String fqn() {
      if (pkg.isEmpty()) {
        return shortName;
      }
      return pkg + "." + shortName;
    }

    @Override
    public String shortName() {
      return shortName;
    }

    @Override
    public String resolved(String declaringPackage, Set<String> collidedShortNames) {
      boolean isShort;
      if (pkg.equals("java.lang") || pkg.equals(declaringPackage)) {
        isShort = true; // java.lang or in-package: always short with no import
      } else if (collidedShortNames.contains(this.shortName)) {
        isShort = false; // collided: emit fully-qualified everywhere
      } else {
        isShort = true; // uncollided non-java.lang: emitted as short + imported
      }
      String base = isShort ? this.shortName : fqn();
      if (generics.isEmpty()) {
        return base;
      }
      StringBuilder sb = new StringBuilder(base.length() + 4);
      sb.append(base).append('<');
      for (int i = 0; i < generics.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(generics.get(i).resolved(declaringPackage, collidedShortNames));
      }
      sb.append('>');
      return sb.toString();
    }

    // A qualified ref contributes itself: the collector resolves it and its generics when it
    // resolves the set. A bare short name (no package) contributes nothing — it is in-package or a
    // java.lang type, neither of which needs an import.
    @Override
    public Set<TypeRef> referencedTypes() {
      if (pkg.isEmpty()) return Set.of();
      return Set.of((TypeRef) this);
    }

    /** A fully-qualified reference with generics. */
    public Named withGenerics(TypeRef... args) {
      return new Named(pkg, shortName, List.of(args));
    }

    /** A fully-qualified reference with generics. */
    public Named withGenerics(List<TypeRef> generics) {
      return new Named(pkg, shortName, generics);
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
