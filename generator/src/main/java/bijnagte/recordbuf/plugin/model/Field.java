package bijnagte.recordbuf.plugin.model;

import bijnagte.recordbuf.codegen.source.TypeRef;
import io.soabase.recordbuilder.core.RecordBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a single protobuf field. We deliberately keep this framework-agnostic so
 * writers/generators do not depend on protobuf's classes.
 *
 * @param name proto field name (snake_case)
 * @param javaName java field name (from {@code (java_name)} option, else camelCased)
 * @param number field tag
 * @param label optional / required / repeated
 * @param kind scalar / message / enum / map
 * @param typeUrl the protobuf type URL the field's wire encoding dispatches on (a scalar token like
 *     {@code int32}, or the fully-qualified proto type name for MESSAGE/ENUM). A dispatch/WKT token
 *     — codegen derives Java types from {@link #type()} instead
 * @param type the field's Java type as a {@link TypeRef}: a primitive/boxed scalar, a {@code
 *     List<T>}/{@code Map<K,V>} for repeated/map fields, the referenced record/enum's fully-typed
 *     {@link TypeRef.Named} (the class the field comes from), or a well-known type's native Java
 *     type
 * @param mapValue for MAP, the value's wire token: a proto scalar name, or the fully-qualified
 *     proto type name of a message/enum value
 * @param mapValueKind for MAP, whether the value is a scalar, message or enum; {@code null} for any
 *     other field. The value's Java type is the second generic argument of {@link #type()}
 * @param proto3Optional the raw {@code optional} keyword flag, used (with the kind/label) to decide
 *     presence — see {@link #isNullable()}
 * @param defaultValue textual default (only meaningful for proto2 optionals and for map/scalar
 *     defaults)
 * @param comment the field's own leading doc comment, if captured
 * @param deprecated {@code option deprecated = true} on the field (rendered as a bare
 *     {@code @Deprecated} on the record component)
 * @param jacksonFormat the resolved {@code recordbuf.jackson_format} option (temporal rendering on
 *     a {\code recordbuf.jackson}-enabled file), or {@code null} when absent; the loader fails loud
 *     when set on a file without the toggle
 */
@RecordBuilder
public record Field(
    String name,
    String javaName,
    int number,
    FieldLabel label,
    FieldKind kind,
    String typeUrl,
    TypeRef type,
    @Nullable String mapKey,
    @Nullable String mapValue,
    @Nullable FieldKind mapValueKind,
    boolean proto3Optional,
    @Nullable Object defaultValue,
    @Nullable String comment,
    boolean deprecated,
    @Nullable JacksonTemporalFormat jacksonFormat,
    String jsonName,
    boolean jsonIgnored) {
  /** For a MAP field, the Java type of its keys: the first generic argument of {@link #type()}. */
  public TypeRef mapKeyType() {
    return mapTypeArgument(0);
  }

  /**
   * For a MAP field, the Java type of its values: the second generic argument of {@link #type()}.
   */
  public TypeRef mapValueType() {
    return mapTypeArgument(1);
  }

  private TypeRef mapTypeArgument(int index) {
    if (kind == FieldKind.MAP
        && type instanceof TypeRef.Named named
        && named.generics().size() == 2) {
      return named.generics().get(index);
    }
    throw new IllegalStateException("field " + name + " is not a Map<K, V> field: " + type);
  }

  public boolean isMap() {
    return kind == FieldKind.MAP;
  }

  public boolean isMessage() {
    return kind == FieldKind.MESSAGE;
  }

  public boolean isScalar() {
    return kind == FieldKind.SCALAR;
  }

  public boolean isRepeated() {
    return label == FieldLabel.REPEATED;
  }

  /**
   * Whether this field tracks presence in proto3 — i.e. whether its Java record component is
   * present-able (a boxed/nullable primitive, or a {@code @Nullable} reference) rather than a fixed
   * default-valued component.
   *
   * <p>Proto3 presence rules (proto3-only, per the plan): a singular <em>message</em> always
   * carries presence; a singular <em>scalar</em>/<em>enum</em> carries presence only when the
   * {@code optional} keyword is used. Repeated fields and maps default to empty containers and
   * never carry presence.
   */
  public boolean isNullable() {
    return switch (kind) {
      case MESSAGE -> label != FieldLabel.REPEATED; // singular message: present
      case SCALAR, ENUM -> proto3Optional; // present only via `optional`
      default -> false; // MAP and any other singular kind
    };
  }
}
