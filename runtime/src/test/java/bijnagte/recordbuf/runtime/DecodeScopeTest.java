package bijnagte.recordbuf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the decode scope: the depth and cumulative-byte limits a nested decode is
 * checked against, and the caller override through {@link DecodeLimits#CURRENT}.
 */
class DecodeScopeTest {

  /**
   * Enters {@code levels} nested decodes, each re-entering {@link DecodeScope#decode}, and returns
   * how many were entered.
   */
  private static int nest(int levels) throws IOException {
    if (levels == 0) {
      return 0;
    }
    return 1 + DecodeScope.decode(new byte[0], in -> nest(levels - 1));
  }

  /** Enters {@code levels} nested decodes, recording the depth each level observes. */
  private static int recordDepths(int levels, List<Integer> observed) throws IOException {
    observed.add(DecodeScope.currentDepth());
    if (levels == 0) {
      return 0;
    }
    return 1 + DecodeScope.decode(new byte[0], in -> recordDepths(levels - 1, observed));
  }

  /** The depth each level observes runs 0 through the depth limit, one per nested decode. */
  @Test
  void observedDepthIsZeroThroughTheLimit() throws IOException {
    List<Integer> observed = new ArrayList<>();

    DecodeScope.decode(new byte[0], in -> recordDepths(DecodeLimits.DEFAULT_MAX_DEPTH, observed));

    assertEquals(
        IntStream.rangeClosed(0, DecodeLimits.DEFAULT_MAX_DEPTH).boxed().toList(), observed);
  }

  /**
   * Runs a top-level decode of {@code inBytes} under {@code limits} bound in {@link
   * DecodeLimits#CURRENT}.
   */
  private static <T> T withLimits(DecodeLimits limits, byte[] inBytes, DecodeScope.Body<T> body)
      throws IOException {
    return ScopedValue.where(DecodeLimits.CURRENT, limits)
        .call(() -> DecodeScope.decode(inBytes, body));
  }

  /** The default depth admits 100 nested levels below the top-level message. */
  @Test
  void defaultDepthAdmitsHundredNestedLevels() throws IOException {
    int entered = DecodeScope.decode(new byte[0], in -> nest(DecodeLimits.DEFAULT_MAX_DEPTH));

    assertEquals(100, entered);
  }

  /** The 101st nested level is rejected with an {@link InvalidProtocolBufferException}. */
  @Test
  void defaultDepthRejectsOneLevelBeyond() {
    assertThrows(
        InvalidProtocolBufferException.class,
        () -> DecodeScope.decode(new byte[0], in -> nest(DecodeLimits.DEFAULT_MAX_DEPTH + 1)));
  }

  /** A bound depth limit admits the third nested level and rejects the fourth. */
  @Test
  void boundDepthOverridesDefault() throws IOException {
    DecodeLimits three = new DecodeLimits(3, DecodeLimits.DEFAULT_MAX_TOTAL_BYTES);

    int entered = withLimits(three, new byte[0], in -> nest(3));
    assertEquals(3, entered);

    assertThrows(
        InvalidProtocolBufferException.class, () -> withLimits(three, new byte[0], in -> nest(4)));
  }

  /** A bound depth limit above the default admits a level the default rejects. */
  @Test
  void boundLargerDepthAdmitsLevelDefaultRejects() throws IOException {
    DecodeLimits twoHundred = new DecodeLimits(200, DecodeLimits.DEFAULT_MAX_TOTAL_BYTES);

    assertThrows(
        InvalidProtocolBufferException.class,
        () -> DecodeScope.decode(new byte[0], in -> nest(150)));

    int entered = withLimits(twoHundred, new byte[0], in -> nest(150));
    assertEquals(150, entered);
  }

  /** Nested bytes exactly at the cap pass; one byte over the cap throws. */
  @Test
  void nestedBytesAtCapPassAndOneOverThrows() throws IOException {
    DecodeLimits ten = new DecodeLimits(100, 10);

    withLimits(
        ten,
        new byte[0],
        in -> {
          DecodeScope.decode(new byte[10], x -> 0);
          return 0;
        });

    assertThrows(
        InvalidProtocolBufferException.class,
        () ->
            withLimits(
                ten,
                new byte[0],
                in -> {
                  DecodeScope.decode(new byte[11], x -> 0);
                  return 0;
                }));
  }

  /** Sibling nested decodes at the same depth draw from one shared budget. */
  @Test
  void siblingNestedDecodesShareOneBudget() {
    DecodeLimits ten = new DecodeLimits(100, 10);

    assertThrows(
        InvalidProtocolBufferException.class,
        () ->
            withLimits(
                ten,
                new byte[0],
                in -> {
                  DecodeScope.decode(new byte[6], x -> 0);
                  DecodeScope.decode(new byte[5], x -> 0);
                  return 0;
                }));
  }

  /** The top-level input is not charged: a large flat input passes a one-byte nested cap. */
  @Test
  void topLevelBytesAreNotCharged() throws IOException {
    DecodeLimits oneByte = new DecodeLimits(100, 1);

    int result = withLimits(oneByte, new byte[1000], in -> 7);

    assertEquals(7, result);
  }

  /** A rejected decode unwinds its bindings: the next top-level decode starts at depth zero. */
  @Test
  void sameThreadDecodesSucceedAfterRejection() throws IOException {
    assertThrows(
        InvalidProtocolBufferException.class,
        () -> DecodeScope.decode(new byte[0], in -> nest(DecodeLimits.DEFAULT_MAX_DEPTH + 1)));

    int entered = DecodeScope.decode(new byte[0], in -> nest(DecodeLimits.DEFAULT_MAX_DEPTH));

    assertEquals(DecodeLimits.DEFAULT_MAX_DEPTH, entered);
  }

  /** A non-positive depth or byte limit is rejected when the limits are constructed. */
  @Test
  void nonPositiveLimitIsRejectedAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new DecodeLimits(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DecodeLimits(1, 0));
  }
}
