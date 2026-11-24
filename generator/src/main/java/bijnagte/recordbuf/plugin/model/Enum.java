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
  /**
   * One entry in an enum.
   *
   * @param name the Java constant name: the upper-cased proto value name
   * @param number the proto number
   * @param deprecated {@code option deprecated = true} on the value
   * @param comment leading doc comment, if captured
   * @param aliases the other proto spellings merged into this constant because they upper-case to
   *     {@code name} (for example {@code moo} for {@code MOO}), in declaration order, never {@code
   *     null}; empty when no other value upper-cases to {@code name}. In a file with the Jackson
   *     surface, Jackson reads each as an alias of the constant.
   */
  @RecordBuilder
  public record EnumConstant(
      String name, int number, boolean deprecated, @Nullable String comment, List<String> aliases) {
    /** A constant with no case-colliding alias. */
    public EnumConstant(String name, int number, boolean deprecated, @Nullable String comment) {
      this(name, number, deprecated, comment, List.of());
    }

    /**
     * Enforce the never-{@code null}, empty-default invariant on {@link #aliases} even when a
     * caller (or the record-builder's default initialization) supplies {@code null}.
     */
    public EnumConstant {
      if (aliases == null) aliases = List.of();
    }
  }

  /**
   * Enforce the never-{@code null}, empty-default invariant on {@link #interfaces} even when a
   * caller (or the record-builder's default initialization) supplies {@code null}.
   */
  public Enum {
    if (interfaces == null) interfaces = List.of();
  }
}
