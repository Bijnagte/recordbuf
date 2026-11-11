package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code enum} constant: {@code NAME} with optional ctor args {@code (…)} (the proto number).
 * The ctor args are a list of value expressions; the renderer wraps the list in parens, so a caller
 * supplies the args without parens. An empty list renders the bare {@code NAME}.
 */
public record EnumConstant(String name, List<String> ctorArgs, Javadoc javadoc, boolean deprecated)
    implements Member {
  // ctorArgs -> empty (renders the bare NAME when the list is empty); javadoc -> empty.
  // `deprecated` is a primitive (false by default); `name` is required.
  public EnumConstant {
    ctorArgs = ctorArgs == null ? List.of() : ctorArgs;
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
  }

  /**
   * Entry point: a constant identified by {@link #name}, the one always-required field; the ctor
   * args, Javadoc, and deprecation accumulate as before.
   */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /** An accumulating, ergonomic {@link EnumConstant} builder. */
  public static final class Builder {
    private final String name;
    private final List<String> ctorArgs = new ArrayList<>();
    private Javadoc javadoc;
    private boolean deprecated;

    /** Seed the {@link #name} (the one always-required field); the rest accumulate. */
    public Builder(String name) {
      this.name = name;
    }

    /**
     * Append one or more ctor arg value expressions (e.g. the proto number). Calls accumulate, and
     * a single call may take several; no args renders the bare name. The renderer supplies the
     * surrounding parens.
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
    public EnumConstant build() {
      return new EnumConstant(name, List.copyOf(ctorArgs), javadoc, deprecated);
    }
  }
}
