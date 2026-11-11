package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * The leading modifier keywords for a declaration -- {@code public static final} for {@code static
 * final class GreeterCodec}, for example. The structured model (DEC-1) replaces the previous joined
 * string: a {@link Visibility} plus the orthogonal {@code static}/{@code final}/{@code sealed}/{
 * code default} flags. The renderer joins the set tokens in canonical Java keyword order via {@link
 * #render()}.
 *
 * <p>Visibility is a single enum ({@link #visibility} = {@code null} means "no visibility keyword",
 * the package-private default). The orthogonal flags model the non-visibility modifier keywords. No
 * raw/escape modifier string exists -- the six tokens observed across all six generator mappings
 * ({@code public}/{@code private}/{@code static}/{@code final}/{@code sealed}/{@code default}) are
 * fully covered; a seventh token, if it ever appears, is added as a typed flag + a KTD, mirroring
 * the body AST's fragment escape.
 *
 * <p>Build via {@link #builder()}: the builder's toggles read as intent ({@code
 * Modifiers.builder().public_().static_().build()}). A node builder under {@link Type} exposes the
 * same toggles so a caller sets modifiers without ever naming a string.
 */
public record Modifiers(
    Visibility visibility, boolean isStatic, boolean isFinal, boolean isSealed, boolean isDefault) {

  /** A declaration's four visibility keywords. */
  public enum Visibility {
    PUBLIC("public"),
    PROTECTED("protected"),
    PACKAGE_PRIVATE(""),
    PRIVATE("private");

    private final String keyword;

    Visibility(String keyword) {
      this.keyword = keyword;
    }

    /** The keyword this visibility renders as (empty for {@code PACKAGE_PRIVATE}). */
    public String keyword() {
      return keyword;
    }
  }

  /** A declaration with no modifiers of its own (renders {@code ""}). */
  public static Modifiers none() {
    return new Modifiers(null, false, false, false, false);
  }

  /** A fresh, empty builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether this modifier set renders nothing. */
  public boolean isEmpty() {
    return visibility == null && !isStatic && !isFinal && !isSealed && !isDefault;
  }

  /**
   * Join the set tokens in canonical Java keyword order -- {@code [visibility] static final sealed
   * default}. Package-private visibility (blank keyword) and absent flags are skipped; an all-empty
   * set renders {@code ""}.
   */
  public String render() {
    List<String> parts = new ArrayList<>();
    if (visibility != null && !visibility.keyword().isEmpty()) parts.add(visibility.keyword());
    if (isStatic) parts.add("static");
    if (isFinal) parts.add("final");
    if (isSealed) parts.add("sealed");
    if (isDefault) parts.add("default");
    return String.join(" ", parts);
  }

  /**
   * The modifier keywords followed by the one separating space the caller needs before the type /
   * keyword. Empty when there are no modifiers -- preserves the renderer's "append a trailing space
   * only when non-empty" contract.
   */
  public String withSpace() {
    return isEmpty() ? "" : render() + " ";
  }

  /**
   * An accumulative builder for {@link Modifiers}. Toggles read as intent and each sets its flag to
   * {@code true}; the visibility toggles ({@link #public_() public_()}/{@link #protected_()
   * protected_()}/{@link #private_() private_()}) delegate to the {@link #visibility} enum.
   */
  public static final class Builder {
    private Visibility visibility;
    private boolean isStatic;
    private boolean isFinal;
    private boolean isSealed;
    private boolean isDefault;

    private Builder() {}

    /** Set the visibility dimension directly (the canonical entry point). */
    public Builder visibility(Visibility v) {
      this.visibility = v;
      return this;
    }

    /** Set visibility to {@code public}. */
    public Builder public_() {
      this.visibility = Visibility.PUBLIC;
      return this;
    }

    /** Set visibility to {@code protected}. */
    public Builder protected_() {
      this.visibility = Visibility.PROTECTED;
      return this;
    }

    /** Set visibility to {@code private}. */
    public Builder private_() {
      this.visibility = Visibility.PRIVATE;
      return this;
    }

    /** Set the {@code static} flag. */
    public Builder static_() {
      this.isStatic = true;
      return this;
    }

    /** Set the {@code final} flag. */
    public Builder final_() {
      this.isFinal = true;
      return this;
    }

    /** Set the {@code sealed} flag. */
    public Builder sealed_() {
      this.isSealed = true;
      return this;
    }

    /** Set the {@code default} flag. */
    public Builder default_() {
      this.isDefault = true;
      return this;
    }

    /** Construct the {@link Modifiers}. */
    public Modifiers build() {
      return new Modifiers(visibility, isStatic, isFinal, isSealed, isDefault);
    }
  }
}
