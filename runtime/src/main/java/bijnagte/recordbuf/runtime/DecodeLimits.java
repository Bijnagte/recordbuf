package bijnagte.recordbuf.runtime;

/**
 * The bounds one binary decode of a generated codec enforces: how deeply messages may nest below
 * the top-level message, and how many bytes nested decodes may be handed in total. Callers tighten
 * or loosen them by binding a {@link #CURRENT} around the decode call; with none bound, {@link
 * #defaults()} applies.
 *
 * @param maxDepth the nesting levels allowed below the top-level message; must be positive
 * @param maxTotalBytes the cumulative bytes nested decodes may be handed; must be positive
 */
public record DecodeLimits(int maxDepth, long maxTotalBytes) {

  /** Nesting depth allowed below the top-level message; equal to protobuf-java's default. */
  public static final int DEFAULT_MAX_DEPTH = 100;

  /** Cumulative nested-decode bytes allowed per top-level decode (64 MiB). */
  public static final long DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024;

  /** The limits a caller binds with {@code ScopedValue.where(CURRENT, limits)}. */
  public static final ScopedValue<DecodeLimits> CURRENT = ScopedValue.newInstance();

  public DecodeLimits {
    if (maxDepth <= 0) {
      throw new IllegalArgumentException("maxDepth must be positive: " + maxDepth);
    }
    if (maxTotalBytes <= 0) {
      throw new IllegalArgumentException("maxTotalBytes must be positive: " + maxTotalBytes);
    }
  }

  /** The limits in force when no caller has bound {@link #CURRENT}. */
  public static DecodeLimits defaults() {
    return new DecodeLimits(DEFAULT_MAX_DEPTH, DEFAULT_MAX_TOTAL_BYTES);
  }
}
