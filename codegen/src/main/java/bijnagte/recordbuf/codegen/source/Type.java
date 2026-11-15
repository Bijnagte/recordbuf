package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.SourceRenderer;
import java.util.List;

/**
 * The source-AST node for a single top-level or nested type declaration. Each variant ({@link
 * RecordSpec}, {@link ClassSpec}, {@link EnumSpec}, {@link InterfaceSpec}) is a top-level record
 * that carries its own header fields and its own <em>typed body</em>: the member lists that kind
 * can have. A record heads its body with {@link Component components}; an enum heads its body with
 * {@link EnumConstant constants}; a class holds fields, constructors, static blocks and methods; an
 * interface holds methods and nested types only.
 *
 * <p>The interface itself exposes only what every variant shares -- {@link #name()}, {@link
 * #mods()}, {@link #javadoc()}, {@link #annotations()}, and {@link #nested()} (every variant may
 * nest types). Every variant carries a uniform {@code List<Annotation>} ({@link #annotations()}); a
 * single {@link SourceRenderer} path emits it (one annotation per line, before the node's Javadoc),
 * so the ad-hoc {@code @Deprecated}/{@code @Override}/{@code @SuppressWarnings}/{@code @Nullable}
 * handling collapses into one model. Each variant is built via a hand-written, ergonomic nested
 * {@code Builder} ({@code RecordSpec.Builder}, ...); the compact constructors still seed optional
 * fields to their null-safe defaults, so {@code new RecordSpec(...)} callers work unchanged.
 *
 * <p>Every builder keeps a transient {@code deprecated(boolean)} sugar that, when true, adds the
 * {@link Annotation#DEPRECATED} marker to that node's list -- the previous per-node boolean flag
 * now lives as an {@link Annotation} instance, not a parallel field.
 */
public sealed interface Type permits RecordSpec, ClassSpec, EnumSpec, InterfaceSpec {
  String name();

  Modifiers mods();

  Javadoc javadoc();

  /**
   * The uniform annotation list the node carries (the record/class/interface/enum and -- through
   * the same model -- their components, methods, params, and enum constants). Empty when none, so a
   * node without annotations renders none.
   */
  List<Annotation> annotations();

  /** The nested types (each a nested {@link Type}); every variant may nest. */
  List<Type> nested();
}
