package bijnagte.recordbuf.fixture.golden;

import java.util.Optional;

/**
 * One serialization format a golden case is checked in. An implementation owns the four things the
 * matrix needs: the golden file {@link #extension()}, whether a record takes part at all ({@link
 * #notApplicableReason}), how to serialize a record, and how to deserialize golden content back to
 * a record.
 *
 * <p>The sealed subtypes fix the golden content type and with it the comparison rule: {@link Json}
 * goldens are text compared by {@link GoldenAssertions#assertJsonMatches}, {@link Binary} goldens
 * are hex files holding bytes compared by {@link GoldenAssertions#assertBinaryMatches}.
 */
public sealed interface GoldenFormat permits GoldenFormat.Json, GoldenFormat.Binary {

  /** Returns the golden file extension without the leading dot, e.g. {@code "pb.hex"}. */
  String extension();

  /**
   * Returns why this format cannot represent {@code record}, or empty if it can. A case that
   * declares an outcome other than not-applicable for a record the format rejects fails
   * construction.
   */
  default Optional<String> notApplicableReason(Object record) {
    return Optional.empty();
  }

  /** A format whose goldens are JSON text. */
  non-sealed interface Json extends GoldenFormat {

    /** Serializes {@code record} to JSON text. */
    String serialize(Object record) throws Exception;

    /** Deserializes golden JSON text to a record of {@code recordType}. */
    Object deserialize(String goldenText, Class<?> recordType) throws Exception;
  }

  /** A format whose goldens are hex-encoded bytes. */
  non-sealed interface Binary extends GoldenFormat {

    /** Serializes {@code record} to bytes. */
    byte[] serialize(Object record) throws Exception;

    /** Deserializes golden bytes to a record of {@code recordType}. */
    Object deserialize(byte[] goldenBytes, Class<?> recordType) throws Exception;
  }
}
