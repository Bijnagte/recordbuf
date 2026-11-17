package bijnagte.recordbuf.plugin.model;

import bijnagte.recordbuf.codegen.source.TypeRef;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a protobuf enum.
 *
 * @param name short name (last path component)
 * @param constants the enum's constants
 * @param deprecated {@code option deprecated = true}
 * @param comment leading doc comment, if captured
 * @param interfaces the fully-typed Java interfaces the enum should emit in an {@code implements}
 *     clause, as {@link TypeRef.Named} references, in declaration order and de-duplicated (never
 *     {@code null}); empty when none are declared. Parsed from the {@code
 *     recordbuf.java_enum_interface} option's FQN strings.
 */
// The record is deliberately named `Enum` because it is the first-class, 1:1 model of a protobuf
// `enum` keyword; the clash with `java.lang.Enum` is accepted rather than rename-rippling it
// through
// the whole model + generators.
@SuppressWarnings("AvoidCommonTypeNames")
@RecordBuilder
public record Enum(
    String name,
    List<EnumConstant> constants,
    boolean deprecated,
    @Nullable String comment,
    List<TypeRef> interfaces) {
  /** One entry in an enum. */
  @RecordBuilder
  public record EnumConstant(
      String name, int number, boolean deprecated, @Nullable String comment) {}

  /**
   * Enforce the never-{@code null}, empty-default invariant on {@link #interfaces} even when a
   * caller (or the record-builder's default initialization) supplies {@code null}.
   */
  public Enum {
    if (interfaces == null) interfaces = List.of();
  }
}
