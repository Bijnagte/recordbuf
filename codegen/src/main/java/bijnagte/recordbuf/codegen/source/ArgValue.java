package bijnagte.recordbuf.codegen.source;

/**
 * One value of an {@link Arg}, in one of two forms: a {@link Str} string literal (rendered quoted,
 * the {@code @JsonProperty("name")} / {@code @SuppressWarnings("all")} shape) or a {@link Ref}
 * structured type reference (rendered as the type's collector-resolved name plus a suffix — the
 * {@code @JsonSerialize(using = JsonTemporal.X.class)} shape, where the collector both imports the
 * type and, on a short-name collision, emits it fully-qualified at the reference site).
 */
public sealed interface ArgValue permits ArgValue.Str, ArgValue.Ref {

  /**
   * Build a string-literal value.
   *
   * @param text the literal text (rendered quoted)
   * @return the value
   */
  static Str str(String text) {
    return new Str(text);
  }

  /**
   * Build a class-literal value over a type reference: renders as {@code <resolved name>.class}.
   *
   * @param ref the type the literal is taken from
   * @return the value
   */
  static Ref classLit(TypeRef ref) {
    return new Ref(ref, ".class");
  }

  /**
   * A string-literal value; the renderer owns the quoting.
   *
   * @param text the literal text
   */
  record Str(String text) implements ArgValue {}

  /**
   * A type-reference value: the {@code ref} resolved through the file's type collector at render
   * time, with {@code suffix} appended to the resolved name (a blank suffix renders the bare name;
   * {@code .class} renders a class literal).
   *
   * @param ref the type reference
   * @param suffix the text appended to the resolved name (no leading space; typically blank or
   *     {@code .class})
   */
  record Ref(TypeRef ref, String suffix) implements ArgValue {}
}
