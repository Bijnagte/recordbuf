package bijnagte.recordbuf.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * The canonical temporal JSON object {@code {"seconds": "<int64>", "nanos": <int>}} read off a
 * parser positioned on its {@code START_OBJECT}: the one place the four temporal readers (java.time
 * and protobuf-message, Instant/Timestamp and Duration) parse it. A missing member is 0, unknown
 * members are skipped, and {@code seconds} is accepted as a string or a number.
 *
 * @param seconds the {@code seconds} member
 * @param nanos the {@code nanos} member
 */
record SecondsNanos(long seconds, int nanos) {

  /**
   * Reads the object the parser is positioned on, leaving it on its {@code END_OBJECT}.
   *
   * @param p a parser whose current token is {@code START_OBJECT}
   * @return the pair
   */
  static SecondsNanos read(JsonParser p) {
    long seconds = 0;
    int nanos = 0;
    while (p.nextToken() != JsonToken.END_OBJECT) {
      if (p.currentName().equals("seconds")) {
        p.nextToken();
        seconds = Long.parseLong(p.getValueAsString());
      } else if (p.currentName().equals("nanos")) {
        p.nextToken();
        nanos = (int) p.getLongValue();
      } else {
        p.skipChildren();
      }
    }
    return new SecondsNanos(seconds, nanos);
  }
}
