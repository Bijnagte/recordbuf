package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.UnknownField;
import bijnagte.recordbuf.runtime.UnknownFields;
import bijnagte.recordbuf.runtime.WireType;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire-folds the record's retained unknown JSON properties into the binary unknown-field carrier
 * (U6 of the 2026-11-20 Jackson plan). On a {@code recordbuf.jackson}-enabled file the record's
 * {@code jsonExtras} component — the any-getter/any-setter retention surface, projected flat onto
 * the JSON object and absorbed from it on deserialize — is folded into the carrier when the record
 * is encoded, so a record round-tripped through JSON survives the wire exactly as a binary-captured
 * unknown field would: one synthetic length-delimited entry per retained property, its value the
 * UTF-8 bytes of the property's serialized JSON under a deterministic synthetic field number
 * derived from the property name.
 *
 * <p>The fold is idempotent: an entry already present in the carrier with the same field number and
 * value bytes is not re-added, so encoding the same record twice (bytes in, bytes out) is stable
 * and the carrier never grows on repeated encode/decode cycles.
 */
public final class JsonExtras {

  private JsonExtras() {}

  /**
   * Fold the retained properties into the carrier: a new carrier whose entries are the carrier's
   * followed by one synthetic length-delimited entry per retained property (entries already present
   * — same field number, same value bytes — are not duplicated).
   *
   * @param carrier the record's unknown-field carrier (the round-tripped bytes, if any)
   * @param extras the record's retained properties; {@code null} or empty folds to the carrier
   *     unchanged
   * @return a new carrier with the folded entries, or the carrier itself when {@code extras} is
   *     null or empty
   */
  public static UnknownFields fold(UnknownFields carrier, @Nullable Map<String, JsonNode> extras) {
    if (extras == null || extras.isEmpty()) {
      return carrier;
    }
    List<UnknownField> merged = new ArrayList<>(carrier.fields());
    for (Map.Entry<String, JsonNode> entry : extras.entrySet()) {
      byte[] value = lengthDelimited(entry.getValue().toString().getBytes(StandardCharsets.UTF_8));
      int number = fieldNumber(entry.getKey());
      boolean present =
          merged.stream()
              .anyMatch(unknown ->
                  unknown.fieldNumber() == number && Arrays.equals(unknown.value(), value));
      if (!present) {
        merged.add(new UnknownField(number, WireType.LENGTH_DELIMITED, value));
      }
    }
    return UnknownFields.of(merged);
  }

  /**
   * The deterministic synthetic field number under which a retained JSON property's bytes are
   * re-emitted: the property name's 32-bit fold mapped into the proto3 field-number range ([1,
   * 2^29) — a wider mask would overflow the wire tag's number-encoding shift on the encode path).
   * Distinct names fold into distinct numbers in practice; a collision would merge two retained
   * properties' bytes into one entry, which still decodes as an unknown field (the carrier does not
   * interpret field identity) — accepted as a wire-format loss.
   *
   * @param name the property name
   * @return a field number in {@code [1, 2^29)}
   */
  public static int fieldNumber(String name) {
    long hash = 0;
    for (int i = 0; i < name.length(); i++) {
      hash = hash * 31 + name.charAt(i);
    }
    return 1 + (int) (hash % 0x20000000L); // proto3 field numbers are < 2^29
  }

  /**
   * Assemble a length-delimited value byte sequence: the unsigned varint length, then the content.
   */
  static byte[] lengthDelimited(byte[] content) {
    byte[] length = varint64(content.length & 0xFFFFFFFFL);
    byte[] out = new byte[length.length + content.length];
    System.arraycopy(length, 0, out, 0, length.length);
    System.arraycopy(content, 0, out, length.length, content.length);
    return out;
  }

  /** Encode a non-negative value as an unsigned little-endian base-128 varint. */
  private static byte[] varint64(long value) {
    List<Byte> bytes = new ArrayList<>();
    while ((value & ~0x7FL) != 0) {
      bytes.add((byte) ((int) (value & 0x7F) | 0x80));
      value >>>= 7;
    }
    bytes.add((byte) (int) value);
    byte[] out = new byte[bytes.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = bytes.get(i);
    }
    return out;
  }
}
