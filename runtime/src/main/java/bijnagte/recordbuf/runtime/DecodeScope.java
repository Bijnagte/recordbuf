package bijnagte.recordbuf.runtime;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;

/**
 * Bounds one binary decode of a generated codec by nesting depth and cumulative nested bytes. A
 * generated {@code decode(byte[])} routes through {@link #decode(byte[], Body)}: the outermost call
 * binds fresh state; a call made while that state is bound is a nested decode, which is checked
 * against the limits and rebinds the state one level deeper. Rebinding is scoped, so the state
 * unwinds on any throw and the next top-level decode on the thread starts at depth zero.
 */
public final class DecodeScope {

  /** One message level's decode body, given that level's wire bytes. */
  @FunctionalInterface
  public interface Body<T> {
    T decode(byte[] inBytes) throws IOException;
  }

  /**
   * The current level and the limits in force for the whole top-level decode. The {@link Budget} is
   * shared by every level of one top-level decode, so sibling nested decodes draw from one total.
   */
  private record State(int depth, DecodeLimits limits, Budget budget) {}

  /** Cumulative nested-decode bytes handed out so far within one top-level decode. */
  private static final class Budget {
    private long spentBytes;
  }

  private static final ScopedValue<State> CURRENT = ScopedValue.newInstance();

  private DecodeScope() {}

  /**
   * Runs {@code body} over {@code inBytes} as one message level. Top level: the limits come from
   * {@link DecodeLimits#CURRENT} (or {@link DecodeLimits#defaults()} when unbound). Nested: the
   * level is checked against the depth limit and {@code inBytes.length} is charged to the shared
   * budget, so a payload that exactly reaches the cap passes and one byte over throws.
   *
   * @throws InvalidProtocolBufferException when nesting exceeds the depth limit or cumulative
   *     nested bytes exceed the size limit
   */
  public static <T> T decode(byte[] inBytes, Body<T> body) throws IOException {
    if (!CURRENT.isBound()) {
      DecodeLimits limits = DecodeLimits.CURRENT.orElse(DecodeLimits.defaults());
      State top = new State(0, limits, new Budget());
      return ScopedValue.where(CURRENT, top).call(() -> body.decode(inBytes));
    }

    State outer = CURRENT.get();
    int depth = outer.depth() + 1;
    if (depth > outer.limits().maxDepth()) {
      throw new InvalidProtocolBufferException(
          "recursion depth exceeds the decode limit of " + outer.limits().maxDepth());
    }
    Budget budget = outer.budget();
    if (inBytes.length > outer.limits().maxTotalBytes() - budget.spentBytes) {
      throw new InvalidProtocolBufferException(
          "nested decode bytes exceed the decode limit of " + outer.limits().maxTotalBytes());
    }
    budget.spentBytes += inBytes.length;
    State nested = new State(depth, outer.limits(), budget);
    return ScopedValue.where(CURRENT, nested).call(() -> body.decode(inBytes));
  }

  /** The depth of the level bound on this thread, or -1 outside any decode. Tests read it. */
  static int currentDepth() {
    return CURRENT.isBound() ? CURRENT.get().depth() : -1;
  }
}
